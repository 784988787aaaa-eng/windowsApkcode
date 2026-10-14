package com.smartledger.licenseissuer.crypto

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

enum class LicenseStatusState(val labelAr: String) {
    ACTIVE("صالح ونشط"),
    EXPIRED("منتهي الصلاحية"),
    REVOKED("ملغى"),
    NOT_YET_VALID("لم يبدأ سريانه"),
    INVALID("غير صالح"),
    DEVICE_MISMATCH("غير مرتبط بالجهاز")
}

data class ParsedActivationRequest(
    val productCode: String,
    val deviceId: String,
    val customerName: String,
    val usedOperations: Int = 0,
    val isValid: Boolean,
    val errorMessageAr: String = ""
)

data class IssuedLicensePackage(
    val licenseId: String,
    val productCode: String,
    val licenseType: String,
    val deviceId: String,
    val customerName: String,
    val customerCode: String,
    val issuedAtUnixSeconds: Long,
    val startsAtUnixSeconds: Long,
    val expiresAtUnixSeconds: Long,
    val edition: String,
    val notes: String,
    val canonicalPayloadJson: String,
    val activationCode: String
)

/**
 * Shared cryptographic licensing contract between Android License Issuer and Windows SmartLedger Pro.
 * Supports Perpetual and Timed licenses (with start/expiration timestamps), customer codes,
 * device binding, and signed revocation certificates (SLREV1).
 */
object LicenseContract {
    const val TOKEN_PREFIX = "SLPRO1"
    const val REVOCATION_PREFIX = "SLREV1"
    const val REQUEST_PREFIX = "SLREQ1"
    const val PRODUCT_CODE_SLPRO = "SLPRO"
    const val LICENSE_TYPE_PERPETUAL = "PERPETUAL"
    const val LICENSE_TYPE_TIMED = "TIMED"
    const val FORMAT_VERSION = 1

    private val DEVICE_CODE_REGEX = Regex("SLP-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}", RegexOption.IGNORE_CASE)
    private val HEX_64_REGEX = Regex("\b[0-9A-F]{64}\b", RegexOption.IGNORE_CASE)
    private val REQUEST_TOKEN_REGEX = Regex("SLREQ1:([A-Za-z0-9_-]+)")

    fun normalizeDeviceId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.trim().uppercase().filter { it.isLetterOrDigit() }
        val withoutPrefix = if (clean.startsWith("SLP")) clean.substring(3) else clean
        if (withoutPrefix.length == 64 && withoutPrefix.all { it in '0'..'9' || it in 'A'..'F' }) {
            return withoutPrefix.substring(0, 16)
        }
        return withoutPrefix
    }

    fun formatCanonicalDeviceCode(raw: String?): String {
        val clean = normalizeDeviceId(raw)
        if (clean.length == 16 && clean.all { it in '0'..'9' || it in 'A'..'F' }) {
            return "SLP-${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}"
        }
        return raw?.trim()?.uppercase() ?: ""
    }

    fun isValidDeviceCode(raw: String?): Boolean {
        val clean = normalizeDeviceId(raw)
        return clean.length == 16 && clean.all { it in '0'..'9' || it in 'A'..'F' }
    }

    fun parseIncomingRequest(rawText: String): ParsedActivationRequest {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ParsedActivationRequest(PRODUCT_CODE_SLPRO, "", "", 0, false, "النص المدخل فارغ. ألصق رسالة طلب التفعيل أو معرّف جهاز Windows.")
        }

        val tokenMatch = REQUEST_TOKEN_REGEX.find(trimmed)
        if (tokenMatch != null) {
            try {
                val jsonText = String(base64UrlDecode(tokenMatch.groupValues[1]), StandardCharsets.UTF_8)
                val prod = extractJsonStringField(jsonText, "p").ifBlank { PRODUCT_CODE_SLPRO }
                val did = formatCanonicalDeviceCode(extractJsonStringField(jsonText, "did"))
                val cn = extractJsonStringField(jsonText, "cn").trim()
                val ops = extractJsonLongField(jsonText, "ops").toInt()
                if (isValidDeviceCode(did)) {
                    return ParsedActivationRequest(prod, did, cn, ops, true)
                }
            } catch (_: Exception) {
            }
        }

        val deviceMatch = DEVICE_CODE_REGEX.find(trimmed)
        if (deviceMatch != null) {
            val did = formatCanonicalDeviceCode(deviceMatch.value)
            var extractedCustomer = ""
            for (line in trimmed.lines()) {
                if ((line.contains("اسم المنشأة") || line.contains("العميل")) && line.contains(":")) {
                    extractedCustomer = line.substringAfter(":").trim()
                    break
                }
            }
            return ParsedActivationRequest(PRODUCT_CODE_SLPRO, did, extractedCustomer, 0, true)
        }

        val hex64Match = HEX_64_REGEX.find(trimmed)
        if (hex64Match != null) {
            val did = formatCanonicalDeviceCode(hex64Match.value)
            if (isValidDeviceCode(did)) {
                return ParsedActivationRequest(PRODUCT_CODE_SLPRO, did, "", 0, true)
            }
        }

        if (isValidDeviceCode(trimmed)) {
            return ParsedActivationRequest(PRODUCT_CODE_SLPRO, formatCanonicalDeviceCode(trimmed), "", 0, true)
        }

        return ParsedActivationRequest(
            PRODUCT_CODE_SLPRO,
            "",
            "",
            0,
            false,
            "لم يتم العثور على معرّف جهاز Windows صالح بصيغة SLP-XXXX-XXXX-XXXX-XXXX داخل النص."
        )
    }

    fun issuePerpetualLicense(
        rawDeviceId: String,
        customerName: String,
        notes: String = "",
        privateKeyPkcs8Base64: String,
        publicKeySpkiBase64: String? = null,
        issuedAtUnixOverride: Long? = null,
        licenseIdOverride: String? = null,
        customerCode: String = ""
    ): IssuedLicensePackage = issueLicense(
        productCode = PRODUCT_CODE_SLPRO,
        licenseType = LICENSE_TYPE_PERPETUAL,
        rawDeviceId = rawDeviceId,
        customerName = customerName,
        customerCode = customerCode,
        startsAtUnixSeconds = issuedAtUnixOverride ?: (System.currentTimeMillis() / 1000L),
        expiresAtUnixSeconds = 0L,
        notes = notes,
        privateKeyPkcs8Base64 = privateKeyPkcs8Base64,
        publicKeySpkiBase64 = publicKeySpkiBase64,
        issuedAtUnixOverride = issuedAtUnixOverride,
        licenseIdOverride = licenseIdOverride
    )

    fun issueLicense(
        productCode: String = PRODUCT_CODE_SLPRO,
        licenseType: String = LICENSE_TYPE_PERPETUAL,
        rawDeviceId: String,
        customerName: String,
        customerCode: String = "",
        startsAtUnixSeconds: Long = 0L,
        expiresAtUnixSeconds: Long = 0L,
        notes: String = "",
        privateKeyPkcs8Base64: String,
        publicKeySpkiBase64: String? = null,
        issuedAtUnixOverride: Long? = null,
        licenseIdOverride: String? = null
    ): IssuedLicensePackage {
        require(isValidDeviceCode(rawDeviceId)) {
            "معرّف جهاز Windows غير صالح؛ يجب أن يتكون من 16 رمزاً ست عشرياً (مثال: SLP-4F8A-92C1-7B3E-D056)."
        }
        require(privateKeyPkcs8Base64.isNotBlank()) {
            "لم يتم إعداد مفتاح التوقيع الخاص بعد؛ يرجى استيراد المفتاح أو توليد زوج مفاتيح من تبويب المفاتيح والإعدادات."
        }
        val cleanProduct = productCode.trim().uppercase()
        require(cleanProduct == PRODUCT_CODE_SLPRO) {
            "المنتج المستهدف غير مدعوم."
        }
        val cleanType = licenseType.trim().uppercase()
        require(cleanType == LICENSE_TYPE_PERPETUAL || cleanType == LICENSE_TYPE_TIMED) {
            "نوع الترخيص يجب أن يكون دائماً (PERPETUAL) أو محدداً بمدة (TIMED)."
        }

        val cleanCustomer = customerName.trim()
        require(cleanCustomer.length >= 2) {
            "اسم العميل أو الجهة مطلوب (حرفان على الأقل)."
        }

        val canonicalDeviceId = formatCanonicalDeviceCode(rawDeviceId)
        val licenseId = (licenseIdOverride ?: UUID.randomUUID().toString().replace("-", "").substring(0, 16)).uppercase()
        val issuedAt = issuedAtUnixOverride ?: (System.currentTimeMillis() / 1000L)
        val effectiveStart = if (startsAtUnixSeconds > 0L) startsAtUnixSeconds else issuedAt
        val effectiveExpiry = if (cleanType == LICENSE_TYPE_TIMED) expiresAtUnixSeconds else 0L

        if (cleanType == LICENSE_TYPE_TIMED) {
            require(effectiveExpiry > effectiveStart) {
                "في الترخيص المحدد بمدة، يجب أن يكون تاريخ الانتهاء بعد تاريخ البداية."
            }
        }

        val payloadJson = buildCanonicalPayloadJson(
            version = FORMAT_VERSION,
            productCode = cleanProduct,
            licenseType = cleanType,
            licenseId = licenseId,
            deviceId = canonicalDeviceId,
            customerName = cleanCustomer,
            customerCode = customerCode.trim(),
            issuedAt = issuedAt,
            startsAt = effectiveStart,
            expiresAt = effectiveExpiry,
            edition = "PRO",
            notes = notes.trim()
        )
        val payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)

        val privateKey = loadPrivateKey(privateKeyPkcs8Base64)
        val derSignature = signSha256WithEcdsaDer(payloadBytes, privateKey)
        val ieeeP1363Signature = convertDerToIeeeP1363(derSignature, 32)

        if (!publicKeySpkiBase64.isNullOrBlank()) {
            val pubKey = loadPublicKey(publicKeySpkiBase64)
            check(verifySha256WithEcdsaIeeeP1363(payloadBytes, ieeeP1363Signature, pubKey)) {
                "فشل التحقق الذاتي من التوقيع الرقمي بعد الإصدار؛ المفتاح الخاص لا يطابق المفتاح العام."
            }
        }

        val token = "$TOKEN_PREFIX.${base64UrlEncode(payloadBytes)}.${base64UrlEncode(ieeeP1363Signature)}"
        return IssuedLicensePackage(
            licenseId = licenseId,
            productCode = cleanProduct,
            licenseType = cleanType,
            deviceId = canonicalDeviceId,
            customerName = cleanCustomer,
            customerCode = customerCode.trim(),
            issuedAtUnixSeconds = issuedAt,
            startsAtUnixSeconds = effectiveStart,
            expiresAtUnixSeconds = effectiveExpiry,
            edition = "PRO",
            notes = notes.trim(),
            canonicalPayloadJson = payloadJson,
            activationCode = token
        )
    }

    fun issueRevocationCertificate(
        licenseId: String,
        deviceId: String,
        reason: String,
        privateKeyPkcs8Base64: String,
        publicKeySpkiBase64: String? = null,
        revokedAtUnixOverride: Long? = null
    ): String {
        val cleanLid = licenseId.trim().uppercase()
        val cleanDid = formatCanonicalDeviceCode(deviceId)
        val revokedAt = revokedAtUnixOverride ?: (System.currentTimeMillis() / 1000L)
        val cleanReason = reason.trim().ifEmpty { "إلغاء إداري من مسؤول التراخيص" }

        val sb = StringBuilder(180)
        sb.append('{')
        sb.append(""v":1,")
        sb.append(""p":"").append(PRODUCT_CODE_SLPRO).append("",")
        sb.append(""lid":"").append(escapeJson(cleanLid)).append("",")
        sb.append(""did":"").append(escapeJson(cleanDid)).append("",")
        sb.append(""rat":").append(revokedAt).append(',')
        sb.append(""rsn":"").append(escapeJson(cleanReason)).append('"')
        sb.append('}')

        val payloadBytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val privateKey = loadPrivateKey(privateKeyPkcs8Base64)
        val derSignature = signSha256WithEcdsaDer(payloadBytes, privateKey)
        val ieeeP1363Signature = convertDerToIeeeP1363(derSignature, 32)

        if (!publicKeySpkiBase64.isNullOrBlank()) {
            val pubKey = loadPublicKey(publicKeySpkiBase64)
            check(verifySha256WithEcdsaIeeeP1363(payloadBytes, ieeeP1363Signature, pubKey)) {
                "فشل التحقق من توقيع شهادة الإلغاء."
            }
        }

        return "$REVOCATION_PREFIX.${base64UrlEncode(payloadBytes)}.${base64UrlEncode(ieeeP1363Signature)}"
    }

    fun evaluateLicenseTokenStatus(
        activationCode: String,
        publicKeySpkiBase64: String,
        isRevokedInDb: Boolean,
        expectedDeviceId: String? = null,
        nowUnixSeconds: Long = System.currentTimeMillis() / 1000L
    ): LicenseStatusState {
        if (isRevokedInDb) return LicenseStatusState.REVOKED
        val parts = activationCode.trim().split('.')
        if (parts.size != 3 || !parts[0].equals(TOKEN_PREFIX, ignoreCase = true)) {
            return LicenseStatusState.INVALID
        }
        return try {
            val payloadBytes = base64UrlDecode(parts[1])
            val sigBytes = base64UrlDecode(parts[2])
            val pubKey = loadPublicKey(publicKeySpkiBase64)
            if (!verifySha256WithEcdsaIeeeP1363(payloadBytes, sigBytes, pubKey)) {
                return LicenseStatusState.INVALID
            }
            val json = String(payloadBytes, StandardCharsets.UTF_8)
            val did = formatCanonicalDeviceCode(extractJsonStringField(json, "did"))
            if (!expectedDeviceId.isNullOrBlank() && normalizeDeviceId(did) != normalizeDeviceId(expectedDeviceId)) {
                return LicenseStatusState.DEVICE_MISMATCH
            }
            val nbf = extractJsonLongField(json, "nbf")
            val exp = extractJsonLongField(json, "exp")
            if (nbf > 0L && nowUnixSeconds + 86400L < nbf) {
                return LicenseStatusState.NOT_YET_VALID
            }
            if (exp > 0L && nowUnixSeconds > exp) {
                return LicenseStatusState.EXPIRED
            }
            LicenseStatusState.ACTIVE
        } catch (_: Exception) {
            LicenseStatusState.INVALID
        }
    }

    fun buildCanonicalPayloadJson(
        version: Int,
        productCode: String,
        licenseType: String,
        licenseId: String,
        deviceId: String,
        customerName: String,
        customerCode: String = "",
        issuedAt: Long,
        startsAt: Long = 0L,
        expiresAt: Long = 0L,
        edition: String = "PRO",
        notes: String = ""
    ): String {
        val sb = StringBuilder(320)
        sb.append('{')
        sb.append(""v":").append(version).append(',')
        sb.append(""p":"").append(escapeJson(productCode)).append("",")
        sb.append(""t":"").append(escapeJson(licenseType)).append("",")
        sb.append(""lid":"").append(escapeJson(licenseId)).append("",")
        sb.append(""did":"").append(escapeJson(deviceId)).append("",")
        sb.append(""cn":"").append(escapeJson(customerName)).append("",")
        if (customerCode.isNotBlank()) {
            sb.append(""cid":"").append(escapeJson(customerCode)).append("",")
        }
        sb.append(""iat":").append(issuedAt).append(',')
        if (startsAt > 0L) {
            sb.append(""nbf":").append(startsAt).append(',')
        }
        if (expiresAt > 0L) {
            sb.append(""exp":").append(expiresAt).append(',')
        }
        sb.append(""ed":"").append(escapeJson(edition)).append('"')
        if (notes.isNotBlank()) {
            sb.append(","nt":"").append(escapeJson(notes)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\' -> sb.append("\\")
                '"' -> sb.append("\"")
                '
' -> sb.append("\n")
                '' -> sb.append("\r")
                '	' -> sb.append("\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun extractJsonStringField(json: String, key: String): String {
        val pattern = Regex(""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"")
        val match = pattern.find(json) ?: return ""
        return match.groupValues[1]
            .replace("\"", """)
            .replace("\\", "\")
            .replace("\n", "
")
            .replace("\r", "")
            .replace("\t", "	")
    }

    internal fun extractJsonLongField(json: String, key: String): Long {
        val pattern = Regex(""${Regex.escape(key)}"\s*:\s*(-?\d+)")
        val match = pattern.find(json) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    fun loadPrivateKey(pkcs8Base64: String): PrivateKey {
        val clean = pkcs8Base64
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(clean)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    fun loadPublicKey(spkiBase64: String): PublicKey {
        val clean = spkiBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(clean)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    private fun signSha256WithEcdsaDer(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    fun verifySha256WithEcdsaIeeeP1363(data: ByteArray, signature64: ByteArray, publicKey: PublicKey): Boolean {
        if (signature64.size != 64) return false
        val der = convertIeeeP1363ToDer(signature64)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(der)
    }

    fun convertDerToIeeeP1363(der: ByteArray, coordSize: Int = 32): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid DER ECDSA signature." }
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) {
            offset += (der[1].toInt() and 0x7F)
        }
        require(der[offset] == 0x02.toByte()) { "Invalid DER R integer tag." }
        val rLen = der[offset + 1].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen

        require(der[offset] == 0x02.toByte()) { "Invalid DER S integer tag." }
        val sLen = der[offset + 1].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset + 2, offset + 2 + sLen)

        val out = ByteArray(coordSize * 2)
        copyBigEndianUnsigned(rBytes, out, 0, coordSize)
        copyBigEndianUnsigned(sBytes, out, coordSize, coordSize)
        return out
    }

    fun convertIeeeP1363ToDer(p1363: ByteArray): ByteArray {
        val half = p1363.size / 2
        val r = BigInteger(1, p1363.copyOfRange(0, half)).toByteArray()
        val s = BigInteger(1, p1363.copyOfRange(half, p1363.size)).toByteArray()
        val totalLen = 2 + r.size + 2 + s.size
        val der = ByteArray(2 + totalLen)
        der[0] = 0x30
        der[1] = totalLen.toByte()
        der[2] = 0x02
        der[3] = r.size.toByte()
        System.arraycopy(r, 0, der, 4, r.size)
        val sOffset = 4 + r.size
        der[sOffset] = 0x02
        der[sOffset + 1] = s.size.toByte()
        System.arraycopy(s, 0, der, sOffset + 2, s.size)
        return der
    }

    private fun copyBigEndianUnsigned(src: ByteArray, dest: ByteArray, destOffset: Int, length: Int) {
        var start = 0
        while (start < src.size - 1 && src[start] == 0.toByte()) {
            start++
        }
        val significantLen = src.size - start
        require(significantLen <= length) { "Coordinate length exceeds $length bytes." }
        System.arraycopy(src, start, dest, destOffset + (length - significantLen), significantLen)
    }

    fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun base64UrlDecode(input: String): ByteArray {
        return Base64.getUrlDecoder().decode(input.trim())
    }
}
