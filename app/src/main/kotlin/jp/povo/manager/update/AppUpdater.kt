package jp.povo.manager.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import jp.povo.manager.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A release newer than the running build, and where to get its APK. */
data class AvailableUpdate(
    val version: String,
    val notes: String?,
    val apkUrl: String,
    val apkSizeBytes: Long?,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AvailableUpdate) : UpdateCheck
    /** The check itself failed — no network, rate limit, no release yet. */
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Checks GitHub Releases for a newer build and hands the APK to the installer.
 *
 * The app is not on Play, so it has to update itself. That is done with the
 * platform's own package installer rather than anything clever: the APK is
 * downloaded, handed over through a [FileProvider] uri, and Android asks the
 * user to confirm. Nothing is installed silently, and a signature mismatch is
 * rejected by the OS — which is the real reason the release key has to be
 * fixed, since a re-keyed build cannot upgrade an installed one.
 *
 * `HttpURLConnection` rather than a new HTTP dependency: two requests to one
 * host does not justify one, and povo-core's client is deliberately scoped to
 * povo's own API.
 */
class AppUpdater(private val context: Context) {

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$API_BASE/repos/$REPO/releases/latest")
            val release = json.parseToJsonElement(body) as? JsonObject
                ?: return@runCatching UpdateCheck.Failed("リリース情報を解釈できませんでした")

            val tag = release.str("tag_name")
                ?: return@runCatching UpdateCheck.Failed("リリースにタグがありません")
            val asset = (release["assets"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
            val apkUrl = asset?.str("browser_download_url")
                ?: return@runCatching UpdateCheck.Failed("リリースに APK が添付されていません")

            if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                UpdateCheck.UpToDate
            } else {
                UpdateCheck.Available(
                    AvailableUpdate(
                        version = tag.removePrefix("v"),
                        notes = release.str("body")?.takeIf(String::isNotBlank),
                        apkUrl = apkUrl,
                        apkSizeBytes = (asset["size"] as? JsonPrimitive)?.content?.toLongOrNull(),
                    ),
                )
            }
        }.getOrElse { failure ->
            Log.w(TAG, "update check failed", failure)
            UpdateCheck.Failed(describe(failure))
        }
    }

    /**
     * Downloads the APK and asks Android to install it.
     *
     * @param onProgress fraction downloaded, or null while the total is unknown
     *   (GitHub's asset redirect does not always report a length).
     */
    suspend fun downloadAndInstall(
        update: AvailableUpdate,
        onProgress: (Float?) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "updates").apply { mkdirs() }
                .resolve("povo-manager-${update.version}.apk")

            (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
            }.use { connection ->
                val total = update.apkSizeBytes
                    ?: connection.contentLengthLong.takeIf { it > 0 }
                var read = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            onProgress(total?.let { (read.toDouble() / it).toFloat() })
                        }
                    }
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    // The installer runs in another process, so it needs both
                    // the read grant and its own task.
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** The release page, for when the in-app path is not available. */
    fun releasePageIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "https://github.com/$REPO/releases/latest".toUri())

    private fun get(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            // Pins the response shape; without it GitHub may serve a different
            // representation as the API evolves.
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "povo-manager/${BuildConfig.VERSION_NAME}")
        }.use { connection ->
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            connection.inputStream.bufferedReader().readText()
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Turns a failure into something a person can act on.
     *
     * A raw status code is not that: 404 in particular is the *expected* answer
     * before the first release exists, and reads as a bug rather than as "there
     * is nothing published yet".
     */
    private fun describe(failure: Throwable): String = when {
        failure is HttpStatusException && failure.status == 404 ->
            "まだリリースが公開されていません"
        failure is HttpStatusException && failure.status == 403 ->
            "GitHub の API 制限に達しました。しばらく待って再試行してください"
        failure is HttpStatusException -> "GitHub が ${failure.status} を返しました"
        failure is IOException -> "ネットワークに接続できません"
        else -> failure.message ?: "更新の確認に失敗しました"
    }

    companion object {
        private const val TAG = "PovoUpdater"
        private const val API_BASE = "https://api.github.com"
        private const val TIMEOUT_MILLIS = 20_000

        /** Set from `povo.updateRepo`; see gradle.properties. */
        val REPO: String = BuildConfig.UPDATE_REPO

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Compares dotted versions numerically, ignoring a leading `v`.
         *
         * String comparison is wrong here — `0.10.0` sorts below `0.9.0` — and
         * a tag with anything unparseable in it is treated as *not* newer, so a
         * malformed release never prompts anyone to install something.
         */
        fun isNewer(tag: String, current: String): Boolean {
            val candidate = parts(tag) ?: return false
            val running = parts(current) ?: return false
            for (i in 0 until maxOf(candidate.size, running.size)) {
                val a = candidate.getOrElse(i) { 0 }
                val b = running.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun parts(version: String): List<Int>? =
            version.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
                .split('.')
                .filter(String::isNotEmpty)
                .map { it.toIntOrNull() ?: return null }
                .takeIf(List<Int>::isNotEmpty)
    }
}

/** Carries the status so [AppUpdater] can tell 404 apart from a real fault. */
private class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** [HttpURLConnection] is not [AutoCloseable], so `use` needs supplying. */
private inline fun <R> HttpURLConnection.use(block: (HttpURLConnection) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }
