package com.smartledger.licenseissuer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartledger.licenseissuer.crypto.IssuedLicensePackage
import com.smartledger.licenseissuer.crypto.LicenseContract
import com.smartledger.licenseissuer.crypto.LicenseStatusState

data class LicenseHistoryRecord(
    val licenseId: String,
    val productCode: String,
    val deviceId: String,
    val customerName: String,
    val customerCode: String,
    val licenseType: String,
    val issuedAtUnixSeconds: Long,
    val startsAtUnixSeconds: Long,
    val expiresAtUnixSeconds: Long,
    val isRevoked: Boolean,
    val revokedAtUnixSeconds: Long,
    val revocationCode: String,
    val notes: String,
    val activationCode: String,
    val evaluatedState: LicenseStatusState
) {
    val statusLabelAr: String get() = evaluatedState.labelAr
    val licenseTypeLabelAr: String
        get() = if (licenseType.equals("TIMED", ignoreCase = true) || expiresAtUnixSeconds > 0L)
            "محدد بمدة"
        else
            "دائم"
}

/**
 * Persistent SQLite database for storing issued licenses and their real lifecycle states
 * (Active, Expired, Revoked, Invalid) on the administrator's Android device.
 */
class LicenseHistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "smartledger_license_history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS IssuedLicenses(" +
                "LicenseId TEXT PRIMARY KEY," +
                "ProductCode TEXT NOT NULL DEFAULT 'SLPRO'," +
                "DeviceId TEXT NOT NULL," +
                "CustomerName TEXT NOT NULL," +
                "CustomerCode TEXT NOT NULL DEFAULT ''," +
                "LicenseType TEXT NOT NULL," +
                "IssuedAtUnix INTEGER NOT NULL," +
                "StartsAtUnix INTEGER NOT NULL DEFAULT 0," +
                "ExpiresAtUnix INTEGER NOT NULL DEFAULT 0," +
                "IsRevoked INTEGER NOT NULL DEFAULT 0," +
                "RevokedAtUnix INTEGER NOT NULL DEFAULT 0," +
                "RevocationCode TEXT NOT NULL DEFAULT ''," +
                "Status TEXT NOT NULL," +
                "Notes TEXT NOT NULL DEFAULT ''," +
                "ActivationCode TEXT NOT NULL);"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS IX_IssuedLicenses_Date ON IssuedLicenses(IssuedAtUnix DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS IX_IssuedLicenses_Device ON IssuedLicenses(DeviceId);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val alterColumns = listOf(
                "ALTER TABLE IssuedLicenses ADD COLUMN ProductCode TEXT NOT NULL DEFAULT 'SLPRO';",
                "ALTER TABLE IssuedLicenses ADD COLUMN CustomerCode TEXT NOT NULL DEFAULT '';",
                "ALTER TABLE IssuedLicenses ADD COLUMN StartsAtUnix INTEGER NOT NULL DEFAULT 0;",
                "ALTER TABLE IssuedLicenses ADD COLUMN ExpiresAtUnix INTEGER NOT NULL DEFAULT 0;",
                "ALTER TABLE IssuedLicenses ADD COLUMN IsRevoked INTEGER NOT NULL DEFAULT 0;",
                "ALTER TABLE IssuedLicenses ADD COLUMN RevokedAtUnix INTEGER NOT NULL DEFAULT 0;",
                "ALTER TABLE IssuedLicenses ADD COLUMN RevocationCode TEXT NOT NULL DEFAULT '';"
            )
            for (sql in alterColumns) {
                try { db.execSQL(sql) } catch (_: Exception) { }
            }
        }
    }

    fun recordIssuedLicense(pkg: IssuedLicensePackage) {
        val values = ContentValues().apply {
            put("LicenseId", pkg.licenseId)
            put("ProductCode", pkg.productCode)
            put("DeviceId", pkg.deviceId)
            put("CustomerName", pkg.customerName)
            put("CustomerCode", pkg.customerCode)
            put("LicenseType", pkg.licenseType)
            put("IssuedAtUnix", pkg.issuedAtUnixSeconds)
            put("StartsAtUnix", pkg.startsAtUnixSeconds)
            put("ExpiresAtUnix", pkg.expiresAtUnixSeconds)
            put("IsRevoked", 0)
            put("RevokedAtUnix", 0L)
            put("RevocationCode", "")
            put("Status", "ACTIVE")
            put("Notes", pkg.notes)
            put("ActivationCode", pkg.activationCode)
        }
        writableDatabase.insertWithOnConflict("IssuedLicenses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markLicenseRevoked(licenseId: String, revocationCode: String, revokedAtUnix: Long = System.currentTimeMillis() / 1000L): Boolean {
        val values = ContentValues().apply {
            put("IsRevoked", 1)
            put("RevokedAtUnix", revokedAtUnix)
            put("RevocationCode", revocationCode)
            put("Status", "REVOKED")
        }
        val rows = writableDatabase.update(
            "IssuedLicenses",
            values,
            "LicenseId = ?",
            arrayOf(licenseId.trim().uppercase())
        )
        return rows > 0
    }

    fun listIssuedLicenses(
        publicKeySpkiBase64: String,
        searchQuery: String = "",
        statusFilter: LicenseStatusState? = null
    ): List<LicenseHistoryRecord> {
        val q = searchQuery.trim()
        val list = mutableListOf<LicenseHistoryRecord>()
        val selection = if (q.isEmpty()) null else "CustomerName LIKE ? OR CustomerCode LIKE ? OR DeviceId LIKE ? OR LicenseId LIKE ? OR Notes LIKE ?"
        val args = if (q.isEmpty()) null else arrayOf("%$q%", "%$q%", "%$q%", "%$q%", "%$q%")
        val nowUnix = System.currentTimeMillis() / 1000L

        readableDatabase.query(
            "IssuedLicenses",
            arrayOf(
                "LicenseId", "ProductCode", "DeviceId", "CustomerName", "CustomerCode",
                "LicenseType", "IssuedAtUnix", "StartsAtUnix", "ExpiresAtUnix",
                "IsRevoked", "RevokedAtUnix", "RevocationCode", "Notes", "ActivationCode"
            ),
            selection,
            args,
            null,
            null,
            "IssuedAtUnix DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val lid = cursor.getString(0)
                val prod = cursor.getString(1) ?: "SLPRO"
                val did = cursor.getString(2)
                val cn = cursor.getString(3)
                val cid = cursor.getString(4) ?: ""
                val ltype = cursor.getString(5) ?: "PERPETUAL"
                val iat = cursor.getLong(6)
                val nbf = cursor.getLong(7)
                val exp = cursor.getLong(8)
                val isRev = cursor.getInt(9) != 0
                val revAt = cursor.getLong(10)
                val revCode = cursor.getString(11) ?: ""
                val notes = cursor.getString(12) ?: ""
                val code = cursor.getString(13)

                val evalState = LicenseContract.evaluateLicenseTokenStatus(
                    activationCode = code,
                    publicKeySpkiBase64 = publicKeySpkiBase64,
                    isRevokedInDb = isRev,
                    expectedDeviceId = did,
                    nowUnixSeconds = nowUnix
                )

                if (statusFilter == null || statusFilter == evalState) {
                    list.add(
                        LicenseHistoryRecord(
                            licenseId = lid,
                            productCode = prod,
                            deviceId = did,
                            customerName = cn,
                            customerCode = cid,
                            licenseType = ltype,
                            issuedAtUnixSeconds = iat,
                            startsAtUnixSeconds = nbf,
                            expiresAtUnixSeconds = exp,
                            isRevoked = isRev,
                            revokedAtUnixSeconds = revAt,
                            revocationCode = revCode,
                            notes = notes,
                            activationCode = code,
                            evaluatedState = evalState
                        )
                    )
                }
            }
        }
        return list
    }
}
