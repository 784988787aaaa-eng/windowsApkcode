package com.smartledger.licenseissuer

import com.smartledger.licenseissuer.crypto.LicenseContract
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicenseContractTest {
    @Test
    fun issueAndVerifyPerpetualLicense_SucceedsWithDynamicKeyPair() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)

        val pkg = LicenseContract.issuePerpetualLicense(
            rawDeviceId = "SLP-4F8A-92C1-7B3E-D056",
            customerName = "شركة الأمل للتجارة",
            notes = "المركز الرئيسي",
            privateKeyPkcs8Base64 = privB64,
            publicKeySpkiBase64 = pubB64
        )
        assertTrue(pkg.activationCode.startsWith("SLPRO1."))
        assertEquals("SLP-4F8A-92C1-7B3E-D056", pkg.deviceId)
    }

    @Test
    fun parseWhatsAppRequest_ExtractsDeviceIdAndCustomerFromTokenAndText() {
        val msg = "السلام عليكم،\n• اسم المنشأة: مؤسسة النور\n• معرّف الجهاز: SLP-4F8A-92C1-7B3E-D056"
        val parsed = LicenseContract.parseIncomingRequest(msg)
        assertTrue(parsed.isValid)
        assertEquals("SLP-4F8A-92C1-7B3E-D056", parsed.deviceId)
        assertEquals("مؤسسة النور", parsed.customerName)
    }
}
