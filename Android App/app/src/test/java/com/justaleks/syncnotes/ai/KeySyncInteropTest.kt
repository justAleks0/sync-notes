package com.justaleks.syncnotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The point of syncing the key is that a key encrypted on one client opens on
 * another. That only holds while both sides agree on PBKDF2-HMAC-SHA256 at the
 * same iteration count, AES-256-GCM, a 12-byte IV and a 128-bit tag appended to
 * the ciphertext — an agreement nothing in either codebase enforces on its own.
 *
 * So this decrypts a blob produced by the *web* app's WebCrypto implementation,
 * captured verbatim. If either side's parameters drift, this fails rather than
 * a user discovering it as "wrong passphrase" on their new phone.
 *
 * KeySync itself is not called here because it reaches for android.util.Base64
 * and Firestore; this repeats only the crypto, which is the part that has to
 * match.
 */
class KeySyncInteropTest {

    private val webSalt = "yWL86mmRdQqmEhIztXScng=="
    private val webIv = "YefK8W9aXOaNNMrn"
    private val webCt = "dWc6KfvHwS8CHUfWbhVXl7649E/j/w4gy8GqHcRLKAI0Yg6JZHSe8hM="
    private val passphrase = "shared passphrase for interop"
    private val expected = "sk-interop-test-value-123"
    private val iterations = 310_000

    /** java.util.Base64 stands in for android.util.Base64, absent on the JVM. */
    private fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    private fun decrypt(salt: String, iv: String, ct: String, phrase: String): String? =
        runCatching {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(phrase.toCharArray(), decode(salt), iterations, 256)
            val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(iv)))
            String(cipher.doFinal(decode(ct)), Charsets.UTF_8)
        }.getOrNull()

    @Test
    fun `decrypts a key encrypted by the web app`() {
        assertEquals(expected, decrypt(webSalt, webIv, webCt, passphrase))
    }

    @Test
    fun `a wrong passphrase fails instead of returning rubbish`() {
        assertNull(decrypt(webSalt, webIv, webCt, "not the passphrase"))
    }

    @Test
    fun `tampered ciphertext is rejected by the GCM tag`() {
        val bytes = decode(webCt)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)

        assertNull(decrypt(webSalt, webIv, tampered, passphrase))
    }
}
