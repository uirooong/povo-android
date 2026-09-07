package jp.povo.manager.core.json

import jp.povo.manager.core.model.DataBucket
import jp.povo.manager.core.model.DataBucketKind
import jp.povo.manager.core.model.PlanUsage
import kotlinx.serialization.json.JsonObject

/**
 * Parses `GET account/usage/plan/get`.
 *
 * povo-core hands this back as a raw JSON string because the payload varies by
 * contract, so the interpreting happens here.
 */
object UsageParser {

    /**
     * @return the parsed usage, or null when the payload carries no recognisable
     *   bucket at all — which is the honest answer for a response whose shape
     *   has changed, and lets the caller keep showing the last good snapshot
     *   instead of rendering zeroes as if they were real.
     */
    fun parse(raw: String): PlanUsage? {
        val root = PovoJson.parse(raw) ?: return null
        // `data` here is the real payload, not an envelope, so stop as soon as a
        // bucket name appears rather than unwrapping past it.
        val data = PovoJson.unwrap(root, *DataBucketKind.entries.map { it.jsonKey }.toTypedArray())
            ?: return null

        val buckets = DataBucketKind.entries.mapNotNull { kind ->
            (data[kind.jsonKey] as? JsonObject)?.let { node -> bucket(kind, node) }
        }
        if (buckets.isEmpty()) return null

        val promotion = PovoJson.obj(data, "promotion_text")
        return PlanUsage(
            buckets = buckets,
            promotionLine1 = PovoJson.string(promotion, "line1"),
            promotionLine2 = PovoJson.string(promotion, "line2"),
        )
    }

    private fun bucket(kind: DataBucketKind, node: JsonObject) = DataBucket(
        kind = kind,
        // Absent means zero here; a bucket object that exists but omits `used`
        // is reporting "nothing used", not "unknown".
        usedKb = PovoJson.double(node, "used", "used_kb") ?: 0.0,
        leftKb = PovoJson.double(node, "left", "remaining") ?: 0.0,
        // Genuinely absent on `bonus` and `boost`, so null rather than zero:
        // "no ceiling reported" and "a ceiling of zero" are different things.
        planKb = PovoJson.double(node, "plan_kb"),
        section = PovoJson.string(node, "section"),
        unit = PovoJson.string(node, "unit", "unit_type"),
        sectionMinValueKb = PovoJson.double(node, "section_min_value"),
    )
}
