package com.smartledger.licenseissuer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartledger.licenseissuer.crypto.IssuedLicensePackage

data class LicenseHistoryRecord(
    val licenseId: String,
    val deviceId: String,
    val customerName: String,
    val licenseType: String,
    val issuedAtUnixSeconds: Long,
    val status: String,
    val notes: String,
    val activationCode: String
)

/**
 * Local SQLite database storing non-sensitive issued license history on the owner's Android device.
 */
class LicenseHistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "smartledger_license_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS IssuedLicenses(" +
                "LicenseId TEXT PRIMARY KEY," +
                "DeviceId TEXT NOT NULL," +
                "CustomerName TEXT NOT NULL," +
                "LicenseType TEXT NOT NULL," +
                "IssuedAtUnix INTEGER NOT NULL," +
                "Status TEXT NOT NULL," +
                "Notes TEXT NOT NULL DEFAULT ''," +
                "ActivationCode TEXT NOT NULL);"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS IX_IssuedLicenses_Date ON IssuedLicenses(IssuedAtUnix DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS IX_IssuedLicenses_Device ON IssuedLicenses(DeviceId);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    fun recordIssuedLicense(pkg: IssuedLicensePackage) {
        val values = ContentValues().apply {
            put("LicenseId", pkg.licenseId)
            put("DeviceId", pkg.deviceId)
            put("CustomerName", pkg.customerName)
            put("LicenseType", "دائم (PERPETUAL)")
            put("IssuedAtUnix", pkg.issuedAtUnixSeconds)
            put("Status", "مُصدَر ونشط")
            put("Notes", pkg.notes)
            put("ActivationCode", pkg.activationCode)
        }
        writableDatabase.insertWithOnConflict("IssuedLicenses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listIssuedLicenses(searchQuery: String = ""): List<LicenseHistoryRecord> {
        val q = searchQuery.trim()
        val list = mutableListOf<LicenseHistoryRecord>()
        val selection = if (q.isEmpty()) null else "CustomerName LIKE ? OR DeviceId LIKE ? OR LicenseId LIKE ? OR Notes LIKE ?"
        val args = if (q.isEmpty()) null else arrayOf("%$q%", "%$q%", "%$q%", "%$q%")

        readableDatabase.query(
            "IssuedLicenses",
            arrayOf("LicenseId", "DeviceId", "CustomerName", "LicenseType", "IssuedAtUnix", "Status", "Notes", "ActivationCode"),
            selection,
            args,
            null,
            null,
            "IssuedAtUnix DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    LicenseHistoryRecord(
                        licenseId = cursor.getString(0),
                        deviceId = cursor.getString(1),
                        customerName = cursor.getString(2),
                        licenseType = cursor.getString(3),
                        issuedAtUnixSeconds = cursor.getLong(4),
                        status = cursor.getString(5),
                        notes = cursor.getString(6),
                        activationCode = cursor.getString(7)
                    )
                )
            }
        }
        return list
    }
}
