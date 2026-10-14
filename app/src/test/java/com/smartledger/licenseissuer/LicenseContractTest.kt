package com.smartledger.licenseissuer

import com.smartledger.licenseissuer.crypto.LicenseContract
import com.smartledger.licenseissuer.crypto.LicenseStatusState
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicenseContractTest {
    @Test
    fun issueAndEvaluate_PerpetualTimedExpiredAndRevokedLicenses() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        val devId = "SLP-4F8A-92C1-7B3E-D056"

        // 1. Perpetual license
        val perp = LicenseContract.issueLicense(
            productCode = "SLPRO",
            licenseType = "PERPETUAL",
            rawDeviceId = devId,
            customerName = "شركة الأمل للتجارة",
            customerCode = "C-100",
            privateKeyPkcs8Base64 = privB64,
            publicKeySpkiBase64 = pubB64
        )
        assertEquals(
            LicenseStatusState.ACTIVE,
            LicenseContract.evaluateLicenseTokenStatus(perp.activationCode, pubB64, false, devId)
        )

        // 2. Timed license evaluated after expiration
        val timed = LicenseContract.issueLicense(
            productCode = "SLPRO",
            licenseType = "TIMED",
            rawDeviceId = devId,
            customerName = "مؤسسة النور",
            startsAtUnixSeconds = 1700000000L,
            expiresAtUnixSeconds = 1702592000L,
            privateKeyPkcs8Base64 = privB64,
            publicKeySpkiBase64 = pubB64,
            issuedAtUnixOverride = 1700000000L
        )
        assertEquals(
            LicenseStatusState.EXPIRED,
            LicenseContract.evaluateLicenseTokenStatus(timed.activationCode, pubB64, false, devId, 1800000000L)
        )

        // 3. Revocation certificate
        val revCode = LicenseContract.issueRevocationCertificate(
            licenseId = perp.licenseId,
            deviceId = devId,
            reason = "إلغاء اختباري",
            privateKeyPkcs8Base64 = privB64,
            publicKeySpkiBase64 = pubB64
        )
        assertTrue(revCode.startsWith("SLREV1."))
    }
}
