package dev.deepseek.mobile.harness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens never enter an Agent configuration, transcript, extension export or Linux filesystem. */
internal class ExtensionSecrets(context: Context) {
    private val preferences =
        context.getSharedPreferences("extension-secrets-v080", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("dsha-extension-secrets", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "dsha-extension-secrets",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    @Synchronized
    fun set(id: String, token: String) {
        java.util.UUID.fromString(id)
        require(token.length <= 8192 && token.none { it == '\r' || it == '\n' || it == '\u0000' })
        if (token.isEmpty()) {
            preferences.edit().remove(id).apply()
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        preferences
            .edit()
            .putString(
                id,
                Base64.encodeToString(
                    cipher.iv + cipher.doFinal(token.toByteArray()),
                    Base64.NO_WRAP,
                ),
            )
            .apply()
    }

    @Synchronized
    fun get(id: String): String {
        val stored = preferences.getString(id, null) ?: return ""
        val data = Base64.decode(stored, Base64.NO_WRAP)
        return String(
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                }
                .doFinal(data.copyOfRange(12, data.size)),
        )
    }
}
