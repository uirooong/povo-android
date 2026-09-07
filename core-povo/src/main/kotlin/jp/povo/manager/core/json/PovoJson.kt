package jp.povo.manager.core.json

import jp.povo.manager.core.model.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON helpers for reading povo's responses.
 *
 * Everything here is deliberately lenient. The endpoints are undocumented and
 * change without notice, keys appear in both camelCase and snake_case (some
 * payloads carry *both* spellings of the same field), and numbers that look
 * like counts arrive fractional. A parser that insists on a shape would break
 * on the next server-side tweak, so each accessor tries the plausible spellings
 * and returns null rather than throwing.
 */
internal object PovoJson {

    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String): JsonObject? =
        runCatching { lenient.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /**
     * Peels `result` / `data` wrappers off a payload.
     *
     * Both shapes occur, sometimes nested, and sometimes neither is present —
     * `account/usage/plan/get` puts its buckets directly under `data`, while
     * the profile endpoint has been seen both bare and wrapped in `result`.
     *
     * @param stopAt keys that mean "you have arrived"; unwrapping halts as soon
     *   as the current object contains one, so a legitimate `data` field is not
     *   mistaken for a wrapper.
     */
    fun unwrap(root: JsonObject?, vararg stopAt: String, maxDepth: Int = 3): JsonObject? {
        var current = root ?: return null
        repeat(maxDepth) {
            if (stopAt.any { it in current }) return current
            val next = (current["result"] ?: current["data"]) as? JsonObject ?: return current
            current = next
        }
        return current
    }

    fun obj(parent: JsonObject?, vararg names: String): JsonObject? =
        names.firstNotNullOfOrNull { parent?.get(it) as? JsonObject }

    fun array(parent: JsonObject?, vararg names: String): JsonArray? =
        names.firstNotNullOfOrNull { parent?.get(it) as? JsonArray }

    fun string(parent: JsonObject?, vararg names: String): String? =
        names.firstNotNullOfOrNull {
            (parent?.get(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }?.takeIf(String::isNotBlank)

    /**
     * Reads a number as [Double].
     *
     * Always Double, never Long: data sizes come back fractional (a real
     * response carried `58147676.87109375` kilobytes) and an integer parse
     * throws on those.
     */
    fun double(parent: JsonObject?, vararg names: String): Double? =
        names.firstNotNullOfOrNull { (parent?.get(it) as? JsonPrimitive)?.doubleOrNull }

    fun long(parent: JsonObject?, vararg names: String): Long? =
        names.firstNotNullOfOrNull { (parent?.get(it) as? JsonPrimitive)?.longOrNull }

    fun bool(parent: JsonObject?, vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { (parent?.get(it) as? JsonPrimitive)?.booleanOrNull }

    /** Reads a `{prefix, postfix, value, discount}` price object. */
    fun money(parent: JsonObject?, vararg names: String): Money? {
        val node = obj(parent, *names) ?: return null
        val value = double(node, "value", "amount") ?: return null
        return Money(
            value = value,
            prefix = string(node, "prefix"),
            postfix = string(node, "postfix"),
            discount = double(node, "discount"),
        )
    }

    /**
     * Normalises the assorted timestamp encodings to epoch milliseconds.
     *
     * Seconds and milliseconds both occur. They are told apart by magnitude:
     * anything past ~1973 in milliseconds is far larger than any plausible
     * seconds value, so the threshold separates them cleanly.
     */
    fun epochMillis(parent: JsonObject?, vararg names: String): Long? {
        val raw = long(parent, *names) ?: return null
        return if (raw > 100_000_000_000L) raw else raw * 1000
    }
}
