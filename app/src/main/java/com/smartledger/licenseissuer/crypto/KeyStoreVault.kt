package com.smartledger.licenseissuer.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class IssuerContactConfig(
    val issuerTitle: String,
    val supportPhone: String,
    val footerNote: String
)

class KeyStoreVault(context: Context) {
    private val prefs = context.getSharedPreferences("smartledger_issuer_vault", Context.MODE_PRIVATE)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPER_KEY_ALIAS = "SmartLedgerPro_Issuer_AES_Master"
        private const val PREF_ENC_PRIV = "enc_priv_pkcs8"
        private const val PREF_ENC_IV = "enc_priv_iv"
        private const val PREF_PUB_SPKI = "pub_spki_b64"
        private const val PREF_ISSUER_TITLE = "contact_issuer_title"
        private const val PREF_SUPPORT_PHONE = "contact_support_phone"
        private const val PREF_FOOTER_NOTE = "contact_footer_note"

        const val DEFAULT_WINDOWS_PUBLIC_KEY_SPKI_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9YrHLP2g0RExh7JeZQAdZS5YbAJu06X3a+Det9G4oWse9wdqCjjVu122OtgpG75idP1u6t+PYof5YXDMz35RcQ=="
    }

    fun isSigningKeyConfigured(): Boolean {
        return prefs.contains(PREF_ENC_PRIV) && prefs.contains(PREF_ENC_IV)
    }

    fun getPublicKeySpkiBase64(): String {
        return prefs.getString(PREF_PUB_SPKI, DEFAULT_WINDOWS_PUBLIC_KEY_SPKI_B64) ?: DEFAULT_WINDOWS_PUBLIC_KEY_SPKI_B64
    }

    fun getPublicKeyFingerprint(): String {
        return try {
            val clean = getPublicKeySpkiBase64().replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(clean)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.take(8).joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            "غير متاح"
        }
    }

    internal fun loadDecryptedPrivateKeyPkcs8Base64(): String {
        val encB64 = prefs.getString(PREF_ENC_PRIV, null)
            ?: throw IllegalStateException("لم يتم إعداد مفتاح الإصدار الخاص بعد. افتح تبويب «المفاتيح والإعدادات» لاستيراد مفتاح المالك أو توليد زوج مفاتيح جديد.")
        val ivB64 = prefs.getString(PREF_ENC_IV, null)
            ?: throw IllegalStateException("بيانات التشفير المحلية للمفتاح غير مكتملة؛ يرجى إعادة استيراد المفتاح الخاص.")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, Base64.getDecoder().decode(ivB64))
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesWrapperKey(), spec)
        val plainBytes = cipher.doFinal(Base64.getDecoder().decode(encB64))
        return String(plainBytes, Charsets.UTF_8)
    }

    fun importPrivateKey(privateKeyPkcs8Base64: String, publicKeySpkiBase64: String = getPublicKeySpkiBase64()) {
        val cleanPriv = privateKeyPkcs8Base64.trim()
        val cleanPub = publicKeySpkiBase64.trim().ifEmpty { DEFAULT_WINDOWS_PUBLIC_KEY_SPKI_B64 }

        val priv = LicenseContract.loadPrivateKey(cleanPriv)
        val pub = LicenseContract.loadPublicKey(cleanPub)
        val testPayload = "SLPRO_KEY_VERIFY_V1".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(priv)
        signer.update(testPayload)
        val p1363 = LicenseContract.convertDerToIeeeP1363(signer.sign(), 32)
        require(LicenseContract.verifySha256WithEcdsaIeeeP1363(testPayload, p1363, pub)) {
            "المفتاح الخاص المدخل لا يطابق المفتاح العام المحدد. تأكد من لصق المفتاح الصحيح."
        }
        saveEncryptedKeyPair(cleanPriv, cleanPub)
    }

    fun generateNewProductionKeyPair(): String {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        saveEncryptedKeyPair(privB64, pubB64)
        return pubB64
    }

    fun getContactConfig(): IssuerContactConfig {
        return IssuerContactConfig(
            issuerTitle = prefs.getString(PREF_ISSUER_TITLE, "إدارة تراخيص الدفتر الذكي — SmartLedger Pro") ?: "إدارة تراخيص الدفتر الذكي — SmartLedger Pro",
            supportPhone = prefs.getString(PREF_SUPPORT_PHONE, "") ?: "",
            footerNote = prefs.getString(PREF_FOOTER_NOTE, "ترخيص موثق رقمياً يعمل دون الحاجة للاتصال بالإنترنت.") ?: "ترخيص موثق رقمياً يعمل دون الحاجة للاتصال بالإنترنت."
        )
    }

    fun saveContactConfig(config: IssuerContactConfig) {
        prefs.edit()
            .putString(PREF_ISSUER_TITLE, config.issuerTitle.trim())
            .putString(PREF_SUPPORT_PHONE, config.supportPhone.trim())
            .putString(PREF_FOOTER_NOTE, config.footerNote.trim())
            .apply()
    }

    private fun saveEncryptedKeyPair(privateKeyPkcs8B64: String, publicKeySpkiB64: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesWrapperKey())
        val encrypted = cipher.doFinal(privateKeyPkcs8B64.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        prefs.edit()
            .putString(PREF_ENC_PRIV, Base64.getEncoder().encodeToString(encrypted))
            .putString(PREF_ENC_IV, Base64.getEncoder().encodeToString(iv))
            .putString(PREF_PUB_SPKI, publicKeySpkiB64)
            .apply()
    }

    private fun getOrCreateAesWrapperKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(WRAPPER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                WRAPPER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
