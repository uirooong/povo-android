package jp.povo.manager.core

import java.security.SecureRandom

/**
 * Protocol-level constants.
 *
 * These are matched to the values a known-working client sends, not invented.
 * povo-core defaults its own User-Agent to `selfcare/<ver> povo-core/<ver>`;
 * we override it because the reference client that is confirmed to log in
 * successfully sends the plain OkHttp UA instead.
 */
object PovoProtocol {
    /** `X-App-Version` header value. */
    const val APP_VERSION = "1.69.0-JP"

    /** `User-Agent` header value. Set via [PovoClient.setUserAgent] on every client. */
    const val USER_AGENT = "okhttp/4.12.0"

    /** Japan country calling code, used for every SMS OTP request. */
    const val ISD_CODE_JP = "81"

    /**
     * Extra headers to send on every request.
     *
     * Empty on purpose. povo-core omits the `VLI-*` headers because it builds
     * the localized path itself, and omits `Accept`/`Accept-Language` because
     * the decompiled interceptor chain never sets them. Sending the reference
     * client's full header set was tried against a live account and changed
     * nothing — `telco/dashboard` returned the same HTTP 500 either way — so
     * the app stays byte-identical to what the real client sends.
     *
     * The plumbing is kept because it is the only way to test such a hypothesis
     * without patching the Rust crate.
     */
    val EXTRA_HEADERS: Map<String, String> = emptyMap()
}

/**
 * Device identity.
 *
 * The reference client generates 16 CSPRNG bytes rendered as 32 lowercase hex
 * characters — deliberately *not* a UUID, which is what a naive implementation
 * would reach for. The value is minted once when an account is added and then
 * persisted: the server binds OTP and device registration to it, so a device id
 * that changes between runs would invalidate the session.
 *
 * Each account gets its own device id. Sharing one across accounts risks the
 * server treating them as the same device and invalidating earlier sessions.
 */
object DeviceId {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
