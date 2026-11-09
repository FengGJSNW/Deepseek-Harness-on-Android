package dev.deepseek.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class CredentialInfo(
    val id: String,
    val name: String,
    val masked: String,
    val selected: Boolean,
)

/** API credential encryption; neither JavaScript nor backups can read the stored key. */
class KeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("deepseek-api", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "deepseek-api",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun encrypt(value: String): String {
        val cleaned = value.trim()
        require(cleaned.length in 12..512 && !cleaned.any { it.isWhitespace() }) {
            "请输入有效的 API Key"
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded =
            Base64.encodeToString(cipher.iv + cipher.doFinal(cleaned.toByteArray()), Base64.NO_WRAP)
        return encoded
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }

    @Synchronized
    private fun entries(): JSONArray {
        prefs.getString("entries", null)?.let {
            return JSONArray(it)
        }
        val values = JSONArray()
        prefs.getString("encrypted", null)?.let {
            val id = UUID.randomUUID().toString()
            values.put(JSONObject().put("id", id).put("name", "默认密钥").put("encrypted", it))
            check(
                prefs
                    .edit()
                    .putString("entries", values.toString())
                    .putString("selected", id)
                    .remove("encrypted")
                    .commit(),
            ) {
                "密钥迁移失败"
            }
        }
        return values
    }

    @Synchronized
    fun list(): List<CredentialInfo> {
        val values = entries()
        val selected = prefs.getString("selected", "")
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val secret = decrypt(item.getString("encrypted"))
            CredentialInfo(
                item.getString("id"),
                item.getString("name"),
                secret.take(7) + "••••••••" + secret.takeLast(4),
                item.getString("id") == selected,
            )
        }
    }

    @Synchronized
    fun save(value: String, name: String = "默认密钥"): String {
        val encrypted = encrypt(value)
        val values = entries()
        require(values.length() < 32) { "最多保存 32 个密钥" }
        val id = UUID.randomUUID().toString()
        values.put(
            JSONObject()
                .put("id", id)
                .put("name", name.trim().ifEmpty { "DeepSeek" }.take(40))
                .put("encrypted", encrypted),
        )
        check(
            prefs.edit().putString("entries", values.toString()).putString("selected", id).commit(),
        ) {
            "密钥保存失败"
        }
        return id
    }

    @Synchronized
    fun read(id: String = ""): String {
        val values = entries()
        val target = id.ifEmpty { prefs.getString("selected", "").orEmpty() }
        for (i in 0 until values.length()) {
            val item = values.getJSONObject(i)
            if (item.getString("id") == target) return decrypt(item.getString("encrypted"))
        }
        return ""
    }

    @Synchronized
    fun select(id: String) {
        require(read(id).isNotEmpty()) { "密钥不存在" }
        check(prefs.edit().putString("selected", id).commit())
    }

    @Synchronized
    fun delete(id: String) {
        val before = entries()
        val after = JSONArray()
        for (i in 0 until before.length()) {
            val item = before.getJSONObject(i)
            if (item.getString("id") != id) after.put(item)
        }
        val edit = prefs.edit().putString("entries", after.toString())
        if (prefs.getString("selected", "") == id)
            edit.putString(
                "selected",
                if (after.length() > 0) after.getJSONObject(0).getString("id") else "",
            )
        check(edit.commit())
    }

    fun configured() = read().isNotEmpty()

    fun clear() {
        prefs.edit().clear().commit()
    }
}
