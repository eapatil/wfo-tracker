package com.offline.wfo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_EXPORT_CSV = 201
        private const val REQ_IMPORT_CSV = 202
        private const val REQ_PICK_PHOTO = 203
        private const val REQ_UNLOCK_EDIT = 204
        private const val REQ_UNLOCK_SETUP = 205
        private const val REQ_UNLOCK_APP = 206
    }

    private lateinit var store: AttendanceStore
    private var selectedMonth: YearMonth = YearMonth.now()
    private var activeTapMode: DayType = DayType.OFFICE
    private var isEditUnlocked: Boolean = false
    private var isAppAuthenticated: Boolean = false
    private var autoPromptedBgLocation: Boolean = false
    private var isDark: Boolean = false

    // Root Containers
    private lateinit var appLockOverlay: LinearLayout
    private lateinit var setupScroll: ScrollView
    private lateinit var dashboardScroll: ScrollView

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
    private lateinit var setupTargetPctView: TextView
    private lateinit var setupRoundUpSwitch: Switch
    private lateinit var setupExclHolidaySwitch: Switch
    private lateinit var setupExclLeaveSwitch: Switch
    private lateinit var setupMinStayLabel: TextView
    private lateinit var setupPlanReminderSwitch: Switch
    private lateinit var setupMorningNudgeSwitch: Switch

    // Dashboard Views
    private lateinit var dashAvatarView: ImageView
    private lateinit var dashGreetingView: TextView
    private lateinit var dashServiceStatusView: TextView
    private lateinit var lockToggleBtn: TextView
    private lateinit var monthTitleView: TextView
    private lateinit var paceBannerView: TextView
    private lateinit var heroCountView: TextView
    private lateinit var heroSubView: TextView
    private lateinit var heroProjectedView: TextView
    private lateinit var heroProgressBar: ProgressBar
    private lateinit var kpiWorkingView: TextView
    private lateinit var kpiRemainingView: TextView
    private lateinit var kpiBufferView: TextView
    private lateinit var qtdTitleView: TextView
    private lateinit var qtdDetailView: TextView
    private lateinit var rhythmView: TextView
    private lateinit var modeOfficeBtn: TextView
    private lateinit var modePlannedBtn: TextView
    private lateinit var modeLeaveBtn: TextView
    private lateinit var modeHolidayBtn: TextView
    private lateinit var calendarContainer: LinearLayout
    private lateinit var currentWifiView: TextView
    private lateinit var wifiChipsContainer: LinearLayout
    private lateinit var ssidInput: EditText
    private lateinit var auditLogView: TextView

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
        dashboardScroll = buildDashboardView()
        appLockOverlay = buildAppLockOverlay()

        frameRoot.addView(setupScroll)
        frameRoot.addView(dashboardScroll)
        frameRoot.addView(appLockOverlay)
        setContentView(frameRoot)

        if (!store.isSetupComplete()) {
            isEditUnlocked = true
            isAppAuthenticated = true
            showSetupMode()
            requestForegroundAndNotifPermissions()
        } else {
            showDashboardMode()
            startMonitorServiceIfPermitted()
            if (store.isFullAppLockEnabled() && !isAppAuthenticated) {
                showAppLockScreen()
                promptDeviceCredential(REQ_UNLOCK_APP, "Unlock WFO Tracker")
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
        // Auto-relock edits and full-app lock whenever user leaves or minimizes the app
        if (store.isSetupComplete()) {
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

        when (requestCode) {
            REQ_UNLOCK_EDIT -> {
                if (resultCode == Activity.RESULT_OK) {
                    isEditUnlocked = true
                    refreshAllViews()
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
                    appLockOverlay.visibility = View.GONE
                    refreshAllViews()
                }
            }
            REQ_PICK_PHOTO -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    saveCompressedAvatar(data.data!!)
                    refreshAllViews()
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
                        val text = contentResolver.openInputStream(data.data!!)
                            ?.bufferedReader()?.use { it.readText() } ?: ""
                        val count = store.importFromCsv(text)
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

    // ==================== VIEW SWITCHERS & SECURITY ====================

    private fun showAppLockScreen() {
        appLockOverlay.visibility = View.VISIBLE
        setupScroll.visibility = View.GONE
        dashboardScroll.visibility = View.GONE
    }

    private fun showSetupMode() {
        appLockOverlay.visibility = View.GONE
        setupNameInput.setText(store.getUserName())
        setupSsidInput.setText(store.getTargetSsids().joinToString(", "))
        setupFullLockSwitch.isChecked = store.isFullAppLockEnabled()
        setupRoundUpSwitch.isChecked = store.isRoundUpEnabled()
        setupExclHolidaySwitch.isChecked = store.isExcludeHolidaysEnabled()
        setupExclLeaveSwitch.isChecked = store.isExcludeLeavesEnabled()
        setupPlanReminderSwitch.isChecked = store.isPlannedRemindersEnabled()
        setupMorningNudgeSwitch.isChecked = store.isMorningNudgeEnabled()
        setupScroll.visibility = View.VISIBLE
        dashboardScroll.visibility = View.GONE
        refreshAllViews()
    }

    private fun showDashboardMode() {
        appLockOverlay.visibility = View.GONE
        setupScroll.visibility = View.GONE
        dashboardScroll.visibility = View.VISIBLE
        refreshAllViews()
    }

    private fun ensureEditUnlocked(): Boolean {
        if (isEditUnlocked) return true
        Toast.makeText(this, "Locked — Tap 🔒 at top to unlock edits", Toast.LENGTH_SHORT).show()
        return false
    }

    @Suppress("DEPRECATION")
    private fun promptDeviceCredential(requestCode: Int, title: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            // Fallback if user has no PIN/Fingerprint set on their phone
            isEditUnlocked = true
            isAppAuthenticated = true
            appLockOverlay.visibility = View.GONE
            if (requestCode == REQ_UNLOCK_SETUP) showSetupMode() else refreshAllViews()
            Toast.makeText(this, "Unlocked (no device PIN set)", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(
            title,
            "Verify your identity to modify WFO attendance records"
        )
        if (intent != null) {
            startActivityForResult(intent, requestCode)
        } else {
            isEditUnlocked = true
            isAppAuthenticated = true
            refreshAllViews()
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
            text = "Personalize your profile, enable background permissions, and configure your 40% mandate rules."
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 20)
        }
        root.addView(title)
        root.addView(subtitle)

        // Card A: Personal Profile & Lock
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
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_PHOTO)
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

        // Card B: 4 System Permissions Checklist
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

        // Card C: Office Wi-Fi & Stay Filter
        val wifiCard = makeCardContainer()
        wifiCard.addView(makeSectionTitle("3. Office Wi-Fi & Minimum Stay"))
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

        val stayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 4)
        }
        setupMinStayLabel = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cycleStayBtn = makeSmallChipButton("Change") {
            val next = when (store.getMinStayMinutes()) {
                0 -> 30
                30 -> 60
                60 -> 120
                else -> 0
            }
            store.setMinStayMinutes(next)
            refreshAllViews()
        }
        stayRow.addView(setupMinStayLabel)
        stayRow.addView(cycleStayBtn)

        wifiCard.addView(setupLiveWifiView)
        wifiCard.addView(btnAddLive)
        addSpacer(wifiCard, 12)
        wifiCard.addView(setupSsidInput)
        wifiCard.addView(stayRow)
        root.addView(wifiCard)
        addSpacer(root, 20)

        // Card D: Mandate Policy & Alerts
        val policyCard = makeCardContainer()
        policyCard.addView(makeSectionTitle("4. Policy Rules & Smart Alerts"))

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
        setupPlanReminderSwitch = Switch(this).apply {
            text = "Planned Office Day Alerts (Eve, Morning, Rollover)"
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
            setPadding(0, 8, 0, 4)
        }

        policyCard.addView(setupRoundUpSwitch)
        policyCard.addView(setupExclHolidaySwitch)
        policyCard.addView(setupExclLeaveSwitch)
        policyCard.addView(setupPlanReminderSwitch)
        policyCard.addView(setupMorningNudgeSwitch)
        root.addView(policyCard)
        addSpacer(root, 24)

        val saveFinishBtn = makePillButton("✅ Save Settings & Start Auto-Tracker", "#10B981", "#FFFFFF") {
            store.setUserName(setupNameInput.text.toString())
            store.setFullAppLockEnabled(setupFullLockSwitch.isChecked)
            store.setTargetSsids(setupSsidInput.text.toString())
            store.setRoundUpEnabled(setupRoundUpSwitch.isChecked)
            store.setExcludeHolidaysEnabled(setupExclHolidaySwitch.isChecked)
            store.setExcludeLeavesEnabled(setupExclLeaveSwitch.isChecked)
            store.setPlannedRemindersEnabled(setupPlanReminderSwitch.isChecked)
            store.setMorningNudgeEnabled(setupMorningNudgeSwitch.isChecked)
            store.setSetupComplete(true)

            isEditUnlocked = false
            syncBackgroundAndWidget()
            showDashboardMode()
            Toast.makeText(this, "Settings saved & locked!", Toast.LENGTH_SHORT).show()
        }
        root.addView(saveFinishBtn)

        return scroll
    }

    // ==================== 3. MAIN ATTENDANCE DASHBOARD UI ====================

    private fun buildDashboardView(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 64)
        }
        scroll.addView(root)

        // 1. Personal Profile & Security Header
        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 18)
        }
        dashAvatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(104, 104)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val greetingCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 12, 0)
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

        lockToggleBtn = makeSmallChipButton("🔒") {
            if (isEditUnlocked) {
                isEditUnlocked = false
                refreshAllViews()
                Toast.makeText(this, "Dashboard locked", Toast.LENGTH_SHORT).show()
            } else {
                promptDeviceCredential(REQ_UNLOCK_EDIT, "Unlock Edit Mode")
            }
        }
        val setupBtn = makeSmallChipButton("⚙️") {
            if (isEditUnlocked) {
                showSetupMode()
            } else {
                promptDeviceCredential(REQ_UNLOCK_SETUP, "Unlock Setup Settings")
            }
        }

        profileHeader.addView(dashAvatarView)
        profileHeader.addView(greetingCol)
        profileHeader.addView(lockToggleBtn)
        addHorizontalGap(profileHeader, 8)
        profileHeader.addView(setupBtn)
        root.addView(profileHeader)

        // 2. Month Switcher Row
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
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
        val nextBtn = makeSmallChipButton("▶") {
            selectedMonth = selectedMonth.plusMonths(1)
            refreshAllViews()
        }
        navRow.addView(prevBtn)
        navRow.addView(monthTitleView)
        navRow.addView(nextBtn)
        root.addView(navRow)

        // 3. Smart Pace Status Pill
        paceBannerView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 22, 24, 22)
        }
        root.addView(paceBannerView)
        addSpacer(root, 18)

        // 4. Hero Progress Card
        val heroCard = makeCardContainer()
        heroCountView = TextView(this).apply {
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
        }
        heroSubView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        }
        heroProjectedView = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 14)
        }
        heroProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        }
        heroCard.addView(heroCountView)
        heroCard.addView(heroSubView)
        heroCard.addView(heroProjectedView)
        heroCard.addView(heroProgressBar)
        addSpacer(heroCard, 20)

        val kpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(cInputBg(), cBorder(), 16f, 1)
            setPadding(12, 16, 12, 16)
        }
        kpiWorkingView = makeKpiColumnView()
        kpiRemainingView = makeKpiColumnView()
        kpiBufferView = makeKpiColumnView()
        kpiRow.addView(kpiWorkingView)
        kpiRow.addView(kpiRemainingView)
        kpiRow.addView(kpiBufferView)
        heroCard.addView(kpiRow)
        root.addView(heroCard)
        addSpacer(root, 18)

        // 5. Quarter-to-Date (QTD) & Weekly Rhythm Card
        val qtdCard = makeCardContainer()
        qtdTitleView = makeSectionTitle("Quarter-to-Date Rollup")
        qtdDetailView = TextView(this).apply {
            textSize = 14f
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 10)
        }
        rhythmView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextSecondary())
        }
        qtdCard.addView(qtdTitleView)
        qtdCard.addView(qtdDetailView)
        qtdCard.addView(rhythmView)
        root.addView(qtdCard)
        addSpacer(root, 18)

        // 6. Interactive 4-State Calendar Card
        val calCard = makeCardContainer()
        calCard.addView(makeSectionTitle("Attendance & Leave Calendar"))
        val calHint = TextView(this).apply {
            text = "Unlock 🔒 to mark dates • Choose tap mode below:"
            textSize = 12f
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 12)
        }
        calCard.addView(calHint)

        // Tap Mode Selector Row
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

        calendarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        calCard.addView(calendarContainer)
        root.addView(calCard)
        addSpacer(root, 18)

        // 7. Office Wi-Fi Card
        val wifiCard = makeCardContainer()
        wifiCard.addView(makeSectionTitle("Office Wi-Fi Auto-Detection"))
        currentWifiView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        wifiCard.addView(currentWifiView)

        wifiChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiCard.addView(wifiChipsContainer)
        addSpacer(wifiCard, 10)

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
        wifiCard.addView(addCurrentWifiBtn)
        addSpacer(wifiCard, 10)

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
        wifiCard.addView(manualWifiRow)

        root.addView(wifiCard)
        addSpacer(root, 18)

        // 8. Daily Audit Log Card
        val logCard = makeCardContainer()
        logCard.addView(makeSectionTitle("Monthly Log & Timestamps"))
        auditLogView = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextPrimary())
            setLineSpacing(8f, 1.0f)
            setPadding(0, 10, 0, 0)
        }
        logCard.addView(auditLogView)
        root.addView(logCard)
        addSpacer(root, 18)

        // 9. Share Report & CSV Backup Actions
        val shareReportBtn = makePillButton("📋 Share Monthly Summary Report", "#4F46E5", "#FFFFFF") {
            val report = store.buildMonthlySummaryReport(selectedMonth)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "WFO Attendance Summary - ${selectedMonth}")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share WFO Summary"))
        }
        root.addView(shareReportBtn)
        addSpacer(root, 12)

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
        root.addView(backupRow)

        return scroll
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

        // Update Avatars
        val avatarBmp = loadCircularAvatarBitmap()
        setupAvatarView.setImageBitmap(avatarBmp)
        dashAvatarView.setImageBitmap(avatarBmp)

        // Setup Wizard Refresh
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
        val stayMins = store.getMinStayMinutes()
        setupMinStayLabel.text = if (stayMins == 0) {
            "Minimum Stay Filter: Instant (0 min)"
        } else {
            "Minimum Stay Filter: $stayMins mins"
        }
        setupTargetPctView.text = "Mandate Target: $targetPct%"

        // Dashboard Header Refresh
        val uName = store.getUserName()
        dashGreetingView.text = if (uName.isNotEmpty()) "Hi, $uName" else "Hi there"

        val trackingReady = locOk && gpsOk && store.getTargetSsids().isNotEmpty()
        if (trackingReady) {
            dashServiceStatusView.text = "🟢 Auto-WiFi Tracking Active"
            dashServiceStatusView.setTextColor(Color.parseColor("#10B981"))
        } else {
            dashServiceStatusView.text = "🟠 Setup Incomplete (Tap ⚙️)"
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

        // Monthly & Quarterly Stats Refresh
        val s = store.getMonthStats(selectedMonth)
        val q = store.getQuarterStats(selectedMonth)
        val r = store.getRhythmStats()

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

        val barPct = if (s.requiredDays > 0) {
            ((s.attendedDays * 100) / s.requiredDays).coerceIn(0, 100)
        } else 100

        heroCountView.text = "${s.attendedDays} / ${s.requiredDays} days"
        heroSubView.text = "${s.actualPercent}% of ${s.effectiveWorkingDays} working days (${s.targetPercent}% mandate)"

        if (s.futurePlannedDays > 0) {
            heroProjectedView.visibility = View.VISIBLE
            heroProjectedView.text =
                "📅 +${s.futurePlannedDays} planned → Projected: ${s.projectedAttendedDays}/${s.requiredDays} days (${s.projectedPercent}%)"
        } else {
            heroProjectedView.visibility = View.GONE
        }

        styleProgressBar(heroProgressBar, progressColorHex)
        heroProgressBar.progress = barPct

        kpiWorkingView.text = "${s.effectiveWorkingDays}\nWorking Days"
        kpiRemainingView.text = "${s.remainingDays}\nStill Needed"
        kpiBufferView.text = "${maxOf(0, s.bufferDays)}\nWFH Buffer"

        qtdTitleView.text = "${q.quarterLabel} Rollup"
        qtdDetailView.text =
            "Attended ${q.attendedDays} / ${q.requiredDays} required days (${q.actualPercent}% of ${q.effectiveWorkingDays} working days)"
        rhythmView.text = "🔥 ${r.weeklyStreak}-week streak   •   📍 Top days: ${r.topDaysLabel}"

        // Calendar Tap Mode Chips
        styleModeChip(modeOfficeBtn, activeTapMode == DayType.OFFICE, "#10B981")
        styleModeChip(modePlannedBtn, activeTapMode == DayType.PLANNED, "#4F46E5")
        styleModeChip(modeLeaveBtn, activeTapMode == DayType.LEAVE, "#F59E0B")
        styleModeChip(modeHolidayBtn, activeTapMode == DayType.HOLIDAY, "#8B5CF6")

        renderCalendarGrid(s)

        currentWifiView.text = if (liveSsid.isEmpty()) {
            "Current Wi-Fi: Not connected (or Location OFF)"
        } else {
            "Current Wi-Fi: $liveSsid"
        }
        renderWifiChips()

        auditLogView.text = if (s.dayLogs.isEmpty()) {
            "No entries recorded in ${s.monthLabel}."
        } else {
            s.dayLogs.joinToString("\n") { log ->
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

                    cell.text = currentDay.toString()
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

                    cell.setOnClickListener {
                        if (!ensureEditUnlocked()) return@setOnClickListener
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
                    currentDay++
                }
                weekRow.addView(cell)
            }
            calendarContainer.addView(weekRow)
        }
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

        // Default Initials Monogram Avatar
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

    @Suppress("DEPRECATION")
    private fun launchCsvExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "wfo_attendance_backup.csv")
        }
        startActivityForResult(intent, REQ_EXPORT_CSV)
    }

    @Suppress("DEPRECATION")
    private fun launchCsvImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_CSV)
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
            setPadding(28, 24, 28, 24)
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
            setPadding(24, 22, 24, 22)
            background = roundedBox(bgHex, bgHex, 18f, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallChipButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(20, 14, 20, 14)
            background = roundedBox(cInputBg(), cBorder(), 14f, 2)
            setOnClickListener { onClick() }
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

    private fun styleProgressBar(bar: ProgressBar, fillHex: String) {
        val bg = roundedBox(if (isDark) "#334155" else "#E2E8F0", "#00000000", 16f, 0)
        val fg = ClipDrawable(
            roundedBox(fillHex, fillHex, 16f, 0),
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        val layer = LayerDrawable(arrayOf(bg, fg)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        bar.progressDrawable = layer
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
}
