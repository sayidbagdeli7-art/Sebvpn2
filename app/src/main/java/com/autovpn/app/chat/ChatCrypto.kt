package com.autovpn.app.chat

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns the shared password into an AES key (locally, never transmitted anywhere)
 * and uses it to encrypt/decrypt chat messages. GitHub only ever sees the base64
 * ciphertext - it never sees the password or the plaintext.
 */
object ChatCrypto {

    private fun keyFromPassword(password: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encrypt(plainText: String, password: String): String {
        val key = keyFromPassword(password)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    /** Returns null if this doesn't decrypt with the given password (wrong password). */
    fun decrypt(payload: String, password: String): String? {
        return try {
            val combined = Base64.decode(payload, Base64.NO_WRAP)
            if (combined.size < 13) return null
            val iv = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val key = keyFromPassword(password)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
