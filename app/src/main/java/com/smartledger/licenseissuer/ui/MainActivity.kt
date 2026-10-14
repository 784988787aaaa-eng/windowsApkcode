package com.smartledger.licenseissuer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartledger.licenseissuer.crypto.*
import com.smartledger.licenseissuer.data.LicenseHistoryDatabase
import com.smartledger.licenseissuer.data.LicenseHistoryRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        } else ""

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SmartLedgerIssuerTheme {
                    LicenseIssuerMainScreen(initialSharedText = sharedText)
                }
            }
        }
    }
}

private val BgDark = Color(0xFF070B19)
private val CardDark = Color(0xFF0E182E)
private val CardBorder = Color(0xFF233B61)
private val AccentCyan = Color(0xFF00B4D8)
private val AccentEmerald = Color(0xFF10B981)
private val WarningAmber = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val DangerRed = Color(0xFFEF4444)

@Composable
fun SmartLedgerIssuerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = AccentCyan,
        secondary = AccentEmerald,
        background = BgDark,
        surface = CardDark,
        onPrimary = Color(0xFF041322),
        onSecondary = Color(0xFF032219),
        onBackground = TextPrimary,
        onSurface = TextPrimary
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseIssuerMainScreen(initialSharedText: String = "") {
    val context = LocalContext.current
    val vault = remember { KeyStoreVault(context) }
    val historyDb = remember { LicenseHistoryDatabase(context) }

    var selectedTab by remember { mutableStateOf(0) }
    var isKeyConfigured by remember { mutableStateOf(vault.isSigningKeyConfigured()) }

    var selectedProduct by remember { mutableStateOf(LicenseContract.PRODUCT_CODE_SLPRO) }
    var deviceIdInput by remember { mutableStateOf("") }
    var customerNameInput by remember { mutableStateOf("") }
    var customerCodeInput by remember { mutableStateOf("") }
    var selectedLicenseType by remember { mutableStateOf(LicenseContract.LICENSE_TYPE_PERPETUAL) }
    var durationDaysText by remember { mutableStateOf("365") }
    var notesInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    var statusBannerText by remember { mutableStateOf("") }
    var isStatusError by remember { mutableStateOf(false) }

    var lastIssuedPackage by remember { mutableStateOf<IssuedLicensePackage?>(null) }
    var showConfirmIssueDialog by remember { mutableStateOf(false) }
    var recordToRevoke by remember { mutableStateOf<LicenseHistoryRecord?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<LicenseStatusState?>(null) }
    var historyItems by remember {
        mutableStateOf(historyDb.listIssuedLicenses(vault.getPublicKeySpkiBase64()))
    }

    fun refreshHistory() {
        historyItems = historyDb.listIssuedLicenses(vault.getPublicKeySpkiBase64(), searchQuery, statusFilter)
    }

    LaunchedEffect(initialSharedText) {
        if (initialSharedText.isNotBlank()) {
            val parsed = LicenseContract.parseIncomingRequest(initialSharedText)
            if (parsed.isValid) {
                selectedProduct = parsed.productCode
                deviceIdInput = parsed.deviceId
                if (parsed.customerName.isNotBlank()) customerNameInput = parsed.customerName
                statusBannerText = "تم استيراد معرّف جهاز Windows (${parsed.deviceId}) وبيانات العميل بنجاح."
                isStatusError = false
            }
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            Surface(color = Color(0xFF091226), shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(0xFF102B50), RoundedCornerShape(11.dp))
                                .border(1.dp, Color(0xFF2A72B7), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AccentCyan)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "مركز إصدار تراخيص الدفتر الذكي",
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "SmartLedger Pro — إصدار والتحقق من التراخيص الرقمية (ECDSA P-256)",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }
                    }
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = AccentCyan,
                            strokeWidth = 2.2.dp
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF091226)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    label = { Text("إصدار ترخيص") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        refreshHistory()
                        selectedTab = 1
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("سجل التراخيص (${historyItems.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("المفاتيح والإعدادات") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!isKeyConfigured && selectedTab != 2) {
                Surface(
                    color = Color(0xFF2B2112),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, WarningAmber, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "مفتاح التوقيع الرقمي غير مهيأ بعد على هذا الجهاز",
                                color = Color(0xFFFEF3C7),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                "استورد مفتاح الإصدار الخاص (PKCS#8) أو ولّد زوج مفاتيح جديد لبدء إصدار التراخيص.",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { selectedTab = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                        ) {
                            Text("إعداد المفتاح", color = Color(0xFF1F1605), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (statusBannerText.isNotBlank()) {
                Surface(
                    color = if (isStatusError) Color(0xFF35151E) else Color(0xFF0B2E25),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isStatusError) DangerRed else AccentEmerald,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusBannerText,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { statusBannerText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق التنبيه", tint = TextMuted)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            when (selectedTab) {
                0 -> IssueLicenseTab(
                    selectedProduct = selectedProduct,
                    deviceIdInput = deviceIdInput,
                    onDeviceIdChange = { deviceIdInput = it },
                    customerNameInput = customerNameInput,
                    onCustomerNameChange = { customerNameInput = it },
                    customerCodeInput = customerCodeInput,
                    onCustomerCodeChange = { customerCodeInput = it },
                    selectedLicenseType = selectedLicenseType,
                    onLicenseTypeChange = { selectedLicenseType = it },
                    durationDaysText = durationDaysText,
                    onDurationDaysChange = { durationDaysText = it },
                    notesInput = notesInput,
                    onNotesChange = { notesInput = it },
                    lastIssuedPackage = lastIssuedPackage,
                    onPasteWhatsAppRequest = {
                        val clip = readClipboard(context)
                        val parsed = LicenseContract.parseIncomingRequest(clip)
                        if (parsed.isValid) {
                            selectedProduct = parsed.productCode
                            deviceIdInput = parsed.deviceId
                            if (parsed.customerName.isNotBlank()) customerNameInput = parsed.customerName
                            statusBannerText = "تم استخراج معرّف جهاز Windows (${parsed.deviceId}) من الحافظة."
                            isStatusError = false
                        } else {
                            statusBannerText = parsed.errorMessageAr
                            isStatusError = true
                        }
                    },
                    onClearForm = {
                        deviceIdInput = ""
                        customerNameInput = ""
                        customerCodeInput = ""
                        notesInput = ""
                        statusBannerText = ""
                    },
                    onRequestIssue = {
                        val days = durationDaysText.trim().toIntOrNull() ?: 0
                        if (!isKeyConfigured) {
                            statusBannerText = "يرجى إعداد مفتاح الإصدار الخاص أولاً من تبويب «المفاتيح والإعدادات»."
                            isStatusError = true
                            selectedTab = 2
                        } else if (!LicenseContract.isValidDeviceCode(deviceIdInput)) {
                            statusBannerText = "معرّف جهاز Windows غير صالح. يجب أن يكون بالصيغة SLP-XXXX-XXXX-XXXX-XXXX."
                            isStatusError = true
                        } else if (customerNameInput.trim().length < 2) {
                            statusBannerText = "يرجى إدخال اسم العميل أو الجهة (حرفان على الأقل)."
                            isStatusError = true
                        } else if (selectedLicenseType == LicenseContract.LICENSE_TYPE_TIMED && days <= 0) {
                            statusBannerText = "أدخل مدة صلاحية صحيحة بالأيام للترخيص المحدد بمدة."
                            isStatusError = true
                        } else {
                            showConfirmIssueDialog = true
                        }
                    },
                    onCopyCode = { code ->
                        copyToClipboard(context, "SmartLedger Activation Code", code)
                        statusBannerText = "تم نسخ مفتاح الترخيص إلى الحافظة بنجاح."
                        isStatusError = false
                    },
                    onShareWhatsApp = { pkg ->
                        shareLicenseViaWhatsApp(context, pkg, vault.getContactConfig())
                    }
                )

                1 -> LicenseHistoryTab(
                    searchQuery = searchQuery,
                    onSearchChange = {
                        searchQuery = it
                        refreshHistory()
                    },
                    selectedFilter = statusFilter,
                    onFilterChange = {
                        statusFilter = it
                        refreshHistory()
                    },
                    records = historyItems,
                    onCopyCode = { rec ->
                        copyToClipboard(context, "SmartLedger Activation Code", rec.activationCode)
                        statusBannerText = "تم نسخ مفتاح الترخيص الخاص بـ «${rec.customerName}»."
                        isStatusError = false
                    },
                    onShareRecord = { rec ->
                        shareRawCodeViaWhatsApp(context, rec.customerName, rec.deviceId, rec.licenseTypeLabelAr, rec.activationCode, vault.getContactConfig())
                    },
                    onRequestRevoke = { rec ->
                        recordToRevoke = rec
                    },
                    onCopyRevocationCode = { rec ->
                        copyToClipboard(context, "SmartLedger Revocation Code", rec.revocationCode)
                        statusBannerText = "تم نسخ شهادة إلغاء الترخيص (SLREV1) إلى الحافظة."
                        isStatusError = false
                    }
                )

                2 -> SecurityAndContactSettingsTab(
                    vault = vault,
                    isKeyConfigured = isKeyConfigured,
                    onKeyStatusChanged = { isKeyConfigured = vault.isSigningKeyConfigured() },
                    onFeedback = { msg, err ->
                        statusBannerText = msg
                        isStatusError = err
                    }
                )
            }
        }
    }

    if (showConfirmIssueDialog) {
        val normalizedDid = LicenseContract.formatCanonicalDeviceCode(deviceIdInput)
        val days = durationDaysText.trim().toIntOrNull() ?: 365
        val nowSec = System.currentTimeMillis() / 1000L
        val expSec = if (selectedLicenseType == LicenseContract.LICENSE_TYPE_TIMED) nowSec + (days * 86400L) else 0L
        val df = remember { SimpleDateFormat("yyyy/MM/dd", Locale.US) }

        AlertDialog(
            onDismissRequest = { showConfirmIssueDialog = false },
            containerColor = CardDark,
            title = { Text("تأكيد إصدار مفتاح ترخيص", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("هل تريد توقيع وإصدار الترخيص بالبيانات التالية؟", color = TextMuted, fontSize = 13.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• المنتج: SmartLedger Pro ($selectedProduct)", color = TextPrimary)
                    Text("• العميل / الجهة: ${customerNameInput.trim()}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("• معرّف جهاز Windows: $normalizedDid", color = AccentCyan, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (expSec > 0L)
                            "• الصلاحية: محدد بمدة ($days يوماً — من ${df.format(Date(nowSec * 1000L))} حتى ${df.format(Date(expSec * 1000L))})"
                        else
                            "• الصلاحية: ترخيص دائم (لا ينتهي بمرور الوقت)",
                        color = AccentEmerald
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmIssueDialog = false
                        isProcessing = true
                        try {
                            val pkg = LicenseContract.issueLicense(
                                productCode = selectedProduct,
                                licenseType = selectedLicenseType,
                                rawDeviceId = normalizedDid,
                                customerName = customerNameInput.trim(),
                                customerCode = customerCodeInput.trim(),
                                startsAtUnixSeconds = nowSec,
                                expiresAtUnixSeconds = expSec,
                                notes = notesInput.trim(),
                                privateKeyPkcs8Base64 = vault.loadDecryptedPrivateKeyPkcs8Base64(),
                                publicKeySpkiBase64 = vault.getPublicKeySpkiBase64(),
                                issuedAtUnixOverride = nowSec
                            )
                            historyDb.recordIssuedLicense(pkg)
                            lastIssuedPackage = pkg
                            deviceIdInput = normalizedDid
                            refreshHistory()
                            statusBannerText = "تم إصدار وتوقيع مفتاح الترخيص بنجاح وحفظه في السجل الدائم."
                            isStatusError = false
                        } catch (ex: Exception) {
                            statusBannerText = "فشل إصدار الترخيص: ${ex.message}"
                            isStatusError = true
                        } finally {
                            isProcessing = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                ) {
                    Text("تأكيد وإصدار المفتاح", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmIssueDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (recordToRevoke != null) {
        val target = recordToRevoke!!
        AlertDialog(
            onDismissRequest = { recordToRevoke = null },
            containerColor = CardDark,
            title = { Text("تأكيد إلغاء الترخيص", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = {
                Text(
                    "هل تريد تغيير حالة الترخيص رقم (${target.licenseId}) للعميل «${target.customerName}» إلى «ملغى» في قاعدة البيانات وإصدار شهادة إلغاء موقّعة (SLREV1)؟",
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        recordToRevoke = null
                        try {
                            val revCode = LicenseContract.issueRevocationCertificate(
                                licenseId = target.licenseId,
                                deviceId = target.deviceId,
                                reason = "إلغاء إداري من سجل التراخيص",
                                privateKeyPkcs8Base64 = vault.loadDecryptedPrivateKeyPkcs8Base64(),
                                publicKeySpkiBase64 = vault.getPublicKeySpkiBase64()
                            )
                            historyDb.markLicenseRevoked(target.licenseId, revCode)
                            copyToClipboard(context, "SmartLedger Revocation Code", revCode)
                            refreshHistory()
                            statusBannerText = "تم إلغاء الترخيص في قاعدة البيانات ونسخ شهادة الإلغاء (SLREV1) إلى الحافظة."
                            isStatusError = false
                        } catch (ex: Exception) {
                            statusBannerText = "تعذر إلغاء الترخيص: ${ex.message}"
                            isStatusError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("تأكيد الإلغاء", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { recordToRevoke = null }) {
                    Text("تراجع")
                }
            }
        )
    }
}

@Composable
fun IssueLicenseTab(
    selectedProduct: String,
    deviceIdInput: String,
    onDeviceIdChange: (String) -> Unit,
    customerNameInput: String,
    onCustomerNameChange: (String) -> Unit,
    customerCodeInput: String,
    onCustomerCodeChange: (String) -> Unit,
    selectedLicenseType: String,
    onLicenseTypeChange: (String) -> Unit,
    durationDaysText: String,
    onDurationDaysChange: (String) -> Unit,
    notesInput: String,
    onNotesChange: (String) -> Unit,
    lastIssuedPackage: IssuedLicensePackage?,
    onPasteWhatsAppRequest: () -> Unit,
    onClearForm: () -> Unit,
    onRequestIssue: () -> Unit,
    onCopyCode: (String) -> Unit,
    onShareWhatsApp: (IssuedLicensePackage) -> Unit
) {
    val isDeviceValid = LicenseContract.isValidDeviceCode(deviceIdInput)
    val df = remember { SimpleDateFormat("yyyy/MM/dd", Locale.US) }
    val nowMs = System.currentTimeMillis()
    val daysInt = durationDaysText.trim().toIntOrNull() ?: 0
    val expMs = nowMs + (daysInt * 86400_000L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("إصدار مفتاح ترخيص جديد", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        Text(
                            "المنتج المستهدف: الدفتر الذكي — SmartLedger Pro ($selectedProduct)",
                            fontSize = 12.sp,
                            color = AccentEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = onPasteWhatsAppRequest,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("لصق معرّف / طلب", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = deviceIdInput,
                    onValueChange = onDeviceIdChange,
                    label = { Text("معرّف جهاز Windows (Device ID) *") },
                    placeholder = { Text("SLP-XXXX-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (deviceIdInput.isNotBlank()) {
                            Text(
                                text = if (isDeviceValid) "✓ معرّف جهاز Windows صالح ومطابق للصيغة المعتمدة (${LicenseContract.formatCanonicalDeviceCode(deviceIdInput)})"
                                else "✗ المعرّف غير صالح؛ يجب أن يتكون من 16 رمزاً ست عشرياً (SLP-XXXX-XXXX-XXXX-XXXX)",
                                color = if (isDeviceValid) AccentEmerald else DangerRed
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = customerNameInput,
                        onValueChange = onCustomerNameChange,
                        label = { Text("اسم العميل أو الجهة *") },
                        placeholder = { Text("مؤسسة الأمل التجارية") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = customerCodeInput,
                        onValueChange = onCustomerCodeChange,
                        label = { Text("رقم العميل (اختياري)") },
                        placeholder = { Text("C-101") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("نوع الترخيص ومدته:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val isPerp = selectedLicenseType == LicenseContract.LICENSE_TYPE_PERPETUAL
                    Button(
                        onClick = { onLicenseTypeChange(LicenseContract.LICENSE_TYPE_PERPETUAL) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPerp) AccentEmerald else Color(0xFF162640)
                        )
                    ) {
                        Text("ترخيص دائم (PERPETUAL)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onLicenseTypeChange(LicenseContract.LICENSE_TYPE_TIMED) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isPerp) AccentCyan else Color(0xFF162640)
                        )
                    ) {
                        Text("محدد بمدة (TIMED)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (selectedLicenseType == LicenseContract.LICENSE_TYPE_TIMED) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for ((label, dVal) in listOf("سنة (365)" to "365", "6 أشهر (180)" to "180", "شهر (30)" to "30")) {
                            OutlinedButton(
                                onClick = { onDurationDaysChange(dVal) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(label, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = durationDaysText,
                        onValueChange = onDurationDaysChange,
                        label = { Text("مدة الصلاحية بالأيام *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (daysInt > 0) {
                                Text(
                                    "تاريخ البداية: ${df.format(Date(nowMs))}  •  تاريخ الانتهاء: ${df.format(Date(expMs))}",
                                    color = AccentCyan
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = notesInput,
                    onValueChange = onNotesChange,
                    label = { Text("ملاحظات الإصدار (اختياري)") },
                    placeholder = { Text("مثال: الفرع الرئيسي") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRequestIssue,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إصدار مفتاح الترخيص", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    }
                    if (deviceIdInput.isNotBlank() || customerNameInput.isNotBlank() || notesInput.isNotBlank()) {
                        OutlinedButton(
                            onClick = onClearForm,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تفريغ")
                        }
                    }
                }
            }
        }

        if (lastIssuedPackage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = Color(0xFF09221E),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.2.dp, AccentEmerald, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "تم إصدار مفتاح الترخيص وتوقيعه رقمياً",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD1FAE5)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "العميل: ${lastIssuedPackage.customerName}  •  الجهاز: ${lastIssuedPackage.deviceId}  •  رقم الترخيص: ${lastIssuedPackage.licenseId}",
                        fontSize = 12.sp,
                        color = Color(0xFFA7F3D0)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF051311), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF1E4E44), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = lastIssuedPackage.activationCode,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            color = Color(0xFF38BDF8),
                            textAlign = TextAlign.Left
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onCopyCode(lastIssuedPackage.activationCode) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("نسخ المفتاح", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onShareWhatsApp(lastIssuedPackage) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاركة عبر واتساب")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LicenseHistoryTab(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFilter: LicenseStatusState?,
    onFilterChange: (LicenseStatusState?) -> Unit,
    records: List<LicenseHistoryRecord>,
    onCopyCode: (LicenseHistoryRecord) -> Unit,
    onShareRecord: (LicenseHistoryRecord) -> Unit,
    onRequestRevoke: (LicenseHistoryRecord) -> Unit,
    onCopyRevocationCode: (LicenseHistoryRecord) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.US) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text("بحث في السجل (باسم العميل أو رقم العميل أو معرّف الجهاز)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            val filters = listOf(
                null to "الكل",
                LicenseStatusState.ACTIVE to "صالح",
                LicenseStatusState.EXPIRED to "منتهي",
                LicenseStatusState.REVOKED to "ملغى"
            )
            for ((st, label) in filters) {
                val active = selectedFilter == st
                Button(
                    onClick = { onFilterChange(st) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) AccentCyan else Color(0xFF162640)
                    )
                ) {
                    Text(label, fontSize = 11.5.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardDark, RoundedCornerShape(14.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("لا توجد تراخيص مطابقة في السجل المحلي", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("يُحفظ السجل محلياً في قاعدة بيانات SQLite داخل جهاز المسؤول مع التحقق الديناميكي من حالة كل ترخيص.", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(records, key = { it.licenseId }) { rec ->
                    val badgeColor = when (rec.evaluatedState) {
                        LicenseStatusState.ACTIVE -> AccentEmerald
                        LicenseStatusState.EXPIRED -> WarningAmber
                        LicenseStatusState.REVOKED, LicenseStatusState.INVALID, LicenseStatusState.DEVICE_MISMATCH -> DangerRed
                        LicenseStatusState.NOT_YET_VALID -> AccentCyan
                    }
                    Surface(
                        color = CardDark,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (rec.customerCode.isBlank()) rec.customerName else "${rec.customerName} (${rec.customerCode})",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = rec.statusLabelAr,
                                    fontSize = 12.sp,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "معرّف الجهاز: ${rec.deviceId}  •  الترخيص: ${rec.licenseId}",
                                fontSize = 12.sp,
                                color = AccentCyan,
                                fontFamily = FontFamily.Monospace
                            )
                            val validityText = if (rec.expiresAtUnixSeconds > 0L)
                                "محدد بمدة: ${dateFormat.format(Date(rec.startsAtUnixSeconds * 1000L))} ⬅ ${dateFormat.format(Date(rec.expiresAtUnixSeconds * 1000L))}"
                            else
                                "دائم • تاريخ الإصدار: ${dateFormat.format(Date(rec.issuedAtUnixSeconds * 1000L))}"
                            Text(validityText, fontSize = 11.5.sp, color = TextMuted)
                            if (rec.notes.isNotBlank()) {
                                Text("ملاحظات: ${rec.notes}", fontSize = 11.5.sp, color = TextMuted)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onCopyCode(rec) }, modifier = Modifier.weight(1f)) {
                                    Text("نسخ المفتاح", fontSize = 11.5.sp)
                                }
                                OutlinedButton(onClick = { onShareRecord(rec) }, modifier = Modifier.weight(1f)) {
                                    Text("مشاركة", fontSize = 11.5.sp)
                                }
                                if (!rec.isRevoked) {
                                    OutlinedButton(
                                        onClick = { onRequestRevoke(rec) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("إلغاء الترخيص", fontSize = 11.5.sp, color = DangerRed)
                                    }
                                } else if (rec.revocationCode.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = { onCopyRevocationCode(rec) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("نسخ شهادة الإلغاء", fontSize = 11.sp, color = WarningAmber)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityAndContactSettingsTab(
    vault: KeyStoreVault,
    isKeyConfigured: Boolean,
    onKeyStatusChanged: () -> Unit,
    onFeedback: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var pubKeySpki by remember { mutableStateOf(vault.getPublicKeySpkiBase64()) }
    var fingerprint by remember { mutableStateOf(vault.getPublicKeyFingerprint()) }
    var privateKeyInput by remember { mutableStateOf("") }
    var customPublicKeyInput by remember { mutableStateOf(vault.getPublicKeySpkiBase64()) }
    var showGenerateConfirm by remember { mutableStateOf(false) }

    val initialContact = remember { vault.getContactConfig() }
    var issuerTitle by remember { mutableStateOf(initialContact.issuerTitle) }
    var supportPhone by remember { mutableStateOf(initialContact.supportPhone) }
    var footerNote by remember { mutableStateOf(initialContact.footerNote) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("1. خزنة مفتاح التوقيع الرقمي (ECDSA P-256)", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    Text(
                        text = if (isKeyConfigured) "✓ مهيأ وآمن" else "⚠ يتطلب الإعداد",
                        color = if (isKeyConfigured) AccentEmerald else WarningAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "يعمل التطبيق محلياً دون خادم؛ يُحفظ مفتاح الإصدار الخاص مشفراً داخل شريحة Android Keystore (AES-256-GCM) ولا يُضمّن في الكود المصدري العام.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("بصمة المفتاح العام النشط (SHA-256): $fingerprint", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AccentEmerald, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = privateKeyInput,
                    onValueChange = { privateKeyInput = it },
                    label = { Text("استيراد المفتاح الخاص للمالك (PKCS#8 Base64 أو PEM)") },
                    placeholder = { Text("ألصق المفتاح الخاص هنا لحفظه مشفراً داخل الجهاز...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPublicKeyInput,
                    onValueChange = { customPublicKeyInput = it },
                    label = { Text("المفتاح العام المقابل في تطبيق Windows (SPKI Base64)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            try {
                                vault.importPrivateKey(privateKeyInput, customPublicKeyInput)
                                privateKeyInput = ""
                                pubKeySpki = vault.getPublicKeySpkiBase64()
                                fingerprint = vault.getPublicKeyFingerprint()
                                onKeyStatusChanged()
                                onFeedback("تم التحقق من تطابق المفتاح الخاص وتشفيره داخل Android Keystore بنجاح.", false)
                            } catch (ex: Exception) {
                                onFeedback("تعذر استيراد المفتاح: ${ex.message}", true)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                    ) {
                        Text("حفظ وتشفير المفتاح", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val clip = readClipboard(context)
                            if (clip.isNotBlank()) {
                                privateKeyInput = clip.trim()
                                onFeedback("تم لصق المفتاح من الحافظة؛ اضغط «حفظ وتشفير المفتاح» لاعتماده.", false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("لصق من الحافظة", fontSize = 12.5.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, "SmartLedger Public Key SPKI", pubKeySpki)
                            onFeedback("تم نسخ المفتاح العام (SPKI Base64) إلى الحافظة.", false)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("نسخ المفتاح العام (Public Key)", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showGenerateConfirm = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("توليد زوج مفاتيح جديد", fontSize = 12.sp)
                    }
                }
            }
        }

        Surface(
            color = CardDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("2. إعدادات التواصل ورسالة تسليم الترخيص", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "تُدرج هذه البيانات تلقائياً في رسالة تسليم مفتاح الترخيص المرسلة للعميل.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = issuerTitle,
                    onValueChange = { issuerTitle = it },
                    label = { Text("اسم جهة الإصدار / المالك") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = supportPhone,
                    onValueChange = { supportPhone = it },
                    label = { Text("رقم هاتف / واتساب الدعم الفني (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = footerNote,
                    onValueChange = { footerNote = it },
                    label = { Text("عبارة التذييل في رسالة التفعيل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        vault.saveContactConfig(IssuerContactConfig(issuerTitle, supportPhone, footerNote))
                        onFeedback("تم حفظ إعدادات التواصل وقالب رسالة التسليم بنجاح.", false)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("حفظ إعدادات التواصل", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showGenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showGenerateConfirm = false },
            containerColor = CardDark,
            title = { Text("تأكيد توليد زوج مفاتيح إنتاجي جديد", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "سيؤدي توليد زوج مفاتيح جديد إلى استبدال المفتاح الحالي داخل الجهاز، وسيتوجب عليك نسخ المفتاح العام الجديد (Public Key SPKI) ووضعه في تطبيق Windows قبل بناء نسخة العملاء. هل تريد المتابعة؟",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGenerateConfirm = false
                        val newPub = vault.generateNewProductionKeyPair()
                        pubKeySpki = newPub
                        customPublicKeyInput = newPub
                        fingerprint = vault.getPublicKeyFingerprint()
                        onKeyStatusChanged()
                        onFeedback("تم توليد زوج مفاتيح إنتاجي جديد وتشفيره بنجاح. انسخ المفتاح العام الجديد لتحديث تطبيق Windows.", false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                ) {
                    Text("توليد واستبدال", color = Color(0xFF1F1605), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGenerateConfirm = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val item = cm.primaryClip?.getItemAt(0)
    return item?.coerceToText(context)?.toString() ?: ""
}

private fun shareLicenseViaWhatsApp(context: Context, pkg: IssuedLicensePackage, contact: IssuerContactConfig) {
    val typeLabel = if (pkg.licenseType == LicenseContract.LICENSE_TYPE_TIMED) "محدد بمدة" else "دائم"
    shareRawCodeViaWhatsApp(context, pkg.customerName, pkg.deviceId, typeLabel, pkg.activationCode, contact)
}

private fun shareRawCodeViaWhatsApp(
    context: Context,
    customerName: String,
    deviceId: String,
    licenseTypeLabel: String,
    activationCode: String,
    contact: IssuerContactConfig
) {
    val message = buildString {
        appendLine("مرحباً بك في ${contact.issuerTitle}،")
        appendLine("تم إصدار مفتاح ترخيصك بنجاح:")
        appendLine("• المنشأة: $customerName")
        appendLine("• معرّف الجهاز: $deviceId")
        appendLine("• نوع الترخيص: $licenseTypeLabel")
        if (contact.supportPhone.isNotBlank()) {
            appendLine("• الدعم الفني: ${contact.supportPhone}")
        }
        appendLine()
        appendLine("انسخ مفتاح التفعيل التالي وألصقه في نافذة التراخيص داخل برنامج الكمبيوتر:")
        appendLine(activationCode)
        if (contact.footerNote.isNotBlank()) {
            appendLine()
            append(contact.footerNote)
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, "إرسال مفتاح التفعيل إلى العميل"))
}
