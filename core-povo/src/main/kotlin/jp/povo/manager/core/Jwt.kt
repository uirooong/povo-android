package jp.povo.manager.core

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Minimal, non-validating JWT payload reader.
 *
 * povo-core has an equivalent internally, but it is private and only returns
 * *string* claims — so it cannot read `exp`, which is numeric. We need `exp`
 * because refreshing early is pointless: the server hands back the same JWT
 * unless the current one has actually expired. So the refresh policy is
 * "refresh only once [isExpired] is true", and that requires reading `exp`
 * here.
 *
 * There is nothing to verify cryptographically: these tokens come from the auth
 * server over TLS and are never trusted for authorization decisions locally.
 */
object Jwt {
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(token: String): JsonObject? = runCatching {
        val segment = token.split('.').getOrNull(1) ?: return null
        val bytes = Base64.decode(segment, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    }.getOrNull()

    /** Expiry as epoch seconds, or null when the token has no readable `exp`. */
    fun expiresAtEpochSeconds(token: String): Long? =
        payload(token)?.get("exp")?.jsonPrimitive?.longOrNull

    /**
     * The `external_id` claim — the same value povo-core puts in the
     * `X-USER-ID` header, and what `update_device` expects as its user id.
     */
    fun externalId(token: String): String? = runCatching {
        payload(token)?.get("external_id")?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * True when the token is past its expiry.
     *
     * A token with no readable `exp` is treated as expired so that a refresh is
     * attempted rather than a request being sent with a credential we cannot
     * reason about.
     */
    fun isExpired(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val exp = expiresAtEpochSeconds(token) ?: return true
        return exp <= nowEpochSeconds
    }
}
