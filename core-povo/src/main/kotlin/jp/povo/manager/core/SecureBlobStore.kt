package jp.povo.manager.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (auth tokens) with a hardware-backed key and stores the
 * ciphertext on disk.
 *
 * povo-core deliberately does not persist tokens — that is the host platform's
 * job, because that is where the real security guarantees live. On Android that
 * means the Keystore: the AES key never leaves it, so the ciphertext on disk is
 * useless on another device or to another app.
 *
 * Jetpack Security's `EncryptedSharedPreferences` is deprecated, so this talks
 * to the Keystore directly. The format on disk is `iv || ciphertext`, with the
 * 12-byte GCM IV up front.
 *
 * A decrypt failure is treated as "no value" rather than an error: the key is
 * invalidated whenever the user's device credentials change or the app's data is
 * cleared, and the correct response to that is to ask for a fresh login.
 */
class SecureBlobStore(context: Context, private val fileName: String) {

    private val file = File(context.filesDir, fileName)

    fun write(plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val body = cipher.doFinal(plaintext.toByteArray())
        // Write via a temp file so a crash mid-write cannot leave a half-written
        // blob that then fails to decrypt forever.
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeBytes(cipher.iv + body)
        check(tmp.renameTo(file)) { "could not replace $fileName" }
    }

    fun read(): String? {
        if (!file.exists()) return null
        return runCatching {
            val blob = file.readBytes()
            require(blob.size > IV_LENGTH) { "blob too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH),
                )
            }
            cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH).decodeToString()
        }.onFailure {
            Log.w(TAG, "could not decrypt $fileName; treating as absent", it)
        }.getOrNull()
    }

    fun clear() {
        file.delete()
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Tokens must be refreshable by a background worker, so the
                    // key cannot require the user to be present at unlock time.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TAG = "PovoSecureStore"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "povo_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
