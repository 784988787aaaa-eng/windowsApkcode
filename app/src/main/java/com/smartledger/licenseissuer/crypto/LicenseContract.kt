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

data class ParsedActivationRequest(
    val deviceId: String,
    val customerName: String,
    val usedOperations: Int = 0,
    val isValid: Boolean,
    val errorMessageAr: String = ""
)

data class IssuedLicensePackage(
    val licenseId: String,
    val deviceId: String,
    val customerName: String,
    val issuedAtUnixSeconds: Long,
    val edition: String,
    val notes: String,
    val canonicalPayloadJson: String,
    val activationCode: String
)

/**
 * Implements the exact SLPRO1 cryptographic contract shared between Android License Issuer
 * and Windows SmartLedger Pro (ECDSA P-256 / SHA-256 / IEEE P1363 64-byte signature).
 * Uses deterministic RFC 8259 JSON serialization without external library dependencies.
 */
object LicenseContract {
    const val TOKEN_PREFIX = "SLPRO1"
    const val REQUEST_PREFIX = "SLREQ1"
    const val PRODUCT_CODE = "SLPRO"
    const val LICENSE_TYPE = "PERPETUAL"
    const val FORMAT_VERSION = 1

    private val DEVICE_CODE_REGEX = Regex("SLP-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}", RegexOption.IGNORE_CASE)
    private val HEX_64_REGEX = Regex("\b[0-9A-F]{64}\b", RegexOption.IGNORE_CASE)
    private val REQUEST_TOKEN_REGEX = Regex("SLREQ1:([A-Za-z0-9_-]+)")

    fun normalizeDeviceId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.trim().uppercase().filter { it.isLetterOrDigit() }
        val withoutPrefix = if (clean.startsWith("SLP")) clean.substring(3) else clean
        // If a 64-char SHA-256 legacy hardware ID is provided, normalize to its 16-char canonical prefix
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

    /**
     * Parses either a full WhatsApp request message containing SLREQ1:..., or raw text containing SLP-XXXX-XXXX-XXXX-XXXX.
     */
    fun parseIncomingRequest(rawText: String): ParsedActivationRequest {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ParsedActivationRequest("", "", 0, false, "النص المدخل فارغ. ألصق رسالة طلب التفعيل أو معرّف الجهاز.")
        }

        // 1. Structured SLREQ1:<base64url> token
        val tokenMatch = REQUEST_TOKEN_REGEX.find(trimmed)
        if (tokenMatch != null) {
            try {
                val jsonText = String(base64UrlDecode(tokenMatch.groupValues[1]), StandardCharsets.UTF_8)
                val did = formatCanonicalDeviceCode(extractJsonStringField(jsonText, "did"))
                val cn = extractJsonStringField(jsonText, "cn").trim()
                val ops = extractJsonIntField(jsonText, "ops")
                if (isValidDeviceCode(did)) {
                    return ParsedActivationRequest(did, cn, ops, true)
                }
            } catch (_: Exception) {
                // Fall back to plain text extraction below
            }
        }

        // 2. Extract SLP-XXXX-XXXX-XXXX-XXXX from WhatsApp text
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
            return ParsedActivationRequest(did, extractedCustomer, 0, true)
        }

        // 3. Check 64-char legacy hash or direct 16-char hex
        val hex64Match = HEX_64_REGEX.find(trimmed)
        if (hex64Match != null) {
            val did = formatCanonicalDeviceCode(hex64Match.value)
            if (isValidDeviceCode(did)) {
                return ParsedActivationRequest(did, "", 0, true)
            }
        }

        if (isValidDeviceCode(trimmed)) {
            return ParsedActivationRequest(formatCanonicalDeviceCode(trimmed), "", 0, true)
        }

        return ParsedActivationRequest(
            "",
            "",
            0,
            false,
            "لم يتم العثور على معرّف جهاز صالح بصيغة SLP-XXXX-XXXX-XXXX-XXXX داخل النص."
        )
    }

    /**
     * Issues a cryptographically signed perpetual license token compatible with Windows SmartLedger Pro.
     */
    fun issuePerpetualLicense(
        rawDeviceId: String,
        customerName: String,
        notes: String = "",
        privateKeyPkcs8Base64: String,
        publicKeySpkiBase64: String? = null,
        issuedAtUnixOverride: Long? = null,
        licenseIdOverride: String? = null
    ): IssuedLicensePackage {
        require(isValidDeviceCode(rawDeviceId)) {
            "معرّف الجهاز غير صالح؛ يجب أن يتكون من 16 رمزاً ست عشرياً (مثال: SLP-4F8A-92C1-7B3E-D056)."
        }
        require(privateKeyPkcs8Base64.isNotBlank()) {
            "لم يتم إعداد مفتاح الإصدار الخاص بعد؛ يرجى استيراد المفتاح أو توليد زوج مفاتيح من تبويب المفاتيح والأمان."
        }

        val cleanCustomer = customerName.trim().ifEmpty { "عميل الدفتر الذكي" }
        val canonicalDeviceId = formatCanonicalDeviceCode(rawDeviceId)
        val licenseId = (licenseIdOverride ?: UUID.randomUUID().toString().replace("-", "").substring(0, 16)).uppercase()
        val issuedAt = issuedAtUnixOverride ?: (System.currentTimeMillis() / 1000L)
        val cleanNotes = notes.trim()

        val payloadJson = buildCanonicalPayloadJson(
            version = FORMAT_VERSION,
            productCode = PRODUCT_CODE,
            licenseType = LICENSE_TYPE,
            licenseId = licenseId,
            deviceId = canonicalDeviceId,
            customerName = cleanCustomer,
            issuedAt = issuedAt,
            edition = "PRO",
            notes = cleanNotes
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
            deviceId = canonicalDeviceId,
            customerName = cleanCustomer,
            issuedAtUnixSeconds = issuedAt,
            edition = "PRO",
            notes = cleanNotes,
            canonicalPayloadJson = payloadJson,
            activationCode = token
        )
    }

    fun buildCanonicalPayloadJson(
        version: Int,
        productCode: String,
        licenseType: String,
        licenseId: String,
        deviceId: String,
        customerName: String,
        issuedAt: Long,
        edition: String,
        notes: String
    ): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append(""v":").append(version).append(',')
        sb.append(""p":"").append(escapeJson(productCode)).append("",")
        sb.append(""t":"").append(escapeJson(licenseType)).append("",")
        sb.append(""lid":"").append(escapeJson(licenseId)).append("",")
        sb.append(""did":"").append(escapeJson(deviceId)).append("",")
        sb.append(""cn":"").append(escapeJson(customerName)).append("",")
        sb.append(""iat":").append(issuedAt).append(',')
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

    internal fun extractJsonIntField(json: String, key: String): Int {
        val pattern = Regex(""${Regex.escape(key)}"\s*:\s*(-?\d+)")
        val match = pattern.find(json) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
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
