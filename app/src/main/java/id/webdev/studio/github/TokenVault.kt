package id.webdev.studio.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan token GitHub terenkripsi (AES-256-GCM, kunci di Android Keystore).
 * Bila Keystore tidak tersedia, token TIDAK ditulis ke disk (hanya di memori selama proses hidup).
 */
class TokenVault(ctx: Context) {
    private val sp = ctx.getSharedPreferences("github", Context.MODE_PRIVATE)
    private var memory: String? = null

    private fun key(create: Boolean): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: if (!create) null else {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            g.generateKey()
        }
    } catch (e: Exception) { null }

    fun save(token: String) {
        memory = token
        try {
            val k = key(true) ?: return
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            val enc = c.doFinal(token.toByteArray(Charsets.UTF_8))
            sp.edit().putString("tok", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
        } catch (e: Exception) { sp.edit().remove("tok").apply() }
    }

    fun load(): String? {
        memory?.let { return it }
        val raw = sp.getString("tok", null) ?: return null
        return try {
            val (iv, data) = raw.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(false) ?: return null, GCMParameterSpec(128, iv)) }
            String(c.doFinal(data), Charsets.UTF_8).also { memory = it }
        } catch (e: Exception) { null }
    }

    fun clear() { memory = null; sp.edit().remove("tok").remove("login").apply() }

    var login: String?
        get() = sp.getString("login", null)
        set(v) { sp.edit().putString("login", v).apply() }

    private companion object { const val ALIAS = "webdev_github_token" }
}
