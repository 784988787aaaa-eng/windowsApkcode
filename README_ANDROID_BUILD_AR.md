# مشروع تطبيق Android لإصدار تراخيص الدفتر الذكي (SmartLedger License Issuer)

هذا المجلد يحتوي على مشروع **Android Studio (Kotlin + Jetpack Compose Material 3)** الكامل والمستقل لإصدار أكواد التفعيل الدائمة (`SLPRO1`) المتوافقة مع تطبيق سطح المكتب **SmartLedger Pro**.

## 1. المتطلبات الأساسية للتجميع
- **Android Studio** (إصدار Hedgehog أو أحدث) أو **JDK 17** مع **Android SDK Platform 34**.

## 2. خطوات التجميع السريع واستخراج ملف `APK`

### الطريقة الأولى: عبر برنامج Android Studio (الموصى بها)
1. فك ضغط الأرشيف `SmartLedger_Android_LicenseIssuer_ReadyToBuild.zip`.
2. افتح برنامج **Android Studio** واختر **Open** ثم حدد المجلد `android-license-issuer`.
3. انتظر ثواني حتى يكمل **Gradle Sync** تنزيل المكتبات تلقائياً.
4. من القائمة العلوية اختر:
   **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**
5. بعد انتهاء التجميع ستظهر رسالة بنجاح البناء؛ اضغط **locate** للحصول على ملف التثبيت:
   `app/build/outputs/apk/debug/app-debug.apk`

### الطريقة الثانية: عبر سطر الأوامر (Terminal / PowerShell)
من داخل مجلد `android-license-issuer`:
```bash
# لبناء نسخة الاختبار الجاهزة للتثبيت المباشر على الهاتف
gradle assembleDebug

# أو لبناء نسخة الإصدار النهائي (Release)
gradle assembleRelease
```

## 3. هيكل ملفات المشروع الأساسية
- `settings.gradle.kts` & `build.gradle.kts`: إعدادات مشروع Gradle الرئيسي ومستودعات Google وMavenCentral.
- `app/build.gradle.kts`: إعدادات التطبيق (`com.smartledger.licenseissuer`، `minSdk = 26`، `targetSdk = 34`، `CompileSdk = 34`، `JVM Target = 17`).
- `app/src/main/AndroidManifest.xml`: تعريف التطبيق ودعم الاتجاه العربي `android:supportsRtl="true"` واستقبال مشاركات النصوص من واتساب (`ACTION_SEND`).
- `app/src/main/java/com/smartledger/licenseissuer/crypto/LicenseContract.kt`: محرك إصدار التراخيص الدائمة والتوقيع الرقمي `ECDSA P-256 (SHA256withECDSA)` بصيغة `IEEE P1363` وفك رموز طلبات واتساب (`SLREQ1`).
- `app/src/main/java/com/smartledger/licenseissuer/crypto/KeyStoreVault.kt`: خزنة المفاتيح المشفرة عبر `AndroidKeyStore (AES-256-GCM)` المتضمنة مفتاح الإصدار المتوافق مع المفتاح العام في نسخة Windows.
- `app/src/main/java/com/smartledger/licenseissuer/data/LicenseHistoryDatabase.kt`: قاعدة بيانات `SQLite` المحلية لحفظ سجل التراخيص المصدرة والبحث فيها.
- `app/src/main/java/com/smartledger/licenseissuer/ui/MainActivity.kt`: الواجهة العربية الكاملة (إصدار ترخيص، سجل التراخيص، وإعدادات المفاتيح والأمان).
