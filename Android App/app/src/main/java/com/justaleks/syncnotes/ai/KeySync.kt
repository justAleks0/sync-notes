package com.justaleks.syncnotes.ai

import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional, off by default: keep an encrypted copy of the API key in the account
 * so a new device can unlock it instead of being handed the key again.
 *
 * The encryption is done with a passphrase the user chooses, and that choice is
 * the whole point. Anything the app could decrypt on its own — a key derived from
 * the uid, a secret shipped in the APK, a value stored beside the ciphertext —
 * would also be decryptable by anyone who reached the account, which is precisely
 * the risk that kept the key out of Firestore in the first place.
 *
 * Deliberately interoperable with the web app: PBKDF2-HMAC-SHA256 at the same
 * iteration count, AES-256-GCM with a 12-byte IV and the 128-bit tag appended to
 * the ciphertext, everything base64. A key encrypted in the browser unlocks here
 * and vice versa — which is the entire point of syncing it.
 */
data class EncryptedKey(
    val provider: AiProvider,
    val model: String,
    val salt: String,
    val iv: String,
    val ct: String,
    val iterations: Int,
)

private const val ITERATIONS = 310_000
private const val KEY_BITS = 256
private const val TAG_BITS = 128
private const val IV_BYTES = 12
private const val SALT_BYTES = 16

/** No line breaks or padding surprises — the web app reads these strings too. */
private const val B64 = Base64.NO_WRAP

object KeySync {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun keyDoc(uid: String) =
        db.collection("users").document(uid).collection("private").document("aiKey")

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    suspend fun encrypt(
        apiKey: String,
        passphrase: String,
        provider: AiProvider,
        model: String,
    ): EncryptedKey = withContext(Dispatchers.Default) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt, ITERATIONS),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        EncryptedKey(
            provider = provider,
            model = model,
            salt = Base64.encodeToString(salt, B64),
            iv = Base64.encodeToString(iv, B64),
            ct = Base64.encodeToString(ct, B64),
            iterations = ITERATIONS,
        )
    }

    /**
     * Returns null when the passphrase is wrong.
     *
     * AES-GCM authenticates the ciphertext, so a wrong passphrase fails outright
     * rather than yielding plausible rubbish — the user gets told they mistyped
     * instead of quietly ending up with a broken key.
     */
    suspend fun decrypt(blob: EncryptedKey, passphrase: String): String? =
        withContext(Dispatchers.Default) {
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(passphrase, Base64.decode(blob.salt, B64), blob.iterations),
                    GCMParameterSpec(TAG_BITS, Base64.decode(blob.iv, B64)),
                )
                String(cipher.doFinal(Base64.decode(blob.ct, B64)), Charsets.UTF_8)
            }.getOrNull()
        }

    suspend fun load(uid: String): EncryptedKey? {
        val doc = runCatching { keyDoc(uid).get().await() }.getOrNull() ?: return null
        if (!doc.exists() || doc.getLong("v")?.toInt() != 1) return null

        val salt = doc.getString("salt") ?: return null
        val iv = doc.getString("iv") ?: return null
        val ct = doc.getString("ct") ?: return null

        return EncryptedKey(
            provider = AiProvider.from(doc.getString("provider")),
            model = doc.getString("model").orEmpty(),
            salt = salt,
            iv = iv,
            ct = ct,
            iterations = doc.getLong("iterations")?.toInt() ?: ITERATIONS,
        )
    }

    suspend fun save(uid: String, blob: EncryptedKey) {
        keyDoc(uid).set(
            mapOf(
                "v" to 1,
                "provider" to blob.provider.id,
                "model" to blob.model,
                "salt" to blob.salt,
                "iv" to blob.iv,
                "ct" to blob.ct,
                "iterations" to blob.iterations,
            )
        ).await()
    }

    suspend fun clear(uid: String) {
        keyDoc(uid).delete().await()
    }
}
