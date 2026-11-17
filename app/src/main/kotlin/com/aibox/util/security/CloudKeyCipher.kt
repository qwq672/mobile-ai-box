package com.aibox.util.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aibox.util.log.LogSystem
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 云端 API Key 的本地加密。
 *
 * 使用 Android Keystore 里的 AES-256/GCM 密钥（密钥不出 TEE/StrongBox），
 * 密文格式为 `Base64(IV(12B) + ciphertext + tag)`，直接存 DataStore 即可。
 * 不引入第三方依赖。
 *
 * [encrypt] / [decrypt] 失败时返回空串（调用方按「未配置」处理）。
 */
class CloudKeyCipher(context: Context) {

    /** 别名带上包名，避免同设备上不同 App 共用密钥。 */
    private val alias = "aibox_cloud_api_key_${context.packageName}"

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + encrypted.size)
            iv.copyInto(out, 0)
            encrypted.copyInto(out, iv.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (t: Throwable) {
            LogSystem.error(TAG, "encrypt api key failed", t)
            ""
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE)
            )
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 密钥被清除 / 数据损坏：回退成空 key，用户重新填写即可
            LogSystem.warn(TAG, "decrypt api key failed: ${t.message}")
            ""
        }
    }

    /** 取（或首次创建）Keystore 中的 AES 密钥。 */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "aibox.cloud.key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
