package com.offline.wfo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_EXPORT_CSV = 201
        private const val REQ_IMPORT_CSV = 202
        private const val REQ_PICK_PHOTO = 203
        private const val REQ_UNLOCK_EDIT = 204
        private const val REQ_UNLOCK_SETUP = 205
        private const val REQ_UNLOCK_APP = 206
        private const val REQ_PICK_BACKUP_FOLDER = 207
        private const val REQ_IMPORT_HOLIDAYS = 208
    }

    private enum class ActiveTab { HOME, ANALYTICS, SETTINGS }
    private enum class LogFilter { ALL, OFFICE, PLANNED, LEAVE_HOLIDAY }

    private lateinit var store: AttendanceStore
    private var selectedMonth: YearMonth = YearMonth.now()
    private var activeTapMode: DayType = DayType.OFFICE
    private var activeTab: ActiveTab = ActiveTab.HOME
    private var activeLogFilter: LogFilter = LogFilter.ALL
    private var simLeaves: Int = 0
    private var simHolidays: Int = 0
    private var lastUndoBatch: Map<LocalDate, DayType> = emptyMap()

    private var isEditUnlocked: Boolean = false
    private var isAppAuthenticated: Boolean = false
    private var isAwaitingSystemIntent: Boolean = false
    private var autoPromptedBgLocation: Boolean = false
    private var isDark: Boolean = false

    // Root Containers
    private lateinit var appLockOverlay: LinearLayout
    private lateinit var setupScroll: ScrollView
    private lateinit var mainScaffold: LinearLayout
    private lateinit var homeTabScroll: ScrollView
    private lateinit var analyticsTabScroll: ScrollView
    private lateinit var settingsTabScroll: ScrollView

    // Bottom Navigation Tabs
    private lateinit var navHomeBtn: TextView
    private lateinit var navAnalyticsBtn: TextView
    private lateinit var navSettingsBtn: TextView

    // Setup Wizard Views
    private lateinit var setupAvatarView: ImageView
    private lateinit var setupNameInput: EditText
    private lateinit var setupFullLockSwitch: Switch
    private lateinit var setupPermStatusView: TextView
    private lateinit var setupBgLocStatusView: TextView
    private lateinit var setupGpsStatusView: TextView
    private lateinit var setupBatteryStatusView: TextView
    private lateinit var setupLiveWifiView: TextView
    private lateinit var setupSsidInput: EditText
    private lateinit var setupLocationSummaryView: TextView
    private lateinit var setupTargetPctView: TextView
    private lateinit var setupRoundUpSwitch: Switch
    private lateinit var setupExclHolidaySwitch: Switch
    private lateinit var setupExclLeaveSwitch: Switch
    private lateinit var setupConnectAlertSwitch: Switch
    private lateinit var setupDisconnectAlertSwitch: Switch
    private lateinit var setupPlanReminderSwitch: Switch
    private lateinit var setupMorningNudgeSwitch: Switch
    private lateinit var setupTargetBufferAlertSwitch: Switch
    private lateinit var setupQuarterAlertSwitch: Switch
    private lateinit var setupBackupFolderStatusView: TextView

    // Persistent Header Views (Top of Main Scaffold)
    private lateinit var dashAvatarView: ImageView
    private lateinit var dashGreetingView: TextView
    private lateinit var dashServiceStatusView: TextView
    private lateinit var lockToggleBtn: TextView
    private lateinit var checkNowBtn: TextView
    private lateinit var todayLiveStripView: TextView
    private lateinit var undoBannerRow: LinearLayout
    private lateinit var undoBannerText: TextView

    // Tab 1: Home Views
    private lateinit var todayJumpBtn: TextView
    private lateinit var monthTitleView: TextView
    private lateinit var paceBannerView: TextView
    private lateinit var weeklyPacePillView: TextView
    private lateinit var circularRingView: CircularProgressRingView
    private lateinit var heroSubView: TextView
    private lateinit var heroProjectedView: TextView
    private lateinit var kpiWorkingView: TextView
    private lateinit var kpiRemainingView: TextView
    private lateinit var kpiBufferView: TextView
    private lateinit var autoPlanBtn: TextView
    private lateinit var modeOfficeBtn: TextView
    private lateinit var modePlannedBtn: TextView
    private lateinit var modeLeaveBtn: TextView
    private lateinit var modeHolidayBtn: TextView
    private lateinit var calendarContainer: LinearLayout
    private lateinit var filterAllBtn: TextView
    private lateinit var filterOfficeBtn: TextView
    private lateinit var filterPlannedBtn: TextView
    private lateinit var filterLeaveHolBtn: TextView
    private lateinit var auditLogView: TextView

    // Tab 2: Analytics Views
    private lateinit var trendChartView: TrendBarChartView
    private lateinit var qtdTitleView: TextView
    private lateinit var qtdDetailView: TextView
    private lateinit var qtdLeaveHolView: TextView
    private lateinit var rhythmView: TextView
    private lateinit var simLeaveCountView: TextView
    private lateinit var simHolidayCountView: TextView
    private lateinit var simResultView: TextView

    // Tab 3: Settings & Data Views
    private lateinit var modeWifiOnlyBtn: TextView
    private lateinit var modeLocOnlyBtn: TextView
    private lateinit var modeBothBtn: TextView
    private lateinit var trackingModeHintView: TextView
    private lateinit var wifiSectionCard: LinearLayout
    private lateinit var locationSectionCard: LinearLayout
    private lateinit var currentWifiView: TextView
    private lateinit var wifiChipsContainer: LinearLayout
    private lateinit var ssidInput: EditText
    private lateinit var selectedOfficeView: TextView
    private lateinit var officeDistanceView: TextView
    private lateinit var radius250Btn: TextView
    private lateinit var radius500Btn: TextView
    private lateinit var radius1000Btn: TextView
    private lateinit var radius1500Btn: TextView
    private lateinit var notifConnectSwitch: Switch
    private lateinit var notifDisconnectSwitch: Switch
    private lateinit var notifPlannedSwitch: Switch
    private lateinit var notifMorningSwitch: Switch
    private lateinit var notifTargetBufferSwitch: Switch
    private lateinit var notifQuarterSwitch: Switch
    private lateinit var autoBackupStatusView: TextView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAllViews()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AttendanceStore(this)
        isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val frameRoot = FrameLayout(this).apply {
            setBackgroundColor(cBg())
        }

        ViewCompat.setOnApplyWindowInsetsListener(frameRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupScroll = buildSetupWizardView()
        mainScaffold = buildMainScaffold()
        appLockOverlay = buildAppLockOverlay()

        frameRoot.addView(setupScroll)
        frameRoot.addView(mainScaffold)
        frameRoot.addView(appLockOverlay)
        setContentView(frameRoot)

        if (!store.isSetupComplete()) {
            isEditUnlocked = true
            isAppAuthenticated = true
            showSetupMode()
            requestForegroundAndNotifPermissions()
        } else {
            startMonitorServiceIfPermitted()
            if (store.isFullAppLockEnabled() && !isAppAuthenticated) {
                showAppLockScreen()
                promptDeviceCredential(REQ_UNLOCK_APP, "Unlock WFO Tracker")
            } else {
                showDashboardMode()
            }
        }
        refreshAllViews()
    }

    override fun onResume() {
        super.onResume()
        startMonitorServiceIfPermitted()
        refreshAllViews()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(WifiMonitorService.ACTION_DATA_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(updateReceiver) }
    }

    override fun onStop() {
        super.onStop()
        if (store.isSetupComplete() && !isAwaitingSystemIntent) {
            isEditUnlocked = false
            if (store.isFullAppLockEnabled()) {
                isAppAuthenticated = false
                showAppLockScreen()
            }
            refreshAllViews()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        isAwaitingSystemIntent = false

        when (requestCode) {
            REQ_UNLOCK_EDIT -> {
                if (resultCode == Activity.RESULT_OK) {
                    isEditUnlocked = true
                    showDashboardMode()
                    Toast.makeText(this, "Edit mode unlocked", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_UNLOCK_SETUP -> {
                if (resultCode == Activity.RESULT_OK) {
                    isEditUnlocked = true
                    showSetupMode()
                }
            }
            REQ_UNLOCK_APP -> {
                if (resultCode == Activity.RESULT_OK) {
                    isAppAuthenticated = true
                    showDashboardMode()
                }
            }
            REQ_PICK_PHOTO -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    saveCompressedAvatar(data.data!!)
                    refreshAllViews()
                }
            }
            REQ_PICK_BACKUP_FOLDER -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val treeUri = data.data!!
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching {
                        contentResolver.takePersistableUriPermission(treeUri, flags)
                    }
                    store.setAutoBackupTreeUri(treeUri.toString())

                    val hadLocalDataBefore = store.getAllMarkedDates().isNotEmpty()
                    val importedCount = store.restoreExistingBackupFromFolder(treeUri)
                    if (importedCount > 0) {
                        activeLogFilter = LogFilter.ALL
                        syncBackgroundAndWidget()
                        refreshAllViews()
                        Toast.makeText(
                            this,
                            "Found existing backup — restored $importedCount date(s)!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val synced = store.triggerSilentAutoBackup()
                        refreshAllViews()
                        val msg = when {
                            synced && hadLocalDataBefore -> "Auto-backup enabled & synced!"
                            synced -> "Auto-backup folder ready!"
                            else -> "Folder saved (will sync on next update)"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQ_IMPORT_HOLIDAYS -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCatching {
                        val text = contentResolver.openInputStream(data.data!!)
                            ?.bufferedReader()?.use { it.readText() } ?: ""
                        val modified = store.bulkImportHolidays(text)
                        if (modified.isNotEmpty()) {
                            showUndoBanner("Imported ${modified.size} holiday(s)", modified)
                        } else {
                            Toast.makeText(this, "No new holiday dates found in file", Toast.LENGTH_SHORT).show()
                        }
                        syncBackgroundAndWidget()
                        refreshAllViews()
                    }.onFailure {
                        Toast.makeText(this, "Could not read holiday file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQ_EXPORT_CSV -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCatching {
                        contentResolver.openOutputStream(data.data!!)?.use { out ->
                            out.write(store.exportAllCsv().toByteArray(Charsets.UTF_8))
                        }
                        Toast.makeText(this, "CSV backup saved!", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, "Failed to save CSV", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQ_IMPORT_CSV -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCatching {
                        val count = store.importFromUri(data.data!!, autoBackupAfter = true)
                        activeLogFilter = LogFilter.ALL
                        syncBackgroundAndWidget()
                        refreshAllViews()
                        Toast.makeText(this, "Restored $count date(s)!", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, "Could not read CSV file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startMonitorServiceIfPermitted()
        refreshAllViews()

        if (requestCode == 10 &&
            !autoPromptedBgLocation &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasLocationPermission() &&
            !hasBackgroundLocationPermission()
        ) {
            autoPromptedBgLocation = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11
            )
        }
    }

    // ==================== VIEW SWITCHERS, TABS & UNDO ====================

    private fun showAppLockScreen() {
        appLockOverlay.visibility = View.VISIBLE
        setupScroll.visibility = View.GONE
        mainScaffold.visibility = View.GONE
    }

    private fun showSetupMode() {
        appLockOverlay.visibility = View.GONE
        setupNameInput.setText(store.getUserName())
        setupSsidInput.setText(store.getTargetSsids().joinToString(", "))
        setupFullLockSwitch.isChecked = store.isFullAppLockEnabled()
        setupRoundUpSwitch.isChecked = store.isRoundUpEnabled()
        setupExclHolidaySwitch.isChecked = store.isExcludeHolidaysEnabled()
        setupExclLeaveSwitch.isChecked = store.isExcludeLeavesEnabled()
        setupConnectAlertSwitch.isChecked = store.isConnectAlertEnabled()
        setupDisconnectAlertSwitch.isChecked = store.isDisconnectAlertEnabled()
        setupPlanReminderSwitch.isChecked = store.isPlannedRemindersEnabled()
        setupMorningNudgeSwitch.isChecked = store.isMorningNudgeEnabled()
        setupTargetBufferAlertSwitch.isChecked = store.isTargetBufferAlertEnabled()
        setupQuarterAlertSwitch.isChecked = store.isQuarterPaceAlertEnabled()
        setupScroll.visibility = View.VISIBLE
        mainScaffold.visibility = View.GONE
        refreshAllViews()
    }

    private fun showDashboardMode() {
        appLockOverlay.visibility = View.GONE
        setupScroll.visibility = View.GONE
        mainScaffold.visibility = View.VISIBLE
        switchTab(activeTab)
        refreshAllViews()
    }

    private fun switchTab(tab: ActiveTab) {
        activeTab = tab
        homeTabScroll.visibility = if (tab == ActiveTab.HOME) View.VISIBLE else View.GONE
        analyticsTabScroll.visibility = if (tab == ActiveTab.ANALYTICS) View.VISIBLE else View.GONE
        settingsTabScroll.visibility = if (tab == ActiveTab.SETTINGS) View.VISIBLE else View.GONE

        styleNavTab(navHomeBtn, tab == ActiveTab.HOME)
        styleNavTab(navAnalyticsBtn, tab == ActiveTab.ANALYTICS)
        styleNavTab(navSettingsBtn, tab == ActiveTab.SETTINGS)
        refreshAllViews()
    }

    private fun showUndoBanner(message: String, batch: Map<LocalDate, DayType>) {
        lastUndoBatch = batch
        undoBannerText.text = message
        undoBannerRow.visibility = View.VISIBLE
    }

    private fun ensureEditUnlocked(): Boolean {
        if (isEditUnlocked) return true
        Toast.makeText(this, "Locked — Tap 🔒 at top to unlock edits", Toast.LENGTH_SHORT).show()
        return false
    }

    @Suppress("DEPRECATION")
    private fun launchSystemIntentForResult(intent: Intent, requestCode: Int) {
        isAwaitingSystemIntent = true
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            isAwaitingSystemIntent = false
            Toast.makeText(this, "Unable to open system dialog", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun promptDeviceCredential(requestCode: Int, title: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            isEditUnlocked = true
            isAppAuthenticated = true
            if (requestCode == REQ_UNLOCK_SETUP) showSetupMode() else showDashboardMode()
            Toast.makeText(this, "Unlocked (no device PIN set)", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(
            title,
            "Verify your identity to modify WFO attendance records"
        )
        if (intent != null) {
            launchSystemIntentForResult(intent, requestCode)
        } else {
            isEditUnlocked = true
            isAppAuthenticated = true
            if (requestCode == REQ_UNLOCK_SETUP) showSetupMode() else showDashboardMode()
        }
    }

    // ==================== 1. FULL-APP LOCK OVERLAY ====================

    private fun buildAppLockOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg())
            setPadding(48, 48, 48, 48)
            visibility = View.GONE

            val lockIcon = TextView(this@MainActivity).apply {
                text = "🔒"
                textSize = 48f
                gravity = Gravity.CENTER
            }
            val title = TextView(this@MainActivity).apply {
                text = "WFO Tracker Locked"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cTextPrimary())
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 8)
            }
            val sub = TextView(this@MainActivity).apply {
                text = "Authenticate with Fingerprint, Face, or PIN to view your attendance."
                textSize = 14f
                setTextColor(cTextSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 32)
            }
            val unlockBtn = makePillButton("Unlock Now", "#4F46E5", "#FFFFFF") {
                promptDeviceCredential(REQ_UNLOCK_APP, "Unlock WFO Tracker")
            }

            addView(lockIcon)
            addView(title)
            addView(sub)
            addView(unlockBtn)
        }
    }

    // ==================== 2. FIRST-TIME SETUP WIZARD UI ====================

    private fun buildSetupWizardView(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 64)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "WFO Tracker Setup"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
        }
        val subtitle = TextView(this).apply {
            text = "Personalize your profile, enable background permissions, and configure Wi-Fi or 500m–1500m location tracking."
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 20)
        }
        root.addView(title)
        root.addView(subtitle)

        val profileCard = makeCardContainer()
        profileCard.addView(makeSectionTitle("1. Personal Profile & Privacy"))
        addSpacer(profileCard, 14)

        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        setupAvatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(128, 128)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val photoBtnCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickPhotoBtn = makeSmallChipButton("📷 Choose Profile Photo") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            launchSystemIntentForResult(intent, REQ_PICK_PHOTO)
        }
        val removePhotoBtn = TextView(this).apply {
            text = "Remove Photo"
            textSize = 12f
            setTextColor(Color.parseColor("#EF4444"))
            setPadding(8, 10, 8, 4)
            setOnClickListener {
                store.deleteProfilePhoto()
                refreshAllViews()
                Toast.makeText(this@MainActivity, "Photo removed", Toast.LENGTH_SHORT).show()
            }
        }
        photoBtnCol.addView(pickPhotoBtn)
        photoBtnCol.addView(removePhotoBtn)
        avatarRow.addView(setupAvatarView)
        avatarRow.addView(photoBtnCol)
        profileCard.addView(avatarRow)
        addSpacer(profileCard, 14)

        setupNameInput = EditText(this).apply {
            hint = "Your Name (e.g., Arjun)"
            setText(store.getUserName())
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(24, 20, 24, 20)
            background = roundedBox(cInputBg(), cBorder(), 16f, 2)
        }
        profileCard.addView(setupNameInput)
        addSpacer(profileCard, 12)

        setupFullLockSwitch = Switch(this).apply {
            text = "Require Fingerprint/PIN to open app (Full Lock)"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isFullAppLockEnabled()
            setPadding(0, 8, 0, 4)
        }
        profileCard.addView(setupFullLockSwitch)
        root.addView(profileCard)
        addSpacer(root, 20)

        val permCard = makeCardContainer()
        permCard.addView(makeSectionTitle("2. Required System Permissions"))
        addSpacer(permCard, 12)

        setupPermStatusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, 6) }
        val btnGrantPerm = makePillButton("Allow Location & Notifications", "#4F46E5", "#FFFFFF") {
            requestForegroundAndNotifPermissions()
        }
        permCard.addView(setupPermStatusView)
        permCard.addView(btnGrantPerm)
        addSpacer(permCard, 16)

        setupBgLocStatusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, 6) }
        val btnBgLoc = makePillButton("Set Location: \"Allow all the time\"", "#4F46E5", "#FFFFFF") {
            requestBackgroundLocation()
        }
        permCard.addView(setupBgLocStatusView)
        permCard.addView(btnBgLoc)
        addSpacer(permCard, 16)

        setupGpsStatusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, 6) }
        val btnGps = makePillButton("Turn ON Phone Location (GPS)", "#0284C7", "#FFFFFF") {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        permCard.addView(setupGpsStatusView)
        permCard.addView(btnGps)
        addSpacer(permCard, 16)

        setupBatteryStatusView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, 6) }
        val btnBattery = makePillButton("Allow Unrestricted Battery", "#0284C7", "#FFFFFF") {
            requestUnrestrictedBattery()
        }
        permCard.addView(setupBatteryStatusView)
        permCard.addView(btnBattery)
        root.addView(permCard)
        addSpacer(root, 20)

        val trackingCard = makeCardContainer()
        trackingCard.addView(makeSectionTitle("3. Office Wi-Fi & Geofence Location"))
        setupLiveWifiView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 10)
        }
        val btnAddLive = makePillButton("➕ Auto-Detect Connected Wi-Fi", "#E0E7FF", "#3730A3") {
            val live = getConnectedWifiSsid()
            if (live.isEmpty()) {
                Toast.makeText(
                    this,
                    "Connect to office Wi-Fi & turn ON Location above first",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val existing = setupSsidInput.text.toString().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (existing.none { it.equals(live, ignoreCase = true) }) {
                    existing.add(live)
                    setupSsidInput.setText(existing.joinToString(", "))
                }
                Toast.makeText(this, "Added '$live'", Toast.LENGTH_SHORT).show()
            }
        }
        setupSsidInput = EditText(this).apply {
            hint = "Enter Office Wi-Fi SSID(s), comma-separated"
            setText(store.getTargetSsids().joinToString(", "))
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(24, 20, 24, 20)
            background = roundedBox(cInputBg(), cBorder(), 16f, 2)
        }

        setupLocationSummaryView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextPrimary())
            setPadding(0, 14, 0, 8)
        }
        val btnSelectLocSetup = makePillButton("📍 Select Location (Maps / GPS / 500m List)", "#0284C7", "#FFFFFF") {
            showSelectLocationDialog()
        }

        trackingCard.addView(setupLiveWifiView)
        trackingCard.addView(btnAddLive)
        addSpacer(trackingCard, 10)
        trackingCard.addView(setupSsidInput)
        trackingCard.addView(setupLocationSummaryView)
        trackingCard.addView(btnSelectLocSetup)
        root.addView(trackingCard)
        addSpacer(root, 20)

        val policyCard = makeCardContainer()
        policyCard.addView(makeSectionTitle("4. Policy Rules & Notification Options"))

        val pctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 10)
        }
        setupTargetPctView = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minusBtn = makeSmallChipButton("− 5%") {
            store.setTargetPercent(store.getTargetPercent() - 5)
            refreshAllViews()
        }
        val plusBtn = makeSmallChipButton("+ 5%") {
            store.setTargetPercent(store.getTargetPercent() + 5)
            refreshAllViews()
        }
        pctRow.addView(setupTargetPctView)
        pctRow.addView(minusBtn)
        addHorizontalGap(pctRow, 10)
        pctRow.addView(plusBtn)
        policyCard.addView(pctRow)

        setupRoundUpSwitch = Switch(this).apply {
            text = "Round Up Fractional Days (e.g., 8.4 → 9 days)"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isRoundUpEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupExclHolidaySwitch = Switch(this).apply {
            text = "Exclude Marked Holidays from Target"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isExcludeHolidaysEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupExclLeaveSwitch = Switch(this).apply {
            text = "Exclude Marked Leaves from Target"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isExcludeLeavesEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupConnectAlertSwitch = Switch(this).apply {
            text = "Office Connected Alert (Wi-Fi / Geofence Entry)"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isConnectAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupDisconnectAlertSwitch = Switch(this).apply {
            text = "Office Disconnected Alert (Wi-Fi / Geofence Exit)"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isDisconnectAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupPlanReminderSwitch = Switch(this).apply {
            text = "Planned Office Day Alerts (Eve, Morning, Missed)"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isPlannedRemindersEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupMorningNudgeSwitch = Switch(this).apply {
            text = "Morning Pace Nudge on WFH Days"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isMorningNudgeEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupTargetBufferAlertSwitch = Switch(this).apply {
            text = "Monthly Target Met & Zero Buffer Alerts"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isTargetBufferAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupQuarterAlertSwitch = Switch(this).apply {
            text = "Quarter-End Pace Alert"
            textSize = 14f
            setTextColor(cTextPrimary())
            isChecked = store.isQuarterPaceAlertEnabled()
            setPadding(0, 8, 0, 4)
        }

        policyCard.addView(setupRoundUpSwitch)
        policyCard.addView(setupExclHolidaySwitch)
        policyCard.addView(setupExclLeaveSwitch)
        policyCard.addView(setupConnectAlertSwitch)
        policyCard.addView(setupDisconnectAlertSwitch)
        policyCard.addView(setupPlanReminderSwitch)
        policyCard.addView(setupMorningNudgeSwitch)
        policyCard.addView(setupTargetBufferAlertSwitch)
        policyCard.addView(setupQuarterAlertSwitch)
        root.addView(policyCard)
        addSpacer(root, 20)

        val backupSetupCard = makeCardContainer()
        backupSetupCard.addView(makeSectionTitle("5. Auto-Backup Folder (Local or Drive)"))
        setupBackupFolderStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        val pickFolderBtn = makePillButton("📁 Choose Folder (Auto-Imports Existing Backup)", "#E0E7FF", "#3730A3") {
            launchBackupFolderPicker()
        }
        backupSetupCard.addView(setupBackupFolderStatusView)
        backupSetupCard.addView(pickFolderBtn)
        root.addView(backupSetupCard)
        addSpacer(root, 24)

        val saveFinishBtn = makePillButton("✅ Save Settings & Start Auto-Tracker", "#10B981", "#FFFFFF") {
            store.setUserName(setupNameInput.text.toString())
            store.setFullAppLockEnabled(setupFullLockSwitch.isChecked)
            store.setTargetSsids(setupSsidInput.text.toString())
            store.setRoundUpEnabled(setupRoundUpSwitch.isChecked)
            store.setExcludeHolidaysEnabled(setupExclHolidaySwitch.isChecked)
            store.setExcludeLeavesEnabled(setupExclLeaveSwitch.isChecked)
            store.setConnectAlertEnabled(setupConnectAlertSwitch.isChecked)
            store.setDisconnectAlertEnabled(setupDisconnectAlertSwitch.isChecked)
            store.setPlannedRemindersEnabled(setupPlanReminderSwitch.isChecked)
            store.setMorningNudgeEnabled(setupMorningNudgeSwitch.isChecked)
            store.setTargetBufferAlertEnabled(setupTargetBufferAlertSwitch.isChecked)
            store.setQuarterPaceAlertEnabled(setupQuarterAlertSwitch.isChecked)
            store.setSetupComplete(true)

            isEditUnlocked = false
            isAppAuthenticated = true
            syncBackgroundAndWidget()
            showDashboardMode()
            Toast.makeText(this, "Settings saved & locked!", Toast.LENGTH_SHORT).show()
        }
        root.addView(saveFinishBtn)

        return scroll
    }

    // ==================== 3. MAIN 3-TAB SCAFFOLD ====================

    private fun buildMainScaffold(): LinearLayout {
        val scaffold = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 12)
        }

        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        dashAvatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(96, 96)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val greetingCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dashGreetingView = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
        }
        dashServiceStatusView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 2, 0, 0)
        }
        greetingCol.addView(dashGreetingView)
        greetingCol.addView(dashServiceStatusView)

        checkNowBtn = makeSmallChipButton("🔄") {
            triggerManualCheckNow()
        }
        lockToggleBtn = makeSmallChipButton("🔒") {
            if (isEditUnlocked) {
                isEditUnlocked = false
                refreshAllViews()
                Toast.makeText(this, "Dashboard locked", Toast.LENGTH_SHORT).show()
            } else {
                promptDeviceCredential(REQ_UNLOCK_EDIT, "Unlock Edit Mode")
            }
        }

        profileHeader.addView(dashAvatarView)
        profileHeader.addView(greetingCol)
        profileHeader.addView(checkNowBtn)
        addHorizontalGap(profileHeader, 6)
        profileHeader.addView(lockToggleBtn)
        topBar.addView(profileHeader)

        todayLiveStripView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
        }
        topBar.addView(todayLiveStripView)

        undoBannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBox("#1E293B", "#4F46E5", 14f, 2)
            setPadding(20, 12, 16, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 0) }
        }
        undoBannerText = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undoActionBtn = TextView(this).apply {
            text = "UNDO"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(16, 6, 12, 6)
            setOnClickListener {
                if (lastUndoBatch.isNotEmpty()) {
                    store.restoreBatchDates(lastUndoBatch)
                    lastUndoBatch = emptyMap()
                    undoBannerRow.visibility = View.GONE
                    syncBackgroundAndWidget()
                    refreshAllViews()
                    Toast.makeText(this@MainActivity, "Action undone", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val dismissUndoBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(8, 6, 8, 6)
            setOnClickListener {
                lastUndoBatch = emptyMap()
                undoBannerRow.visibility = View.GONE
            }
        }
        undoBannerRow.addView(undoBannerText)
        undoBannerRow.addView(undoActionBtn)
        undoBannerRow.addView(dismissUndoBtn)
        topBar.addView(undoBannerRow)

        scaffold.addView(topBar)

        val tabContentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        homeTabScroll = buildHomeTab()
        analyticsTabScroll = buildAnalyticsTab()
        settingsTabScroll = buildSettingsTab()

        tabContentHost.addView(homeTabScroll)
        tabContentHost.addView(analyticsTabScroll)
        tabContentHost.addView(settingsTabScroll)
        scaffold.addView(tabContentHost)

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(cCardHex(), cBorder(), 0f, 2)
            setPadding(16, 12, 16, 12)
        }
        navHomeBtn = makeNavTabButton("📅 Home") { switchTab(ActiveTab.HOME) }
        navAnalyticsBtn = makeNavTabButton("📊 Analytics") { switchTab(ActiveTab.ANALYTICS) }
        navSettingsBtn = makeNavTabButton("⚙️ Settings") { switchTab(ActiveTab.SETTINGS) }

        bottomNav.addView(navHomeBtn)
        addHorizontalGap(bottomNav, 8)
        bottomNav.addView(navAnalyticsBtn)
        addHorizontalGap(bottomNav, 8)
        bottomNav.addView(navSettingsBtn)
        scaffold.addView(bottomNav)

        return scaffold
    }

    // ==================== TAB 1: HOME (TODAY & CALENDAR) ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHomeTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 48)
        }
        scroll.addView(root)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        val prevBtn = makeSmallChipButton("◀") {
            selectedMonth = selectedMonth.minusMonths(1)
            refreshAllViews()
        }
        monthTitleView = TextView(this).apply {
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        todayJumpBtn = makeSmallChipButton("Today") {
            selectedMonth = YearMonth.now()
            refreshAllViews()
        }
        val nextBtn = makeSmallChipButton("▶") {
            selectedMonth = selectedMonth.plusMonths(1)
            refreshAllViews()
        }
        navRow.addView(prevBtn)
        navRow.addView(monthTitleView)
        navRow.addView(todayJumpBtn)
        addHorizontalGap(navRow, 6)
        navRow.addView(nextBtn)
        root.addView(navRow)

        paceBannerView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 18, 24, 18)
        }
        root.addView(paceBannerView)
        addSpacer(root, 10)

        weeklyPacePillView = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 14, 20, 14)
        }
        root.addView(weeklyPacePillView)
        addSpacer(root, 16)

        val heroCard = makeCardContainer()
        circularRingView = CircularProgressRingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(340, 340).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        heroSubView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 6)
        }
        heroProjectedView = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        heroCard.addView(circularRingView)
        heroCard.addView(heroSubView)
        heroCard.addView(heroProjectedView)
        addSpacer(heroCard, 12)

        val kpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(cInputBg(), cBorder(), 16f, 1)
            setPadding(12, 14, 12, 14)
        }
        kpiWorkingView = makeKpiColumnView()
        kpiRemainingView = makeKpiColumnView()
        kpiBufferView = makeKpiColumnView()
        kpiRow.addView(kpiWorkingView)
        kpiRow.addView(kpiRemainingView)
        kpiRow.addView(kpiBufferView)
        heroCard.addView(kpiRow)
        root.addView(heroCard)
        addSpacer(root, 16)

        val calCard = makeCardContainer()
        val calHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val calTitle = makeSectionTitle("Attendance & Leave Calendar").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        autoPlanBtn = makeSmallChipButton("✨ Auto-Plan") {
            if (!ensureEditUnlocked()) return@makeSmallChipButton
            autoPlanBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val modified = store.autoPlanRemainingDaysForMonth(selectedMonth)
            syncBackgroundAndWidget()
            refreshAllViews()
            if (modified.isNotEmpty()) {
                showUndoBanner("Auto-planned ${modified.size} office day(s)", modified)
            } else {
                Toast.makeText(this, "Monthly target already covered!", Toast.LENGTH_SHORT).show()
            }
        }
        calHeaderRow.addView(calTitle)
        calHeaderRow.addView(autoPlanBtn)
        calCard.addView(calHeaderRow)

        val calHint = TextView(this).apply {
            text = "Swipe left/right for months • Tap when locked to inspect • Unlock 🔒 to mark:"
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        calCard.addView(calHint)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 14)
        }
        modeOfficeBtn = makeModeChip("🏢 Office") { activeTapMode = DayType.OFFICE; refreshAllViews() }
        modePlannedBtn = makeModeChip("📅 Plan") { activeTapMode = DayType.PLANNED; refreshAllViews() }
        modeLeaveBtn = makeModeChip("🌴 Leave") { activeTapMode = DayType.LEAVE; refreshAllViews() }
        modeHolidayBtn = makeModeChip("🎉 Holiday") { activeTapMode = DayType.HOLIDAY; refreshAllViews() }

        modeRow.addView(modeOfficeBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modePlannedBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modeLeaveBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modeHolidayBtn)
        calCard.addView(modeRow)

        val swipeDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > 100 && abs(velocityX) > 100) {
                        selectedMonth = if (dx < 0) selectedMonth.plusMonths(1) else selectedMonth.minusMonths(1)
                        refreshAllViews()
                        return true
                    }
                    return false
                }
            }
        )

        calendarContainer = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                return swipeDetector.onTouchEvent(ev) || super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                return swipeDetector.onTouchEvent(event) || super.onTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }
        calCard.addView(calendarContainer)

        val legendView = TextView(this).apply {
            text = "🟢 Office   •   🔵 Dashed Planned   •   🟠 Leave   •   🟣 Holiday"
            textSize = 11f
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        calCard.addView(legendView)
        root.addView(calCard)
        addSpacer(root, 16)

        val logCard = makeCardContainer()
        logCard.addView(makeSectionTitle("Monthly Log & Timestamps"))
        addSpacer(logCard, 10)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        filterAllBtn = makeModeChip("All") { activeLogFilter = LogFilter.ALL; refreshAllViews() }
        filterOfficeBtn = makeModeChip("Office") { activeLogFilter = LogFilter.OFFICE; refreshAllViews() }
        filterPlannedBtn = makeModeChip("Planned") { activeLogFilter = LogFilter.PLANNED; refreshAllViews() }
        filterLeaveHolBtn = makeModeChip("Leave/Hol") { activeLogFilter = LogFilter.LEAVE_HOLIDAY; refreshAllViews() }

        filterRow.addView(filterAllBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterOfficeBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterPlannedBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterLeaveHolBtn)
        logCard.addView(filterRow)

        auditLogView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextPrimary())
            setLineSpacing(8f, 1.0f)
        }
        logCard.addView(auditLogView)
        root.addView(logCard)

        return scroll
    }

    // ==================== TAB 2: ANALYTICS & SIMULATOR ====================

    private fun buildAnalyticsTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 48)
        }
        scroll.addView(root)

        val trendCard = makeCardContainer()
        trendCard.addView(makeSectionTitle("6-Month Attendance Trend"))
        val trendSub = TextView(this).apply {
            text = "Monthly attendance % vs. dashed mandate target line"
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 12)
        }
        trendCard.addView(trendSub)

        trendChartView = TrendBarChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                340
            )
        }
        trendCard.addView(trendChartView)
        root.addView(trendCard)
        addSpacer(root, 16)

        val qtdCard = makeCardContainer()
        qtdTitleView = makeSectionTitle("Quarter-to-Date Rollup")
        qtdDetailView = TextView(this).apply {
            textSize = 14f
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 6)
        }
        qtdLeaveHolView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 0, 0, 10)
        }
        rhythmView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
        }
        qtdCard.addView(qtdTitleView)
        qtdCard.addView(qtdDetailView)
        qtdCard.addView(qtdLeaveHolView)
        qtdCard.addView(rhythmView)
        root.addView(qtdCard)
        addSpacer(root, 16)

        val simCard = makeCardContainer()
        simCard.addView(makeSectionTitle("What-If Leave & Holiday Simulator"))
        val simSub = TextView(this).apply {
            text = "Preview how upcoming leaves or holidays shift your required days:"
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 12)
        }
        simCard.addView(simSub)

        val simLeaveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 8)
        }
        simLeaveCountView = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val leaveMinus = makeSmallChipButton("−") {
            simLeaves = maxOf(0, simLeaves - 1)
            refreshAllViews()
        }
        val leavePlus = makeSmallChipButton("+") {
            simLeaves = minOf(15, simLeaves + 1)
            refreshAllViews()
        }
        simLeaveRow.addView(simLeaveCountView)
        simLeaveRow.addView(leaveMinus)
        addHorizontalGap(simLeaveRow, 8)
        simLeaveRow.addView(leavePlus)
        simCard.addView(simLeaveRow)

        val simHolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 12)
        }
        simHolidayCountView = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val holMinus = makeSmallChipButton("−") {
            simHolidays = maxOf(0, simHolidays - 1)
            refreshAllViews()
        }
        val holPlus = makeSmallChipButton("+") {
            simHolidays = minOf(15, simHolidays + 1)
            refreshAllViews()
        }
        simHolRow.addView(simHolidayCountView)
        simHolRow.addView(holMinus)
        addHorizontalGap(simHolRow, 8)
        simHolRow.addView(holPlus)
        simCard.addView(simHolRow)

        simResultView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 16, 20, 16)
        }
        simCard.addView(simResultView)
        root.addView(simCard)
        addSpacer(root, 18)

        val shareMonthBtn = makePillButton("📋 Share Monthly Summary Report", "#4F46E5", "#FFFFFF") {
            val report = store.buildMonthlySummaryReport(selectedMonth)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "WFO Attendance Summary - $selectedMonth")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share Monthly Summary"))
        }
        root.addView(shareMonthBtn)
        addSpacer(root, 12)

        val shareYearBtn = makePillButton("🏆 Share Annual Year-in-Review (${selectedMonth.year})", "#334155", "#FFFFFF") {
            val report = store.buildAnnualSummaryReport(selectedMonth.year)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "WFO Annual Summary - ${selectedMonth.year}")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share Annual Summary"))
        }
        root.addView(shareYearBtn)

        return scroll
    }

    // ==================== TAB 3: SETTINGS & DATA ====================

    private fun buildSettingsTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 48)
        }
        scroll.addView(root)

        val modeCard = makeCardContainer()
        modeCard.addView(makeSectionTitle("Tracking Mode"))
        val modeSelectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 8)
        }
        modeWifiOnlyBtn = makeModeChip("📶 Wi-Fi") {
            if (!ensureEditUnlocked()) return@makeModeChip
            store.setTrackingMode(TrackingMode.WIFI_ONLY)
            syncBackgroundAndWidget()
            refreshAllViews()
        }
        modeLocOnlyBtn = makeModeChip("📍 Location") {
            if (!ensureEditUnlocked()) return@makeModeChip
            store.setTrackingMode(TrackingMode.LOCATION_ONLY)
            syncBackgroundAndWidget()
            refreshAllViews()
        }
        modeBothBtn = makeModeChip("📶+📍 Both") {
            if (!ensureEditUnlocked()) return@makeModeChip
            store.setTrackingMode(TrackingMode.BOTH)
            syncBackgroundAndWidget()
            refreshAllViews()
        }
        modeSelectorRow.addView(modeWifiOnlyBtn)
        addHorizontalGap(modeSelectorRow, 6)
        modeSelectorRow.addView(modeLocOnlyBtn)
        addHorizontalGap(modeSelectorRow, 6)
        modeSelectorRow.addView(modeBothBtn)
        modeCard.addView(modeSelectorRow)

        trackingModeHintView = TextView(this).apply {
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 0)
        }
        modeCard.addView(trackingModeHintView)
        root.addView(modeCard)
        addSpacer(root, 16)

        locationSectionCard = makeCardContainer()
        locationSectionCard.addView(makeSectionTitle("Office Geofence Location"))
        selectedOfficeView = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 4)
        }
        officeDistanceView = TextView(this).apply {
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 0, 0, 12)
        }
        locationSectionCard.addView(selectedOfficeView)
        locationSectionCard.addView(officeDistanceView)

        val selectLocBtn = makePillButton("🗺️ Select Location (Maps / GPS / 500m List)", "#0284C7", "#FFFFFF") {
            if (!ensureEditUnlocked()) return@makePillButton
            showSelectLocationDialog()
        }
        locationSectionCard.addView(selectLocBtn)

        val radiusLabel = TextView(this).apply {
            text = "Geofence Radius (up to 1500m):"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextSecondary())
            setPadding(0, 14, 0, 8)
        }
        locationSectionCard.addView(radiusLabel)

        val radiusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        radius250Btn = makeModeChip("250m") { applyRadiusSelection(250) }
        radius500Btn = makeModeChip("500m") { applyRadiusSelection(500) }
        radius1000Btn = makeModeChip("1000m") { applyRadiusSelection(1000) }
        radius1500Btn = makeModeChip("1500m") { applyRadiusSelection(1500) }
        radiusRow.addView(radius250Btn)
        addHorizontalGap(radiusRow, 6)
        radiusRow.addView(radius500Btn)
        addHorizontalGap(radiusRow, 6)
        radiusRow.addView(radius1000Btn)
        addHorizontalGap(radiusRow, 6)
        radiusRow.addView(radius1500Btn)
        locationSectionCard.addView(radiusRow)

        root.addView(locationSectionCard)
        addSpacer(root, 16)

        wifiSectionCard = makeCardContainer()
        wifiSectionCard.addView(makeSectionTitle("Office Wi-Fi Auto-Detection"))
        currentWifiView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        wifiSectionCard.addView(currentWifiView)

        wifiChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiSectionCard.addView(wifiChipsContainer)
        addSpacer(wifiSectionCard, 10)

        val addCurrentWifiBtn = makePillButton("➕ Add Connected Wi-Fi", "#E0E7FF", "#3730A3") {
            if (!ensureEditUnlocked()) return@makePillButton
            val liveSsid = getConnectedWifiSsid()
            if (liveSsid.isEmpty()) {
                Toast.makeText(
                    this,
                    "Connect to office Wi-Fi & ensure Location is ON",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val added = store.addTargetSsid(liveSsid)
                syncBackgroundAndWidget()
                refreshAllViews()
                val msg = if (added) "Added '$liveSsid'!" else "'$liveSsid' is already saved."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        wifiSectionCard.addView(addCurrentWifiBtn)
        addSpacer(wifiSectionCard, 10)

        val manualWifiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ssidInput = EditText(this).apply {
            hint = "Or type SSID manually..."
            textSize = 14f
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(20, 18, 20, 18)
            background = roundedBox(cInputBg(), cBorder(), 16f, 2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addManualSsidBtn = makeSmallChipButton("+ Add") {
            if (!ensureEditUnlocked()) return@makeSmallChipButton
            val typed = ssidInput.text.toString().trim()
            if (typed.isNotEmpty()) {
                typed.split(",").forEach { store.addTargetSsid(it) }
                ssidInput.setText("")
                syncBackgroundAndWidget()
                refreshAllViews()
                Toast.makeText(this, "Wi-Fi saved!", Toast.LENGTH_SHORT).show()
            }
        }
        manualWifiRow.addView(ssidInput)
        addHorizontalGap(manualWifiRow, 10)
        manualWifiRow.addView(addManualSsidBtn)
        wifiSectionCard.addView(manualWifiRow)
        root.addView(wifiSectionCard)
        addSpacer(root, 16)

        val notifCard = makeCardContainer()
        notifCard.addView(makeSectionTitle("Notification Preferences"))
        addSpacer(notifCard, 6)

        notifConnectSwitch = makeSettingsSwitch("Connect Alert (Day Captured)") { checked ->
            store.setConnectAlertEnabled(checked)
        }
        notifDisconnectSwitch = makeSettingsSwitch("Disconnect Alert (Left Wi-Fi / Zone)") { checked ->
            store.setDisconnectAlertEnabled(checked)
        }
        notifPlannedSwitch = makeSettingsSwitch("Planned Day Alerts (Eve, Morning, Missed)") { checked ->
            store.setPlannedRemindersEnabled(checked)
        }
        notifMorningSwitch = makeSettingsSwitch("Morning Pace Nudge on WFH Days") { checked ->
            store.setMorningNudgeEnabled(checked)
        }
        notifTargetBufferSwitch = makeSettingsSwitch("Monthly Target Met & Zero Buffer Alerts") { checked ->
            store.setTargetBufferAlertEnabled(checked)
        }
        notifQuarterSwitch = makeSettingsSwitch("Quarter-End Pace Alert") { checked ->
            store.setQuarterPaceAlertEnabled(checked)
        }

        notifCard.addView(notifConnectSwitch)
        notifCard.addView(notifDisconnectSwitch)
        notifCard.addView(notifPlannedSwitch)
        notifCard.addView(notifMorningSwitch)
        notifCard.addView(notifTargetBufferSwitch)
        notifCard.addView(notifQuarterSwitch)
        root.addView(notifCard)
        addSpacer(root, 16)

        val holidayCard = makeCardContainer()
        holidayCard.addView(makeSectionTitle("Bulk Holiday Importer"))
        val holHint = TextView(this).apply {
            text = "Import an .ics calendar file or CSV (YYYY-MM-DD,Holiday Name) to mark holidays across the year."
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        val importHolBtn = makePillButton("🎉 Import Holidays (.ics / CSV)", "#8B5CF6", "#FFFFFF") {
            if (!ensureEditUnlocked()) return@makePillButton
            launchHolidayImport()
        }
        holidayCard.addView(holHint)
        holidayCard.addView(importHolBtn)
        root.addView(holidayCard)
        addSpacer(root, 16)

        val backupCard = makeCardContainer()
        backupCard.addView(makeSectionTitle("Backup & Data Portability"))
        autoBackupStatusView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 12)
        }
        backupCard.addView(autoBackupStatusView)

        val autoFolderBtn = makePillButton("🔄 Configure Auto-Backup Folder (Reuses Existing File)", "#0284C7", "#FFFFFF") {
            if (!ensureEditUnlocked()) return@makePillButton
            launchBackupFolderPicker()
        }
        backupCard.addView(autoFolderBtn)
        addSpacer(backupCard, 10)

        val backupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val exportBtn = makePillButton("💾 Save CSV", "#334155", "#FFFFFF").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        exportBtn.setOnClickListener { launchCsvExport() }

        val importBtn = makePillButton("📂 Restore CSV", "#CBD5E1", "#0F172A").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        importBtn.setOnClickListener {
            if (!ensureEditUnlocked()) return@setOnClickListener
            launchCsvImport()
        }

        backupRow.addView(exportBtn)
        addHorizontalGap(backupRow, 12)
        backupRow.addView(importBtn)
        backupCard.addView(backupRow)
        root.addView(backupCard)
        addSpacer(root, 16)

        val setupWizardBtn = makePillButton("🛠️ Edit Policy Rules, Profile & Permissions", "#4F46E5", "#FFFFFF") {
            if (isEditUnlocked) {
                showSetupMode()
            } else {
                promptDeviceCredential(REQ_UNLOCK_SETUP, "Unlock Policy & Profile Settings")
            }
        }
        root.addView(setupWizardBtn)

        return scroll
    }

    private fun makeSettingsSwitch(label: String, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = label
            textSize = 13f
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { view, checked ->
                if (!view.isPressed) return@setOnCheckedChangeListener
                if (!isEditUnlocked) {
                    isChecked = !checked
                    Toast.makeText(this@MainActivity, "Locked — Tap 🔒 at top to unlock edits", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                onToggle(checked)
                refreshAllViews()
            }
        }
    }

    private fun applyRadiusSelection(meters: Int) {
        if (!ensureEditUnlocked()) return
        store.setGeofenceRadiusMeters(meters)
        syncBackgroundAndWidget()
        refreshAllViews()
        Toast.makeText(this, "Geofence radius set to ${meters}m", Toast.LENGTH_SHORT).show()
    }

    // ==================== SELECT LOCATION (MAPS / GPS / 500M LIST) ====================

    private fun showSelectLocationDialog() {
        var dialogRef: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 16)
        }

        val searchInput = EditText(this).apply {
            hint = "Search landmark in Google Maps (e.g., Deloitte Towers, Hyderabad)"
            setText(store.getOfficeLocationName().ifEmpty { "Deloitte Towers, Hyderabad" })
            setPadding(20, 14, 20, 14)
        }
        container.addView(searchInput)
        addSpacer(container, 10)

        val openMapsBtn = makePillButton("🗺️ Open Google Maps to Search", "#0284C7", "#FFFFFF") {
            val query = searchInput.text.toString().trim().ifEmpty { "Deloitte Towers, Hyderabad" }
            val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                setPackage("com.google.android.apps.maps")
            }
            isAwaitingSystemIntent = true
            try {
                startActivity(mapIntent)
            } catch (_: Exception) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                }.onFailure {
                    isAwaitingSystemIntent = false
                    Toast.makeText(this, "No map app found", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(openMapsBtn)
        addSpacer(container, 10)

        val useCurrentGpsBtn = makePillButton("📍 Use Current Location + Auto Name", "#10B981", "#FFFFFF") {
            captureCurrentLocationWithGeocoder()
            dialogRef?.dismiss()
        }
        container.addView(useCurrentGpsBtn)
        addSpacer(container, 14)

        val presetsHeader = TextView(this).apply {
            text = "Offices & Campus Points (within 500m):"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(presetsHeader)

        val presets = store.getNearbyOfficePresets()
        presets.forEach { preset ->
            val presetBtn = makeSmallChipButton(
                "🏢 ${preset.name} (${preset.radiusMeters}m)"
            ) {
                store.setOfficeLocation(preset.name, preset.lat, preset.lng, preset.radiusMeters)
                syncBackgroundAndWidget()
                refreshAllViews()
                dialogRef?.dismiss()
                Toast.makeText(this, "Selected ${preset.name}", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            container.addView(presetBtn)
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle("Select Office Location")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun captureCurrentLocationWithGeocoder() {
        if (!hasLocationPermission()) {
            requestForegroundAndNotifPermissions()
            Toast.makeText(this, "Grant Location permission first", Toast.LENGTH_SHORT).show()
            return
        }
        val loc = getBestLastKnownLocation()
        if (loc == null) {
            Toast.makeText(
                this,
                "No GPS fix yet — open Google Maps or turn ON Location and try again",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val resolvedName = resolvePlaceNameFromCoordinates(loc.latitude, loc.longitude)
        val currentRadius = maxOf(500, store.getGeofenceRadiusMeters())
        store.setOfficeLocation(resolvedName, loc.latitude, loc.longitude, currentRadius)
        syncBackgroundAndWidget()
        refreshAllViews()
        Toast.makeText(this, "Set office to: $resolvedName", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun resolvePlaceNameFromCoordinates(lat: Double, lng: Double): String {
        return runCatching {
            val geocoder = Geocoder(this, Locale.getDefault())
            val list: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
            val addr = list?.firstOrNull()
            val candidate = addr?.featureName
                ?.takeIf { it.isNotBlank() && it != addr.subThoroughfare && !it.all { ch -> ch.isDigit() } }
                ?: addr?.subLocality
                ?: addr?.locality
            candidate ?: "Current Office (${String.format(Locale.US, "%.4f, %.4f", lat, lng)})"
        }.getOrDefault("Current Office (${String.format(Locale.US, "%.4f, %.4f", lat, lng)})")
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { loc ->
                if (best == null || loc.time > best!!.time) {
                    best = loc
                }
            }
        }
        return best
    }

    private fun triggerManualCheckNow() {
        checkNowBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val loc = getBestLastKnownLocation()
        if (loc != null) {
            store.setLastKnownLocation(loc.latitude, loc.longitude)
        }
        val liveSsid = getConnectedWifiSsid()
        val mode = store.getTrackingMode()

        if ((mode == TrackingMode.WIFI_ONLY || mode == TrackingMode.BOTH) && liveSsid.isNotEmpty()) {
            val targets = store.getTargetSsids()
            if (targets.any { it.equals(liveSsid, ignoreCase = true) }) {
                val isNew = store.recordOfficePresence(liveSsid)
                if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_${LocalDate.now()}")) {
                    sendAlertNotification(this, "✅ Office Day Logged ($liveSsid)", "Connected to office Wi-Fi.")
                }
            }
        }

        if ((mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) && loc != null && store.hasOfficeLocationConfigured()) {
            val dist = store.distanceToOfficeMeters(loc.latitude, loc.longitude)
            if (dist != null && dist <= store.getGeofenceRadiusMeters()) {
                val label = store.getOfficeLocationName().ifEmpty { "Office Geofence" }
                val isNew = store.recordOfficePresence(label)
                if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_${LocalDate.now()}")) {
                    sendAlertNotification(this, "✅ Office Day Logged ($label)", "Within ${store.getGeofenceRadiusMeters()}m office zone.")
                }
            }
        }

        syncBackgroundAndWidget()
        refreshAllViews()
        Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show()
    }

    // ==================== 4. REFRESH LOGIC ====================

    private fun refreshAllViews() {
        val locOk = hasLocationPermission()
        val notifOk = hasNotificationPermission()
        val bgLocOk = hasBackgroundLocationPermission()
        val gpsOk = isSystemLocationEnabled()
        val batOk = isBatteryUnrestricted()
        val liveSsid = getConnectedWifiSsid()
        val targetPct = store.getTargetPercent()
        val mode = store.getTrackingMode()

        val avatarBmp = loadCircularAvatarBitmap()
        setupAvatarView.setImageBitmap(avatarBmp)
        dashAvatarView.setImageBitmap(avatarBmp)

        setupPermStatusView.text = if (locOk && notifOk) {
            "✅ Location & Notifications: Allowed"
        } else {
            "⚠️ Location & Notifications: Action Required"
        }
        setupPermStatusView.setTextColor(Color.parseColor(if (locOk && notifOk) "#10B981" else "#EF4444"))

        setupBgLocStatusView.text = if (bgLocOk) {
            "✅ Background Location: Allow all the time"
        } else {
            "⚠️ Background Location: Must set to \"Allow all the time\""
        }
        setupBgLocStatusView.setTextColor(Color.parseColor(if (bgLocOk) "#10B981" else "#EF4444"))

        setupGpsStatusView.text = if (gpsOk) {
            "✅ Phone Location (GPS) Switch: ON"
        } else {
            "⚠️ Phone Location (GPS) Switch: OFF (Android hides Wi-Fi if OFF)"
        }
        setupGpsStatusView.setTextColor(Color.parseColor(if (gpsOk) "#10B981" else "#EF4444"))

        setupBatteryStatusView.text = if (batOk) {
            "✅ Background Battery: Unrestricted"
        } else {
            "⚠️ Background Battery: Restricted (Tap below & choose Allow)"
        }
        setupBatteryStatusView.setTextColor(Color.parseColor(if (batOk) "#10B981" else "#EF4444"))

        setupLiveWifiView.text = if (liveSsid.isEmpty()) {
            "Detected Wi-Fi: None (Connect to office Wi-Fi or type below)"
        } else {
            "Detected Wi-Fi: $liveSsid"
        }
        setupLocationSummaryView.text =
            "Office Zone: ${store.getOfficeLocationName()} (${store.getGeofenceRadiusMeters()}m radius)"
        setupTargetPctView.text = "Mandate Target: $targetPct%"

        val health = store.getBackupHealthInfo()
        setupBackupFolderStatusView.text = health.statusText
        autoBackupStatusView.text = health.statusText
        autoBackupStatusView.setTextColor(
            when {
                !health.configured -> cTextSecondary()
                health.healthy -> Color.parseColor("#10B981")
                else -> Color.parseColor("#EF4444")
            }
        )

        val uName = store.getUserName()
        dashGreetingView.text = if (uName.isNotEmpty()) "Hi, $uName" else "Hi there"

        val trackingReady = locOk && gpsOk && when (mode) {
            TrackingMode.WIFI_ONLY -> store.getTargetSsids().isNotEmpty()
            TrackingMode.LOCATION_ONLY -> store.hasOfficeLocationConfigured()
            TrackingMode.BOTH -> store.getTargetSsids().isNotEmpty() || store.hasOfficeLocationConfigured()
        }
        if (trackingReady) {
            dashServiceStatusView.text = "🟢 Auto-Tracking Active"
            dashServiceStatusView.setTextColor(Color.parseColor("#10B981"))
        } else {
            dashServiceStatusView.text = "🟠 Setup Incomplete (Tap Settings)"
            dashServiceStatusView.setTextColor(Color.parseColor("#F59E0B"))
        }

        lockToggleBtn.text = if (isEditUnlocked) "🔓 Unlocked" else "🔒 Locked"
        lockToggleBtn.background = roundedBox(
            if (isEditUnlocked) "#D1FAE5" else cInputBg(),
            if (isEditUnlocked) "#10B981" else cBorder(),
            16f,
            2
        )
        lockToggleBtn.setTextColor(
            if (isEditUnlocked) Color.parseColor("#065F46") else cTextPrimary()
        )

        val today = LocalDate.now()
        val todayType = store.getDayType(today)
        val isOnOfficePresence = store.wasConnectedToOfficeWifi()
        val activeSource = store.getActiveOfficeSsid()

        val disconnectedLabel = when (mode) {
            TrackingMode.LOCATION_ONLY -> "Not at office location"
            TrackingMode.WIFI_ONLY -> "Not connected to office Wi-Fi"
            TrackingMode.BOTH -> "Not connected to office Wi-Fi or at office location"
        }

        when {
            isOnOfficePresence -> {
                val srcTag = if (activeSource.isNotEmpty()) " ($activeSource)" else ""
                todayLiveStripView.text = "🟢 Connected / At Office$srcTag"
                todayLiveStripView.setTextColor(Color.parseColor("#065F46"))
                todayLiveStripView.background = roundedBox("#D1FAE5", "#10B981", 16f, 2)
            }
            todayType == DayType.OFFICE -> {
                todayLiveStripView.text = "✅ Office Day Logged Today"
                todayLiveStripView.setTextColor(if (isDark) Color.parseColor("#A7F3D0") else Color.parseColor("#065F46"))
                todayLiveStripView.background = roundedBox(
                    if (isDark) "#064E3B" else "#ECFDF5",
                    "#10B981",
                    16f,
                    1
                )
            }
            todayType == DayType.PLANNED -> {
                todayLiveStripView.text = "📅 Planned Office Day Today"
                todayLiveStripView.setTextColor(if (isDark) Color.parseColor("#C7D2FE") else Color.parseColor("#3730A3"))
                todayLiveStripView.background = roundedBox(
                    if (isDark) "#1E1B4B" else "#EEF2FF",
                    "#6366F1",
                    16f,
                    1
                )
            }
            todayType == DayType.LEAVE -> {
                todayLiveStripView.text = "🌴 On Leave Today"
                todayLiveStripView.setTextColor(Color.parseColor("#92400E"))
                todayLiveStripView.background = roundedBox("#FEF3C7", "#F59E0B", 16f, 1)
            }
            todayType == DayType.HOLIDAY -> {
                todayLiveStripView.text = "🎉 Company Holiday Today"
                todayLiveStripView.setTextColor(if (isDark) Color.parseColor("#DDD6FE") else Color.parseColor("#5B21B6"))
                todayLiveStripView.background = roundedBox(
                    if (isDark) "#2E1065" else "#F5F3FF",
                    "#8B5CF6",
                    16f,
                    1
                )
            }
            else -> {
                todayLiveStripView.text = "🏠 WFH Today • $disconnectedLabel"
                todayLiveStripView.setTextColor(cTextSecondary())
                todayLiveStripView.background = roundedBox(cCardHex(), cBorder(), 16f, 1)
            }
        }

        todayJumpBtn.visibility = if (selectedMonth != YearMonth.now()) View.VISIBLE else View.GONE

        val s = store.getMonthStats(selectedMonth)
        val w = store.getWeeklyPaceStats()

        monthTitleView.text = s.monthLabel

        val progressColorHex: String
        when {
            s.remainingDays == 0 -> {
                paceBannerView.text = "🎉 ${s.targetPercent}% Target Met! (${s.attendedDays}/${s.requiredDays} days) — Safe to WFH"
                paceBannerView.setTextColor(Color.parseColor("#065F46"))
                paceBannerView.background = roundedBox("#D1FAE5", "#6EE7B7", 18f)
                progressColorHex = "#10B981"
            }
            s.bufferDays < 0 -> {
                paceBannerView.text = "⚠️ Shortfall: Need ${s.remainingDays} day(s), only ${s.availableWeekdaysLeft} working day(s) left"
                paceBannerView.setTextColor(Color.parseColor("#991B1B"))
                paceBannerView.background = roundedBox("#FEE2E2", "#FCA5A5", 18f)
                progressColorHex = "#EF4444"
            }
            s.bufferDays == 0 -> {
                paceBannerView.text = "🚨 Zero Buffer: Attend all ${s.availableWeekdaysLeft} remaining working day(s)!"
                paceBannerView.setTextColor(Color.parseColor("#991B1B"))
                paceBannerView.background = roundedBox("#FEE2E2", "#FCA5A5", 18f)
                progressColorHex = "#EF4444"
            }
            else -> {
                paceBannerView.text = "On Track • ${s.bufferDays} WFH buffer day(s) left this month"
                paceBannerView.setTextColor(Color.parseColor("#92400E"))
                paceBannerView.background = roundedBox("#FEF3C7", "#FCD34D", 18f)
                progressColorHex = "#4F46E5"
            }
        }

        weeklyPacePillView.background = roundedBox(
            if (isDark) "#1E293B" else "#EEF2FF",
            if (isDark) "#334155" else "#C7D2FE",
            14f,
            1
        )
        weeklyPacePillView.setTextColor(if (isDark) Color.parseColor("#C7D2FE") else Color.parseColor("#3730A3"))
        weeklyPacePillView.text = if (w.remainingThisWeek == 0) {
            "✅ Weekly Pace Met: ${w.attendedThisWeek}/${w.weeklyQuota} office days logged this week"
        } else {
            "📆 This Week: ${w.attendedThisWeek}/${w.weeklyQuota} days done • Need ${w.remainingThisWeek} more (${w.weekdaysLeftThisWeek} weekday(s) left)"
        }

        circularRingView.setData(
            attended = s.attendedDays,
            projected = s.projectedAttendedDays,
            required = s.requiredDays,
            actualPct = s.actualPercent,
            accentColorHex = progressColorHex,
            isDark = isDark
        )
        heroSubView.text = "${s.actualPercent}% of ${s.effectiveWorkingDays} working days (${s.targetPercent}% mandate)"

        if (s.futurePlannedDays > 0) {
            heroProjectedView.visibility = View.VISIBLE
            heroProjectedView.text =
                "📅 +${s.futurePlannedDays} planned → Projected: ${s.projectedAttendedDays}/${s.requiredDays} days (${s.projectedPercent}%)"
        } else {
            heroProjectedView.visibility = View.GONE
        }

        kpiWorkingView.text = "${s.effectiveWorkingDays}\nWorking Days"
        kpiRemainingView.text = "${s.remainingDays}\nStill Needed"
        kpiBufferView.text = "${maxOf(0, s.bufferDays)}\nWFH Buffer"

        styleModeChip(modeOfficeBtn, activeTapMode == DayType.OFFICE, "#10B981")
        styleModeChip(modePlannedBtn, activeTapMode == DayType.PLANNED, "#4F46E5")
        styleModeChip(modeLeaveBtn, activeTapMode == DayType.LEAVE, "#F59E0B")
        styleModeChip(modeHolidayBtn, activeTapMode == DayType.HOLIDAY, "#8B5CF6")

        renderCalendarGrid(s)

        styleModeChip(filterAllBtn, activeLogFilter == LogFilter.ALL, "#334155")
        styleModeChip(filterOfficeBtn, activeLogFilter == LogFilter.OFFICE, "#10B981")
        styleModeChip(filterPlannedBtn, activeLogFilter == LogFilter.PLANNED, "#4F46E5")
        styleModeChip(filterLeaveHolBtn, activeLogFilter == LogFilter.LEAVE_HOLIDAY, "#F59E0B")

        val filteredLogs = s.dayLogs.filter { log ->
            when (activeLogFilter) {
                LogFilter.ALL -> true
                LogFilter.OFFICE -> log.type == DayType.OFFICE
                LogFilter.PLANNED -> log.type == DayType.PLANNED
                LogFilter.LEAVE_HOLIDAY -> log.type == DayType.LEAVE || log.type == DayType.HOLIDAY
            }
        }
        auditLogView.text = if (filteredLogs.isEmpty()) {
            "No matching entries in ${s.monthLabel}."
        } else {
            filteredLogs.joinToString("\n") { log ->
                val icon = when (log.type) {
                    DayType.OFFICE -> "🏢"
                    DayType.PLANNED -> "📅"
                    DayType.LEAVE -> "🌴"
                    DayType.HOLIDAY -> "🎉"
                    DayType.NONE -> "•"
                }
                "$icon ${log.dateStr} (${log.dayOfWeekShort}) — ${log.detail}"
            }
        }

        val q = store.getQuarterStats(selectedMonth)
        val r = store.getRhythmStats()
        val trends = store.getMultiMonthTrend(selectedMonth, 6)
        trendChartView.setData(trends, isDark)

        qtdTitleView.text = "${q.quarterLabel} Rollup"
        qtdDetailView.text =
            "Attended ${q.attendedDays} / ${q.requiredDays} required days (${q.actualPercent}% of ${q.effectiveWorkingDays} working days)"
        qtdLeaveHolView.text = "🌴 Quarterly Leaves: ${q.leaveDays}   •   🎉 Quarterly Holidays: ${q.holidayDays}"
        rhythmView.text = "🔥 ${r.weeklyStreak}-week streak   •   📍 Top days: ${r.topDaysLabel}"

        simLeaveCountView.text = "Simulated Extra Leaves: +$simLeaves"
        simHolidayCountView.text = "Simulated Extra Holidays: +$simHolidays"
        val simStats = store.getMonthStats(
            ym = selectedMonth,
            extraSimulatedLeaves = simLeaves,
            extraSimulatedHolidays = simHolidays
        )
        val exclNote = when {
            !store.isExcludeLeavesEnabled() && !store.isExcludeHolidaysEnabled() ->
                " (Enable Leave/Holiday exclusions in Setup to reduce target)"
            !store.isExcludeLeavesEnabled() && simLeaves > 0 ->
                " (Leave exclusion is OFF in Setup)"
            !store.isExcludeHolidaysEnabled() && simHolidays > 0 ->
                " (Holiday exclusion is OFF in Setup)"
            else -> ""
        }
        simResultView.background = roundedBox(
            if (isDark) "#0F172A" else "#EEF2FF",
            if (isDark) "#334155" else "#C7D2FE",
            14f,
            1
        )
        simResultView.setTextColor(if (isDark) Color.parseColor("#C7D2FE") else Color.parseColor("#3730A3"))
        simResultView.text =
            "Simulated Target: ${simStats.requiredDays} days (${simStats.effectiveWorkingDays} working days) • Still Needed: ${simStats.remainingDays} • Buffer: ${maxOf(0, simStats.bufferDays)}$exclNote"

        styleModeChip(modeWifiOnlyBtn, mode == TrackingMode.WIFI_ONLY, "#4F46E5")
        styleModeChip(modeLocOnlyBtn, mode == TrackingMode.LOCATION_ONLY, "#0284C7")
        styleModeChip(modeBothBtn, mode == TrackingMode.BOTH, "#10B981")

        trackingModeHintView.text = when (mode) {
            TrackingMode.WIFI_ONLY -> "Checks office Wi-Fi SSIDs only. Disconnected label: \"Not connected to office Wi-Fi\"."
            TrackingMode.LOCATION_ONLY -> "Checks GPS geofence radius only. Disconnected label: \"Not at office location\"."
            TrackingMode.BOTH -> "Checks both office Wi-Fi SSIDs and GPS geofence radius automatically."
        }

        locationSectionCard.visibility =
            if (mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) View.VISIBLE else View.GONE
        wifiSectionCard.visibility =
            if (mode == TrackingMode.WIFI_ONLY || mode == TrackingMode.BOTH) View.VISIBLE else View.GONE

        val radius = store.getGeofenceRadiusMeters()
        selectedOfficeView.text = "📍 ${store.getOfficeLocationName()} (${radius}m radius)"

        val lastLoc = getBestLastKnownLocation()
        if (lastLoc != null) {
            store.setLastKnownLocation(lastLoc.latitude, lastLoc.longitude)
        }
        val distMeters = store.distanceToOfficeMeters(
            lastLoc?.latitude ?: store.getLastKnownLat(),
            lastLoc?.longitude ?: store.getLastKnownLng()
        )
        officeDistanceView.text = if (distMeters != null && (lastLoc != null || store.hasLastKnownLocation())) {
            val dInt = distMeters.toInt()
            if (dInt <= radius) {
                "✅ Inside ${radius}m office zone (~${dInt}m away)"
            } else {
                "ℹ️ Outside ${radius}m office zone (~${dInt}m away)"
            }
        } else {
            "Coordinates: ${String.format(Locale.US, "%.4f, %.4f", store.getOfficeLat(), store.getOfficeLng())}"
        }

        styleModeChip(radius250Btn, radius == 250, "#0284C7")
        styleModeChip(radius500Btn, radius == 500, "#0284C7")
        styleModeChip(radius1000Btn, radius == 1000, "#0284C7")
        styleModeChip(radius1500Btn, radius == 1500, "#0284C7")

        notifConnectSwitch.isChecked = store.isConnectAlertEnabled()
        notifDisconnectSwitch.isChecked = store.isDisconnectAlertEnabled()
        notifPlannedSwitch.isChecked = store.isPlannedRemindersEnabled()
        notifMorningSwitch.isChecked = store.isMorningNudgeEnabled()
        notifTargetBufferSwitch.isChecked = store.isTargetBufferAlertEnabled()
        notifQuarterSwitch.isChecked = store.isQuarterPaceAlertEnabled()

        currentWifiView.text = if (liveSsid.isEmpty()) {
            "Current Wi-Fi: Not connected (or Location OFF)"
        } else {
            "Current Wi-Fi: $liveSsid"
        }
        renderWifiChips()
    }

    private fun renderWifiChips() {
        wifiChipsContainer.removeAllViews()
        val ssids = store.getTargetSsids()
        if (ssids.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No office Wi-Fi saved yet."
                textSize = 13f
                setTextColor(cTextSecondary())
                setPadding(0, 4, 0, 4)
            }
            wifiChipsContainer.addView(emptyTv)
            return
        }

        ssids.forEach { ssid ->
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBox(
                    if (isDark) "#1E293B" else "#EFF6FF",
                    if (isDark) "#334155" else "#BFDBFE",
                    16f,
                    2
                )
                setPadding(22, 14, 18, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            val nameTv = TextView(this).apply {
                text = "📶  $ssid"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isDark) Color.parseColor("#93C5FD") else Color.parseColor("#1E3A8A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#EF4444"))
                setPadding(16, 4, 8, 4)
                setOnClickListener {
                    if (!ensureEditUnlocked()) return@setOnClickListener
                    store.removeTargetSsid(ssid)
                    syncBackgroundAndWidget()
                    refreshAllViews()
                    Toast.makeText(this@MainActivity, "Removed '$ssid'", Toast.LENGTH_SHORT).show()
                }
            }
            chipRow.addView(nameTv)
            chipRow.addView(delBtn)
            wifiChipsContainer.addView(chipRow)
        }
    }

    private fun renderCalendarGrid(stats: MonthStats) {
        calendarContainer.removeAllViews()
        val today = LocalDate.now()
        val ym = stats.yearMonth

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { dayName ->
            val tv = TextView(this).apply {
                text = dayName
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cTextSecondary())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 4, 0, 10)
            }
            headerRow.addView(tv)
        }
        calendarContainer.addView(headerRow)

        val firstDayOffset = ym.atDay(1).dayOfWeek.value - 1
        val daysInMonth = ym.lengthOfMonth()
        var currentDay = 1

        while (currentDay <= daysInMonth) {
            val weekRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            for (col in 0..6) {
                val cell = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, 92, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                }
                if ((currentDay == 1 && col < firstDayOffset) || currentDay > daysInMonth) {
                    cell.text = ""
                } else {
                    val date = ym.atDay(currentDay)
                    val dateStr = date.toString()
                    val dtype = stats.dayTypeMap[dateStr] ?: DayType.NONE
                    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                    val isToday = date == today
                    val hasNote = store.getDayNote(dateStr).isNotEmpty()

                    cell.text = if (hasNote) "$currentDay•" else currentDay.toString()
                    when (dtype) {
                        DayType.OFFICE -> {
                            cell.setTextColor(Color.WHITE)
                            cell.setTypeface(null, Typeface.BOLD)
                            cell.background = roundedBox("#10B981", if (isToday) "#2563EB" else "#059669", 16f, if (isToday) 6 else 2)
                        }
                        DayType.PLANNED -> {
                            cell.setTextColor(if (isDark) Color.parseColor("#C7D2FE") else Color.parseColor("#3730A3"))
                            cell.setTypeface(null, Typeface.BOLD)
                            cell.background = dashedBox(
                                if (isDark) "#1E1B4B" else "#EEF2FF",
                                if (isToday) "#2563EB" else "#6366F1",
                                16f,
                                if (isToday) 6 else 4
                            )
                        }
                        DayType.LEAVE -> {
                            cell.setTextColor(Color.WHITE)
                            cell.setTypeface(null, Typeface.BOLD)
                            cell.background = roundedBox("#F59E0B", if (isToday) "#2563EB" else "#D97706", 16f, if (isToday) 6 else 2)
                        }
                        DayType.HOLIDAY -> {
                            cell.setTextColor(Color.WHITE)
                            cell.setTypeface(null, Typeface.BOLD)
                            cell.background = roundedBox("#8B5CF6", if (isToday) "#2563EB" else "#7C3AED", 16f, if (isToday) 6 else 2)
                        }
                        DayType.NONE -> {
                            if (isWeekend) {
                                cell.setTextColor(cTextSecondary())
                                cell.background = roundedBox(cInputBg(), if (isToday) "#2563EB" else cBorder(), 16f, if (isToday) 5 else 1)
                            } else {
                                cell.setTextColor(cTextPrimary())
                                cell.background = roundedBox(
                                    if (isDark) "#334155" else "#E2E8F0",
                                    if (isToday) "#2563EB" else cBorder(),
                                    16f,
                                    if (isToday) 6 else 1
                                )
                            }
                        }
                    }

                    cell.setOnClickListener { view ->
                        if (!isEditUnlocked) {
                            showDayInspectorDialog(date)
                            return@setOnClickListener
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val resulting = store.applyManualDayType(date, activeTapMode)
                        syncBackgroundAndWidget()
                        refreshAllViews()
                        val msg = if (resulting == DayType.NONE) {
                            "Cleared $dateStr"
                        } else {
                            "Marked $dateStr as ${resulting.name}"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }

                    cell.setOnLongClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showDayInspectorDialog(date)
                        true
                    }
                    currentDay++
                }
                weekRow.addView(cell)
            }
            calendarContainer.addView(weekRow)
        }
    }

    private fun showDayInspectorDialog(date: LocalDate) {
        val info = store.getDayInspectorInfo(date)
        val dowName = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val titleStr = "${date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} ($dowName)"

        var dialogRef: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 12)
        }

        val statusTv = TextView(this).apply {
            text = info.statusLabel
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        val windowTv = TextView(this).apply {
            text = info.timeWindowLabel
            textSize = 13f
            setPadding(0, 0, 0, 4)
        }
        val spanTv = TextView(this).apply {
            text = info.durationLabel
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 14)
        }
        container.addView(statusTv)
        container.addView(windowTv)
        container.addView(spanTv)

        if (isEditUnlocked) {
            val switchLabel = TextView(this).apply {
                text = "Quick Status Change:"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 4, 0, 6)
            }
            container.addView(switchLabel)

            val quickStatusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 14)
            }
            val options = listOf(
                "🏢" to DayType.OFFICE,
                "📅" to DayType.PLANNED,
                "🌴" to DayType.LEAVE,
                "🎉" to DayType.HOLIDAY,
                "Clear" to DayType.NONE
            )
            options.forEachIndexed { idx, (lbl, targetType) ->
                val chip = makeModeChip(lbl) {
                    store.setExplicitDayType(date, targetType)
                    syncBackgroundAndWidget()
                    refreshAllViews()
                    dialogRef?.dismiss()
                }
                styleModeChip(chip, info.type == targetType, "#4F46E5")
                quickStatusRow.addView(chip)
                if (idx < options.lastIndex) addHorizontalGap(quickStatusRow, 4)
            }
            container.addView(quickStatusRow)
        }

        val noteInput = EditText(this).apply {
            hint = if (isEditUnlocked) "Add note or tap a preset below..." else "Unlock 🔒 to edit note"
            setText(info.note)
            isEnabled = isEditUnlocked
            setPadding(20, 16, 20, 16)
        }
        container.addView(noteInput)

        if (isEditUnlocked) {
            val presetsRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 4)
            }
            listOf("Client Visit", "Half-Day", "Team Offsite").forEachIndexed { idx, preset ->
                val chip = makeSmallChipButton(preset) {
                    noteInput.setText(preset)
                    noteInput.setSelection(preset.length)
                }
                presetsRow1.addView(chip)
                if (idx < 2) addHorizontalGap(presetsRow1, 6)
            }
            val presetsRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 6)
            }
            listOf("Sick Leave", "WFH Approved").forEachIndexed { idx, preset ->
                val chip = makeSmallChipButton(preset) {
                    noteInput.setText(preset)
                    noteInput.setSelection(preset.length)
                }
                presetsRow2.addView(chip)
                if (idx < 1) addHorizontalGap(presetsRow2, 6)
            }
            container.addView(presetsRow1)
            container.addView(presetsRow2)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(titleStr)
            .setView(container)
            .setNegativeButton("Close", null)

        if (isEditUnlocked) {
            builder.setPositiveButton("Save Note") { _, _ ->
                store.setDayNote(date, noteInput.text.toString())
                refreshAllViews()
                Toast.makeText(this, "Note updated", Toast.LENGTH_SHORT).show()
            }
        }

        dialogRef = builder.show()
    }

    // ==================== 5. AVATAR IMAGE PROCESSING ====================

    private fun saveCompressedAvatar(uri: Uri) {
        runCatching {
            val rawBmp = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return

            val size = minOf(rawBmp.width, rawBmp.height)
            val xOffset = (rawBmp.width - size) / 2
            val yOffset = (rawBmp.height - size) / 2
            val square = Bitmap.createBitmap(rawBmp, xOffset, yOffset, size, size)
            val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)

            FileOutputStream(store.getProfilePhotoFile()).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Toast.makeText(this, "Profile photo updated!", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not load selected image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCircularAvatarBitmap(): Bitmap {
        val targetSize = 160
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, targetSize, targetSize)
        val rectF = RectF(rect)

        val photoFile = store.getProfilePhotoFile()
        if (photoFile.exists()) {
            val src = BitmapFactory.decodeFile(photoFile.absolutePath)
            if (src != null) {
                canvas.drawOval(rectF, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(src, null, rect, paint)
                return output
            }
        }

        paint.color = Color.parseColor("#4F46E5")
        canvas.drawOval(rectF, paint)

        val name = store.getUserName()
        val initial = if (name.isNotEmpty()) name.first().uppercaseChar().toString() else "W"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 68f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, canvas.width / 2f, yPos, textPaint)
        return output
    }

    // ==================== 6. SYSTEM & SERVICE HELPERS ====================

    private fun syncBackgroundAndWidget() {
        WfoWidgetProvider.updateAllWidgets(this)
        ReminderReceiver.scheduleNextCheckpoint(this)
        if (hasLocationPermission()) {
            runCatching {
                startForegroundService(
                    Intent(this, WifiMonitorService::class.java)
                        .setAction(WifiMonitorService.ACTION_REFRESH_STATUS)
                )
            }
        }
    }

    private fun startMonitorServiceIfPermitted() {
        if (!hasLocationPermission()) return
        runCatching {
            startForegroundService(Intent(this, WifiMonitorService::class.java))
        }
    }

    private fun launchBackupFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        launchSystemIntentForResult(intent, REQ_PICK_BACKUP_FOLDER)
    }

    private fun launchHolidayImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/calendar", "text/csv", "text/comma-separated-values", "text/plain", "*/*")
            )
        }
        launchSystemIntentForResult(intent, REQ_IMPORT_HOLIDAYS)
    }

    private fun launchCsvExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "wfo_attendance_backup.csv")
        }
        launchSystemIntentForResult(intent, REQ_EXPORT_CSV)
    }

    private fun launchCsvImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*")
            )
        }
        launchSystemIntentForResult(intent, REQ_IMPORT_CSV)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isSystemLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestForegroundAndNotifPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        } else {
            requestBackgroundLocation()
        }
    }

    private fun requestBackgroundLocation() {
        if (!hasLocationPermission()) {
            requestForegroundAndNotifPermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11
            )
        } else {
            Toast.makeText(this, "Background Location is already enabled!", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        if (isBatteryUnrestricted()) {
            Toast.makeText(this, "Battery is already unrestricted!", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(fallback)
        }
    }

    @Suppress("DEPRECATION")
    private fun getConnectedWifiSsid(): String {
        if (!hasLocationPermission()) return ""
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val raw = wifiManager.connectionInfo?.ssid ?: ""
        if (raw.isEmpty() || raw == "<unknown ssid>") return ""
        return raw.removePrefix("\"").removeSuffix("\"")
    }

    // ==================== 7. THEME & COMPONENT STYLING ====================

    private fun cBg(): Int = Color.parseColor(if (isDark) "#0F172A" else "#F1F5F9")
    private fun cCardHex(): String = if (isDark) "#1E293B" else "#FFFFFF"
    private fun cInputBg(): String = if (isDark) "#0F172A" else "#F8FAFC"
    private fun cBorder(): String = if (isDark) "#334155" else "#CBD5E1"
    private fun cTextPrimary(): Int = Color.parseColor(if (isDark) "#F8FAFC" else "#0F172A")
    private fun cTextSecondary(): Int = Color.parseColor(if (isDark) "#94A3B8" else "#64748B")

    private fun makeCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 4f
            setPadding(26, 22, 26, 22)
            background = roundedBox(cCardHex(), cBorder(), 22f, 2)
        }
    }

    private fun makeSectionTitle(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
        }
    }

    private fun makePillButton(
        label: String,
        bgHex: String,
        textHex: String,
        onClick: () -> Unit = {}
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textHex))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(24, 20, 24, 20)
            background = roundedBox(bgHex, bgHex, 18f, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallChipButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(18, 12, 18, 12)
            background = roundedBox(cInputBg(), cBorder(), 14f, 2)
            setOnClickListener { onClick() }
        }
    }

    private fun makeNavTabButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(12, 18, 12, 18)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun styleNavTab(btn: TextView, selected: Boolean) {
        if (selected) {
            btn.setTextColor(Color.WHITE)
            btn.background = roundedBox("#4F46E5", "#4F46E5", 16f, 0)
        } else {
            btn.setTextColor(cTextSecondary())
            btn.background = roundedBox(cInputBg(), cBorder(), 16f, 1)
        }
    }

    private fun makeModeChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(8, 14, 8, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun styleModeChip(chip: TextView, selected: Boolean, activeHex: String) {
        if (selected) {
            chip.setTextColor(Color.WHITE)
            chip.background = roundedBox(activeHex, activeHex, 14f, 0)
        } else {
            chip.setTextColor(cTextSecondary())
            chip.background = roundedBox(cInputBg(), cBorder(), 14f, 1)
        }
    }

    private fun makeKpiColumnView(): TextView {
        return TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1.0f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun roundedBox(
        fillHex: String,
        strokeHex: String,
        radiusPx: Float,
        strokeWidthPx: Int = 2
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(strokeWidthPx, Color.parseColor(strokeHex))
            cornerRadius = radiusPx
        }
    }

    private fun dashedBox(
        fillHex: String,
        strokeHex: String,
        radiusPx: Float,
        strokeWidthPx: Int = 4
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(strokeWidthPx, Color.parseColor(strokeHex), 12f, 8f)
            cornerRadius = radiusPx
        }
    }

    private fun addSpacer(parent: LinearLayout, heightPx: Int) {
        val space = TextView(this).apply { height = heightPx }
        parent.addView(space)
    }

    private fun addHorizontalGap(parent: LinearLayout, widthPx: Int) {
        val gap = TextView(this).apply { width = widthPx }
        parent.addView(gap)
    }

    // ==================== 8. CUSTOM CANVAS VISUALIZATIONS ====================

    private class CircularProgressRingView(context: Context) : View(context) {
        private var attended = 0
        private var projected = 0
        private var required = 9
        private var actualPct = 0
        private var accentColor = Color.parseColor("#4F46E5")
        private var dark = false

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
        }
        private val projectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
        }
        private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 54f
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }

        fun setData(
            attended: Int,
            projected: Int,
            required: Int,
            actualPct: Int,
            accentColorHex: String,
            isDark: Boolean
        ) {
            this.attended = attended
            this.projected = projected
            this.required = required
            this.actualPct = actualPct
            this.accentColor = Color.parseColor(accentColorHex)
            this.dark = isDark
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 28f
            val rect = RectF(pad, pad, width - pad, height - pad)

            trackPaint.color = Color.parseColor(if (dark) "#334155" else "#E2E8F0")
            projectedPaint.color = Color.parseColor(if (dark) "#3730A3" else "#C7D2FE")
            progressPaint.color = accentColor
            mainTextPaint.color = Color.parseColor(if (dark) "#F8FAFC" else "#0F172A")
            subTextPaint.color = Color.parseColor(if (dark) "#94A3B8" else "#64748B")

            canvas.drawArc(rect, 0f, 360f, false, trackPaint)

            val denom = maxOf(1, required).toFloat()
            val projSweep = ((projected / denom) * 360f).coerceIn(0f, 360f)
            val actualSweep = ((attended / denom) * 360f).coerceIn(0f, 360f)

            if (projSweep > actualSweep) {
                canvas.drawArc(rect, -90f, projSweep, false, projectedPaint)
            }
            if (actualSweep > 0f) {
                canvas.drawArc(rect, -90f, actualSweep, false, progressPaint)
            }

            val cx = width / 2f
            val cy = height / 2f
            canvas.drawText("$attended / $required", cx, cy + 8f, mainTextPaint)
            canvas.drawText("days ($actualPct%)", cx, cy + 48f, subTextPaint)
        }
    }

    private class TrendBarChartView(context: Context) : View(context) {
        private var items: List<MonthlyTrendPoint> = emptyList()
        private var dark = false

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 26f
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 24f
        }

        fun setData(points: List<MonthlyTrendPoint>, isDark: Boolean) {
            this.items = points
            this.dark = isDark
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (items.isEmpty()) return

            val topMargin = 42f
            val bottomMargin = 48f
            val chartHeight = (height - topMargin - bottomMargin).coerceAtLeast(40f)
            val colWidth = width.toFloat() / items.size

            val maxPct = maxOf(100, items.maxOf { it.actualPercent })
            val targetPct = items.first().targetPercent

            val targetY = topMargin + chartHeight * (1f - (targetPct / maxPct.toFloat()))
            targetLinePaint.color = Color.parseColor("#F59E0B")
            canvas.drawLine(16f, targetY, width - 16f, targetY, targetLinePaint)

            labelPaint.color = Color.parseColor(if (dark) "#94A3B8" else "#64748B")
            valuePaint.color = Color.parseColor(if (dark) "#F8FAFC" else "#0F172A")

            items.forEachIndexed { idx, pt ->
                val cx = colWidth * idx + colWidth / 2f
                val barHalfW = (colWidth * 0.26f).coerceAtMost(32f)
                val ratio = (pt.actualPercent / maxPct.toFloat()).coerceIn(0f, 1f)
                val barTop = topMargin + chartHeight * (1f - ratio)
                val barBottom = topMargin + chartHeight

                barPaint.color = Color.parseColor(
                    if (pt.actualPercent >= pt.targetPercent) "#10B981" else "#4F46E5"
                )
                val barRect = RectF(cx - barHalfW, barTop.coerceAtMost(barBottom - 6f), cx + barHalfW, barBottom)
                canvas.drawRoundRect(barRect, 10f, 10f, barPaint)

                canvas.drawText("${pt.actualPercent}%", cx, barTop - 10f, valuePaint)
                canvas.drawText(pt.shortMonth, cx, height - 12f, labelPaint)
            }
        }
    }
}
