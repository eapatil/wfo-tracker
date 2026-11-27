package com.offline.wfo

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.graphics.drawable.ColorDrawable
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

        private const val CHANNEL_ALERTS_ID = "wfo_live_alerts_channel"
        private const val NOTIF_STATUS_PING_ID = 9042

        private const val THEME_PREFS_NAME = "officepace_ui_prefs"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val STATE_SELECTED_MONTH = "state_selected_month"
        private const val STATE_ACTIVE_TAB = "state_active_tab"
        private const val STATE_EDIT_UNLOCKED = "state_edit_unlocked"
        private const val STATE_APP_AUTH = "state_app_auth"
        private const val STATE_LOG_EXPANDED = "state_log_expanded"
        private const val STATE_NOTIF_EXPANDED = "state_notif_expanded"
        private const val STATE_HOL_EXPANDED = "state_hol_expanded"
    }

    private enum class ActiveTab { HOME, ANALYTICS, SETTINGS }
    private enum class LogFilter { ALL, OFFICE, PLANNED, LEAVE_HOLIDAY }
    private enum class ThemeChoice { SYSTEM, LIGHT, DARK }

    private data class GuideSectionSpec(
        val title: String,
        val subtitle: String,
        val accentRole: GlassUiTheme.AccentRole,
        val bullets: List<Pair<String, String>>,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )

    private lateinit var store: AttendanceStore
    private var selectedMonth: YearMonth = YearMonth.now()
    private var activeTapMode: DayType = DayType.OFFICE
    private var activeTab: ActiveTab = ActiveTab.HOME
    private var activeLogFilter: LogFilter = LogFilter.ALL
    private var isMonthlyLogExpanded: Boolean = false
    private var isNotificationsSectionExpanded: Boolean = false
    private var isUpcomingHolidaysSectionExpanded: Boolean = false
    private var simLeaves: Int = 0
    private var simHolidays: Int = 0
    private var lastUndoBatch: Map<LocalDate, DayType> = emptyMap()

    private var isEditUnlocked: Boolean = false
    private var isAppAuthenticated: Boolean = false
    private var isAwaitingSystemIntent: Boolean = false
    private var isRestoringData: Boolean = false
    private var autoPromptedBgLocation: Boolean = false
    private var isDark: Boolean = false
    private var hasPlayedOpenEntrance: Boolean = false

    // Blocking Restore Overlay Views
    private lateinit var restoreBlockingOverlay: LinearLayout
    private lateinit var restoreOverlayTitleView: TextView
    private lateinit var restoreOverlayDetailView: TextView

    // Lock Screen Live Animation & Status Card State
    private lateinit var lockIconView: TextView
    private lateinit var lockOuterHaloView: View
    private lateinit var lockInnerHaloView: View
    private lateinit var lockStatusCardView: LinearLayout
    private lateinit var lockStatusDotView: View
    private lateinit var lockStatusTitleView: TextView
    private lateinit var lockStatusSummaryView: TextView
    private var lockRunningAnimSet: AnimatorSet? = null

    // Live Tracking Heartbeat & Active Presence Pulse State
    private lateinit var heartbeatDotView: View
    private var heartbeatAnimator: AnimatorSet? = null
    private var liveStripPulseAnimator: ObjectAnimator? = null

    // Root Containers
    private lateinit var appLockOverlay: LinearLayout
    private lateinit var setupScroll: ScrollView
    private lateinit var mainScaffold: LinearLayout
    private lateinit var topHeaderBar: LinearLayout
    private lateinit var heroCardContainer: LinearLayout
    private lateinit var calendarCardContainer: LinearLayout
    private lateinit var homeTabScroll: ScrollView
    private lateinit var analyticsTabScroll: ScrollView
    private lateinit var settingsTabScroll: ScrollView

    // Bottom Navigation Tabs
    private lateinit var navHomeBtn: TextView
    private lateinit var navAnalyticsBtn: TextView
    private lateinit var navSettingsBtn: TextView

    // Setup Wizard Views & Readiness Action Buttons
    private lateinit var setupAvatarView: ImageView
    private lateinit var setupNameInput: EditText
    private lateinit var setupFullLockSwitch: Switch
    private lateinit var setupPermStatusView: TextView
    private lateinit var setupBgLocStatusView: TextView
    private lateinit var setupGpsStatusView: TextView
    private lateinit var setupExactAlarmStatusView: TextView
    private lateinit var setupBatteryStatusView: TextView
    private lateinit var btnGrantPermView: TextView
    private lateinit var btnBgLocView: TextView
    private lateinit var btnGpsView: TextView
    private lateinit var btnExactAlarmView: TextView
    private lateinit var btnBatteryView: TextView
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
    private lateinit var briefingCardView: LinearLayout
    private lateinit var briefingHeadlineView: TextView
    private lateinit var briefingSubView: TextView
    private lateinit var briefingWeeklyView: TextView
    private lateinit var briefingRecoveryView: TextView
    private lateinit var circularRingView: CircularProgressRingView
    private lateinit var heroSubView: TextView
    private lateinit var heroProjectedView: TextView
    private lateinit var kpiWorkingNumView: TextView
    private lateinit var kpiRemainingNumView: TextView
    private lateinit var kpiBufferNumView: TextView
    private lateinit var autoPlanBtn: TextView
    private lateinit var clearPlanBtn: TextView
    private lateinit var modeOfficeBtn: TextView
    private lateinit var modePlannedBtn: TextView
    private lateinit var modeLeaveBtn: TextView
    private lateinit var modeHolidayBtn: TextView
    private lateinit var calendarContainer: LinearLayout
    private lateinit var logHeaderToggleView: TextView
    private lateinit var logExpandBodyView: LinearLayout
    private lateinit var logSummaryBarView: TextView
    private lateinit var filterAllBtn: TextView
    private lateinit var filterOfficeBtn: TextView
    private lateinit var filterPlannedBtn: TextView
    private lateinit var filterLeaveHolBtn: TextView
    private lateinit var auditLogView: TextView

    // Tab 2: Analytics Views
    private lateinit var trendLegendView: TextView
    private lateinit var trendChartView: TrendBarChartView
    private lateinit var runRateFollowThroughView: TextView
    private lateinit var bufferTrajectoryView: TextView
    private lateinit var qtdTitleView: TextView
    private lateinit var qtdDetailView: TextView
    private lateinit var qtdLeaveHolView: TextView
    private lateinit var rhythmView: TextView
    private lateinit var simLeaveCountView: TextView
    private lateinit var simHolidayCountView: TextView
    private lateinit var simResultView: TextView

    // Tab 3: Settings & Data Views
    private lateinit var themeSystemBtn: TextView
    private lateinit var themeLightBtn: TextView
    private lateinit var themeDarkBtn: TextView
    private lateinit var activeThemeStatusView: TextView
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
    private lateinit var priorityWeekdaysContainer: LinearLayout
    private lateinit var priorityOrderPreviewView: TextView
    private lateinit var notifSummaryTv: TextView
    private lateinit var notifArrowTv: TextView
    private lateinit var notifBodyView: LinearLayout
    private lateinit var settingsNotifPermStatusView: TextView
    private lateinit var settingsExactAlarmStatusView: TextView
    private lateinit var notifConnectSwitch: Switch
    private lateinit var notifDisconnectSwitch: Switch
    private lateinit var notifPlannedSwitch: Switch
    private lateinit var notifMorningSwitch: Switch
    private lateinit var notifTargetBufferSwitch: Switch
    private lateinit var notifQuarterSwitch: Switch
    private lateinit var holidaySummaryTv: TextView
    private lateinit var holidayArrowTv: TextView
    private lateinit var holidayBodyView: LinearLayout
    private lateinit var upcomingHolLeaveSummaryView: TextView
    private lateinit var upcomingHolLeaveListView: LinearLayout
    private lateinit var autoBackupStatusView: TextView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isRestoringData) {
                refreshAllViews(animateCharts = false)
            }
        }
    }

    private fun getThemeChoice(): ThemeChoice {
        val raw = getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeChoice.SYSTEM.name) ?: ThemeChoice.SYSTEM.name
        return runCatching { ThemeChoice.valueOf(raw) }.getOrDefault(ThemeChoice.SYSTEM)
    }

    private fun setThemeChoiceAndApply(choice: ThemeChoice) {
        if (getThemeChoice() == choice) return
        getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, choice.name)
            .apply()
        recreate()
    }

    private fun resolveIsDarkMode(): Boolean {
        return when (getThemeChoice()) {
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
            ThemeChoice.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun showStatusPopup(success: Boolean, message: String) {
        val prefix = if (success) "✅ Success: " else "❌ Failed: "
        Toast.makeText(this, "$prefix$message", Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_MONTH, selectedMonth.toString())
        outState.putString(STATE_ACTIVE_TAB, activeTab.name)
        outState.putBoolean(STATE_EDIT_UNLOCKED, isEditUnlocked)
        outState.putBoolean(STATE_APP_AUTH, isAppAuthenticated)
        outState.putBoolean(STATE_LOG_EXPANDED, isMonthlyLogExpanded)
        outState.putBoolean(STATE_NOTIF_EXPANDED, isNotificationsSectionExpanded)
        outState.putBoolean(STATE_HOL_EXPANDED, isUpcomingHolidaysSectionExpanded)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AttendanceStore(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isRestoringData) {
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_SELECTED_MONTH)?.let { ymStr ->
                runCatching { YearMonth.parse(ymStr) }.getOrNull()?.let { selectedMonth = it }
            }
            savedInstanceState.getString(STATE_ACTIVE_TAB)?.let { tabStr ->
                runCatching { ActiveTab.valueOf(tabStr) }.getOrNull()?.let { activeTab = it }
            }
            isEditUnlocked = savedInstanceState.getBoolean(STATE_EDIT_UNLOCKED, false)
            isAppAuthenticated = savedInstanceState.getBoolean(STATE_APP_AUTH, false)
            isMonthlyLogExpanded = savedInstanceState.getBoolean(STATE_LOG_EXPANDED, false)
            isNotificationsSectionExpanded = savedInstanceState.getBoolean(STATE_NOTIF_EXPANDED, false)
            isUpcomingHolidaysSectionExpanded = savedInstanceState.getBoolean(STATE_HOL_EXPANDED, false)
        }

        isDark = resolveIsDarkMode()
        ensureAlertNotificationChannel()

        val frameRoot = FrameLayout(this).apply {
            background = GlassUiTheme.ambientBackdrop(isDark)
        }

        ViewCompat.setOnApplyWindowInsetsListener(frameRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupScroll = buildSetupWizardView()
        mainScaffold = buildMainScaffold()
        appLockOverlay = buildAppLockOverlay()
        restoreBlockingOverlay = buildRestoreBlockingOverlay()

        frameRoot.addView(setupScroll)
        frameRoot.addView(mainScaffold)
        frameRoot.addView(appLockOverlay)
        frameRoot.addView(restoreBlockingOverlay)
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
                promptDeviceCredential(REQ_UNLOCK_APP, "Unlock OfficePace")
            } else {
                showDashboardMode(playEntrance = savedInstanceState == null)
            }
        }
        refreshAllViews(animateCharts = savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        store.performDailyRolloverIfNeeded()
        if (!isRestoringData) {
            store.backupNowAsync()
            startMonitorServiceIfPermitted()
            refreshAllViews(animateCharts = false)
        }
        if (appLockOverlay.visibility == View.VISIBLE && !isRestoringData) {
            startLockScreenRunningAnimation()
        }
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(WifiMonitorService.ACTION_DATA_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        stopAllLoopingAnimations()
        runCatching { unregisterReceiver(updateReceiver) }
    }

    override fun onStop() {
        super.onStop()
        if (!isRestoringData) {
            store.backupNowAsync()
        }
        stopAllLoopingAnimations()
        if (store.isSetupComplete() && !isAwaitingSystemIntent && !isChangingConfigurations && !isRestoringData) {
            isEditUnlocked = false
            if (store.isFullAppLockEnabled()) {
                isAppAuthenticated = false
                showAppLockScreen()
            }
            refreshAllViews(animateCharts = false)
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
                    showDashboardMode(playEntrance = false)
                    showStatusPopup(true, "Edit mode unlocked")
                } else {
                    showStatusPopup(false, "Authentication cancelled or failed")
                }
            }
            REQ_UNLOCK_SETUP -> {
                if (resultCode == Activity.RESULT_OK) {
                    isEditUnlocked = true
                    showSetupMode()
                } else {
                    showStatusPopup(false, "Authentication cancelled or failed")
                }
            }
            REQ_UNLOCK_APP -> {
                if (resultCode == Activity.RESULT_OK) {
                    isAppAuthenticated = true
                    playUnlockTransitionThenDashboard()
                } else {
                    showAppLockScreen()
                    showStatusPopup(false, "Authentication required to open app")
                }
            }
            REQ_PICK_PHOTO -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val saved = store.saveProfilePhotoPair(data.data!!)
                    refreshAllViews(animateCharts = false)
                    if (saved) {
                        showStatusPopup(true, "Saved compressed & original profile photo")
                    } else {
                        showStatusPopup(false, "Could not load selected photo")
                    }
                } else {
                    showStatusPopup(false, "Profile photo selection cancelled")
                }
            }
            REQ_PICK_BACKUP_FOLDER -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val treeUri = data.data!!
                    runFolderRestoreWithBlockingOverlay(treeUri)
                } else {
                    showStatusPopup(false, "Backup folder selection cancelled")
                }
            }
            REQ_IMPORT_HOLIDAYS -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCatching {
                        val text = contentResolver.openInputStream(data.data!!)
                            ?.bufferedReader()?.use { it.readText() } ?: ""
                        val modified = store.bulkImportHolidays(text)
                        if (modified.isNotEmpty()) {
                            isUpcomingHolidaysSectionExpanded = true
                            showUndoBanner("Imported ${modified.size} holiday(s)", modified)
                            showStatusPopup(true, "Imported ${modified.size} holiday(s)")
                        } else {
                            showStatusPopup(false, "No new holiday dates found in file")
                        }
                        syncBackgroundAndWidget()
                        refreshAllViews(animateCharts = true)
                    }.onFailure {
                        showStatusPopup(false, "Could not read holiday file")
                    }
                } else {
                    showStatusPopup(false, "Holiday import cancelled")
                }
            }
            REQ_EXPORT_CSV -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCatching {
                        contentResolver.openOutputStream(data.data!!)?.use { out ->
                            out.write(store.exportAllCsv().toByteArray(Charsets.UTF_8))
                        }
                        showStatusPopup(true, "CSV backup saved")
                    }.onFailure {
                        showStatusPopup(false, "Failed to write CSV backup")
                    }
                } else {
                    showStatusPopup(false, "CSV export cancelled")
                }
            }
            REQ_IMPORT_CSV -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    runCsvImportWithBlockingOverlay(data.data!!)
                } else {
                    showStatusPopup(false, "CSV restore cancelled")
                }
            }
        }
    }

    private fun runFolderRestoreWithBlockingOverlay(treeUri: Uri) {
        showBlockingRestoreOverlay(
            title = "Restoring data...",
            detail = "Checking backup folder & restoring settings, weekday priorities, and logs."
        )
        Thread {
            val outcome = runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val permGranted = runCatching {
                    contentResolver.takePersistableUriPermission(treeUri, flags)
                    true
                }.getOrDefault(false)

                store.setAutoBackupTreeUri(treeUri.toString())
                val hadLocalDataBefore = store.getAllMarkedDates().isNotEmpty()
                val importedCount = store.restoreExistingBackupFromFolder(treeUri)
                val synced = store.triggerSilentAutoBackup()

                FolderRestoreOutcome(
                    permGranted = permGranted,
                    hadLocalDataBefore = hadLocalDataBefore,
                    importedCount = importedCount,
                    synced = synced
                )
            }

            runOnUiThread {
                hideBlockingRestoreOverlay()
                outcome.onSuccess { res ->
                    syncRestoredSetupInputs()
                    if (res.importedCount > 0) {
                        activeLogFilter = LogFilter.ALL
                        syncBackgroundAndWidget()
                        refreshAllViews(animateCharts = true)
                        val syncNote = if (res.synced) " & auto-backup synced" else ""
                        showStatusPopup(true, "Restored ${res.importedCount} item(s)$syncNote")
                    } else {
                        refreshAllViews(animateCharts = false)
                        if (res.synced) {
                            showStatusPopup(
                                true,
                                if (res.hadLocalDataBefore) {
                                    "No prior backup file found — created & synced new backup"
                                } else {
                                    "No prior backup file found — created new backup folder"
                                }
                            )
                        } else if (!res.permGranted) {
                            showStatusPopup(false, "Folder selected, but persistable write permission was not granted")
                        } else {
                            showStatusPopup(false, "Folder linked, but could not write backup file yet")
                        }
                    }
                }.onFailure {
                    refreshAllViews(animateCharts = false)
                    showStatusPopup(false, "Could not complete folder restore")
                }
            }
        }.start()
    }

    private fun runCsvImportWithBlockingOverlay(uri: Uri) {
        showBlockingRestoreOverlay(
            title = "Restoring data...",
            detail = "Importing CSV rows, weekday priorities, and full-lock preferences."
        )
        Thread {
            val result = runCatching {
                store.importFromUri(uri, autoBackupAfter = true)
            }
            runOnUiThread {
                hideBlockingRestoreOverlay()
                result.onSuccess { count ->
                    syncRestoredSetupInputs()
                    activeLogFilter = LogFilter.ALL
                    syncBackgroundAndWidget()
                    refreshAllViews(animateCharts = true)
                    if (count > 0) {
                        showStatusPopup(true, "Restored $count date(s) from CSV")
                    } else {
                        showStatusPopup(false, "No valid attendance rows found in selected CSV")
                    }
                }.onFailure {
                    refreshAllViews(animateCharts = false)
                    showStatusPopup(false, "Could not read selected CSV file")
                }
            }
        }.start()
    }

    private fun syncRestoredSetupInputs() {
        if (::setupNameInput.isInitialized && store.getUserName().isNotEmpty()) {
            setupNameInput.setText(store.getUserName())
        }
        if (::setupFullLockSwitch.isInitialized) {
            setupFullLockSwitch.isChecked = store.isFullAppLockEnabled()
        }
        if (::setupSsidInput.isInitialized) {
            setupSsidInput.setText(store.getTargetSsids().joinToString(", "))
        }
        if (::setupRoundUpSwitch.isInitialized) {
            setupRoundUpSwitch.isChecked = store.isRoundUpEnabled()
        }
        if (::setupExclHolidaySwitch.isInitialized) {
            setupExclHolidaySwitch.isChecked = store.isExcludeHolidaysEnabled()
        }
        if (::setupExclLeaveSwitch.isInitialized) {
            setupExclLeaveSwitch.isChecked = store.isExcludeLeavesEnabled()
        }
        if (::setupConnectAlertSwitch.isInitialized) {
            setupConnectAlertSwitch.isChecked = store.isConnectAlertEnabled()
        }
        if (::setupDisconnectAlertSwitch.isInitialized) {
            setupDisconnectAlertSwitch.isChecked = store.isDisconnectAlertEnabled()
        }
        if (::setupPlanReminderSwitch.isInitialized) {
            setupPlanReminderSwitch.isChecked = store.isPlannedRemindersEnabled()
        }
        if (::setupMorningNudgeSwitch.isInitialized) {
            setupMorningNudgeSwitch.isChecked = store.isMorningNudgeEnabled()
        }
        if (::setupTargetBufferAlertSwitch.isInitialized) {
            setupTargetBufferAlertSwitch.isChecked = store.isTargetBufferAlertEnabled()
        }
        if (::setupQuarterAlertSwitch.isInitialized) {
            setupQuarterAlertSwitch.isChecked = store.isQuarterPaceAlertEnabled()
        }
        renderPriorityWeekdays()
        syncRadiusChipHighlights()
    }

    private data class FolderRestoreOutcome(
        val permGranted: Boolean,
        val hadLocalDataBefore: Boolean,
        val importedCount: Int,
        val synced: Boolean
    )

    private fun showBlockingRestoreOverlay(title: String, detail: String) {
        isRestoringData = true
        stopAllLoopingAnimations()
        if (::mainScaffold.isInitialized) GlassUiTheme.applyGlassBlur(mainScaffold)
        if (::setupScroll.isInitialized) GlassUiTheme.applyGlassBlur(setupScroll)
        if (::restoreBlockingOverlay.isInitialized) {
            restoreOverlayTitleView.text = title
            restoreOverlayDetailView.text = detail
            restoreBlockingOverlay.alpha = 1f
            restoreBlockingOverlay.visibility = View.VISIBLE
            restoreBlockingOverlay.bringToFront()
        }
    }

    private fun hideBlockingRestoreOverlay() {
        isRestoringData = false
        if (::mainScaffold.isInitialized) GlassUiTheme.clearGlassBlur(mainScaffold)
        if (::setupScroll.isInitialized) GlassUiTheme.clearGlassBlur(setupScroll)
        if (::restoreBlockingOverlay.isInitialized) {
            restoreBlockingOverlay.visibility = View.GONE
        }
    }

    private fun buildRestoreBlockingOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GlassUiTheme.glassNavDock(isDark)
            setPadding(48, 48, 48, 48)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GlassUiTheme.glassCard(isDark, GlassUiTheme.cardRadiusPx)
                setPadding(36, 32, 36, 32)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val spinner = ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(84, 84).apply {
                    setMargins(0, 0, 0, 18)
                }
            }

            restoreOverlayTitleView = TextView(this@MainActivity).apply {
                text = "Restoring data..."
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cTextPrimary())
                gravity = Gravity.CENTER
            }

            restoreOverlayDetailView = TextView(this@MainActivity).apply {
                text = "Please wait while your backup data is applied."
                textSize = GlassUiTheme.cardBodySp
                setTextColor(cTextSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }

            card.addView(spinner)
            card.addView(restoreOverlayTitleView)
            card.addView(restoreOverlayDetailView)
            addView(card)
        }
    }

    private fun showFullScreenProfilePhotoDialog() {
        val targetFile = store.getFullScreenProfilePhotoFile()
        if (!targetFile.exists() || targetFile.length() == 0L) {
            showStatusPopup(false, "No profile photo saved yet")
            return
        }

        val fullBmp = runCatching {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1
            }
            BitmapFactory.decodeFile(targetFile.absolutePath, opts)
        }.getOrNull()

        if (fullBmp == null) {
            showStatusPopup(false, "Unable to open full-screen profile photo")
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this).apply {
            background = GlassUiTheme.ambientBackdrop(true)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        val photoView = ImageView(this).apply {
            setImageBitmap(fullBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            setOnClickListener { dialog.dismiss() }
        }

        val closeBtn = TextView(this).apply {
            text = "✕ Close"
            textSize = GlassUiTheme.tonalButtonSp
            setTypeface(null, Typeface.BOLD)
            setTextColor(GlassUiTheme.textOnAccent)
            background = GlassUiTheme.glassSubtleSurface(true, GlassUiTheme.bannerRadiusPx)
            setPadding(24, 14, 24, 14)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(24, 36, 24, 24)
            }
            setOnClickListener { dialog.dismiss() }
        }

        container.addView(photoView)
        container.addView(closeBtn)
        dialog.setContentView(container)
        dialog.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startMonitorServiceIfPermitted()
        refreshAllViews(animateCharts = false)

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

    private fun ensureAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "OfficePace Live Status & Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Animated heads-up alerts and live presence notifications"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun postAnimatedStatusNotification(title: String, body: String, activePresence: Boolean) {
        if (!hasNotificationPermission()) return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            701,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = Intent(this, WifiMonitorService::class.java).apply {
            action = WifiMonitorService.ACTION_REFRESH_STATUS
        }
        val refreshPending = PendingIntent.getService(
            this,
            702,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_popup_sync, "Refresh Check", refreshPending)

        if (activePresence) {
            builder.setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setOngoing(false)
        }

        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_STATUS_PING_ID, builder.build())
        }
    }

    private fun stopAllLoopingAnimations() {
        stopLockScreenRunningAnimation()
        stopHeartbeatAnimation()
        stopLiveStripPulse()
    }

    private fun startHeartbeatAnimation(active: Boolean) {
        if (!::heartbeatDotView.isInitialized) return
        stopHeartbeatAnimation()
        if (!active) {
            heartbeatDotView.scaleX = 1f
            heartbeatDotView.scaleY = 1f
            heartbeatDotView.alpha = 0.45f
            return
        }

        heartbeatDotView.alpha = 1f
        val beat = ObjectAnimator.ofPropertyValuesHolder(
            heartbeatDotView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.38f, 1.06f, 1.3f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.38f, 1.06f, 1.3f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f, 1f, 0.75f, 1f)
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        heartbeatAnimator = AnimatorSet().apply {
            play(beat)
            start()
        }
    }

    private fun stopHeartbeatAnimation() {
        heartbeatAnimator?.cancel()
        heartbeatAnimator = null
        if (::heartbeatDotView.isInitialized) {
            heartbeatDotView.scaleX = 1f
            heartbeatDotView.scaleY = 1f
        }
    }

    private fun startLiveStripPulse(enabled: Boolean) {
        if (!::todayLiveStripView.isInitialized) return
        stopLiveStripPulse()
        if (!enabled) {
            todayLiveStripView.scaleX = 1f
            todayLiveStripView.scaleY = 1f
            return
        }
        liveStripPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            todayLiveStripView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.015f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.03f, 1f)
        ).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopLiveStripPulse() {
        liveStripPulseAnimator?.cancel()
        liveStripPulseAnimator = null
        if (::todayLiveStripView.isInitialized) {
            todayLiveStripView.scaleX = 1f
            todayLiveStripView.scaleY = 1f
        }
    }

    private fun shakeLockHint() {
        if (!::lockToggleBtn.isInitialized) return
        lockToggleBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ObjectAnimator.ofFloat(
            lockToggleBtn,
            View.TRANSLATION_X,
            0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f
        ).apply {
            duration = 360L
            start()
        }
    }

    private fun updateLockScreenStatusCard() {
        if (!::lockStatusTitleView.isInitialized || !::lockStatusSummaryView.isInitialized || !::lockStatusDotView.isInitialized) return

        val locOk = hasLocationPermission()
        val gpsOk = isSystemLocationEnabled()
        val mode = store.getTrackingMode()
        val trackingReady = locOk && gpsOk && when (mode) {
            TrackingMode.WIFI_ONLY -> store.getTargetSsids().isNotEmpty()
            TrackingMode.LOCATION_ONLY -> store.hasOfficeLocationConfigured()
            TrackingMode.BOTH -> store.getTargetSsids().isNotEmpty() || store.hasOfficeLocationConfigured()
        }

        val isConnected = store.wasConnectedToOfficeWifi()
        val activeSrc = store.getActiveOfficeSsid()
        val stats = store.getMonthStats(YearMonth.now())

        when {
            isConnected -> {
                val okCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
                lockStatusTitleView.text = if (activeSrc.isNotEmpty()) "Connected: $activeSrc" else "In Office Zone"
                lockStatusTitleView.setTextColor(GlassUiTheme.statusSuccessText(isDark))
                lockStatusDotView.background = GlassUiTheme.roundedShape(okCol, okCol, 999f, 0)
            }
            trackingReady -> {
                val okCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
                lockStatusTitleView.text = "Auto-Tracking Running"
                lockStatusTitleView.setTextColor(GlassUiTheme.statusSuccessText(isDark))
                lockStatusDotView.background = GlassUiTheme.roundedShape(okCol, okCol, 999f, 0)
            }
            else -> {
                val warnCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.WARNING).second
                lockStatusTitleView.text = "Background Monitoring Paused"
                lockStatusTitleView.setTextColor(GlassUiTheme.statusWarningText(isDark))
                lockStatusDotView.background = GlassUiTheme.roundedShape(warnCol, warnCol, 999f, 0)
            }
        }

        lockStatusSummaryView.text =
            "${stats.monthLabel}: ${stats.attendedDays}/${stats.requiredDays} days (${stats.actualPercent}%) • ${maxOf(0, stats.bufferDays)} buffer left"
    }

    private fun startLockScreenRunningAnimation() {
        if (!::lockIconView.isInitialized || !::lockOuterHaloView.isInitialized || !::lockInnerHaloView.isInitialized) return
        stopLockScreenRunningAnimation()
        updateLockScreenStatusCard()

        lockIconView.text = "🔒"
        lockIconView.rotation = 0f

        val outerRadar = ObjectAnimator.ofPropertyValuesHolder(
            lockOuterHaloView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.55f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.55f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0.0f)
        ).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }

        val innerPulse = ObjectAnimator.ofPropertyValuesHolder(
            lockInnerHaloView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.16f, 0.92f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.16f, 0.92f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 0.25f, 0.65f)
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val pendulumRock = ObjectAnimator.ofPropertyValuesHolder(
            lockIconView,
            PropertyValuesHolder.ofFloat(View.ROTATION, -8f, 8f, -8f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -10f, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f)
        ).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val dotBeat = if (::lockStatusDotView.isInitialized) {
            ObjectAnimator.ofPropertyValuesHolder(
                lockStatusDotView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.4f, 1.08f, 1.32f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.4f, 1.08f, 1.32f, 1f)
            ).apply {
                duration = 1400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        } else null

        val cardBreath = if (::lockStatusCardView.isInitialized) {
            ObjectAnimator.ofPropertyValuesHolder(
                lockStatusCardView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f, 1f)
            ).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        } else null

        val anims = listOfNotNull(outerRadar, innerPulse, pendulumRock, dotBeat, cardBreath)
        lockRunningAnimSet = AnimatorSet().apply {
            playTogether(anims)
            start()
        }
    }

    private fun stopLockScreenRunningAnimation() {
        lockRunningAnimSet?.cancel()
        lockRunningAnimSet = null
        if (::lockOuterHaloView.isInitialized) {
            lockOuterHaloView.scaleX = 1f
            lockOuterHaloView.scaleY = 1f
            lockOuterHaloView.alpha = 0.25f
        }
        if (::lockInnerHaloView.isInitialized) {
            lockInnerHaloView.scaleX = 1f
            lockInnerHaloView.scaleY = 1f
            lockInnerHaloView.alpha = 0.45f
        }
        if (::lockIconView.isInitialized) {
            lockIconView.translationY = 0f
            lockIconView.scaleX = 1f
            lockIconView.scaleY = 1f
            lockIconView.rotation = 0f
        }
        if (::lockStatusDotView.isInitialized) {
            lockStatusDotView.scaleX = 1f
            lockStatusDotView.scaleY = 1f
        }
        if (::lockStatusCardView.isInitialized) {
            lockStatusCardView.scaleX = 1f
            lockStatusCardView.scaleY = 1f
        }
    }

    private fun playUnlockTransitionThenDashboard() {
        if (!::lockIconView.isInitialized) {
            showDashboardMode(playEntrance = true)
            return
        }
        stopLockScreenRunningAnimation()
        lockIconView.text = "🔓"

        val pop = ObjectAnimator.ofPropertyValuesHolder(
            lockIconView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.44f, 1.15f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.44f, 1.15f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -18f, 0f),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, -22f, 14f, 0f)
        ).apply {
            duration = 440L
            interpolator = OvershootInterpolator(1.2f)
        }

        val cardSlideOut = if (::lockStatusCardView.isInitialized) {
            ObjectAnimator.ofPropertyValuesHolder(
                lockStatusCardView,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, 36f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)
            ).apply {
                duration = 260L
                startDelay = 120L
                interpolator = DecelerateInterpolator()
            }
        } else null

        val fadeOverlay = ObjectAnimator.ofFloat(appLockOverlay, View.ALPHA, 1f, 0f).apply {
            duration = 260L
            startDelay = 200L
        }

        val group = listOfNotNull(pop, cardSlideOut, fadeOverlay)
        AnimatorSet().apply {
            playTogether(group)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    appLockOverlay.alpha = 1f
                    if (::lockStatusCardView.isInitialized) {
                        lockStatusCardView.alpha = 1f
                        lockStatusCardView.translationY = 0f
                    }
                    showDashboardMode(playEntrance = true)
                }
            })
            start()
        }
    }

    private fun playAppOpenEntranceAnimation() {
        if (hasPlayedOpenEntrance) return
        hasPlayedOpenEntrance = true

        val targets = listOfNotNull(
            if (::topHeaderBar.isInitialized) topHeaderBar to 0L else null,
            if (::heroCardContainer.isInitialized) heroCardContainer to 85L else null,
            if (::calendarCardContainer.isInitialized) calendarCardContainer to 170L else null
        )

        targets.forEach { (view, delay) ->
            view.alpha = 0f
            view.translationY = 44f
            view.scaleX = 0.96f
            view.scaleY = 0.96f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay)
                .setDuration(340L)
                .setInterpolator(OvershootInterpolator(0.85f))
                .start()
        }
    }

    private fun animateTabReveal(target: View) {
        target.alpha = 0f
        target.translationY = 24f
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(230L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateCalendarGlide(direction: Int) {
        if (!::calendarContainer.isInitialized) return
        val offset = (110f * direction)
        calendarContainer.alpha = 0.15f
        calendarContainer.translationX = offset
        calendarContainer.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(250L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateCellPop(view: View) {
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(85L)
            .withEndAction {
                view.animate()
                    .scaleX(1.12f)
                    .scaleY(1.12f)
                    .setDuration(120L)
                    .setInterpolator(OvershootInterpolator(1.3f))
                    .withEndAction {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(90L)
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun showAppLockScreen() {
        stopHeartbeatAnimation()
        stopLiveStripPulse()
        appLockOverlay.alpha = 1f
        appLockOverlay.visibility = View.VISIBLE
        setupScroll.visibility = View.GONE
        mainScaffold.visibility = View.GONE
        updateLockScreenStatusCard()
        startLockScreenRunningAnimation()
    }

    private fun showSetupMode() {
        stopLockScreenRunningAnimation()
        appLockOverlay.visibility = View.GONE
        syncRestoredSetupInputs()
        setupScroll.visibility = View.VISIBLE
        mainScaffold.visibility = View.GONE
        refreshAllViews(animateCharts = false)
    }

    private fun showDashboardMode(playEntrance: Boolean = false) {
        stopLockScreenRunningAnimation()
        appLockOverlay.visibility = View.GONE
        setupScroll.visibility = View.GONE
        mainScaffold.visibility = View.VISIBLE
        switchTab(activeTab, animate = false)
        refreshAllViews(animateCharts = playEntrance)
        if (playEntrance) {
            playAppOpenEntranceAnimation()
        }
    }

    private fun switchTab(tab: ActiveTab, animate: Boolean = true) {
        activeTab = tab
        homeTabScroll.visibility = if (tab == ActiveTab.HOME) View.VISIBLE else View.GONE
        analyticsTabScroll.visibility = if (tab == ActiveTab.ANALYTICS) View.VISIBLE else View.GONE
        settingsTabScroll.visibility = if (tab == ActiveTab.SETTINGS) View.VISIBLE else View.GONE

        styleNavTab(navHomeBtn, tab == ActiveTab.HOME)
        styleNavTab(navAnalyticsBtn, tab == ActiveTab.ANALYTICS)
        styleNavTab(navSettingsBtn, tab == ActiveTab.SETTINGS)

        val activeScroll = when (tab) {
            ActiveTab.HOME -> homeTabScroll
            ActiveTab.ANALYTICS -> analyticsTabScroll
            ActiveTab.SETTINGS -> settingsTabScroll
        }
        if (animate) {
            animateTabReveal(activeScroll)
        }
        refreshAllViews(animateCharts = tab == ActiveTab.ANALYTICS)
    }

    private fun showUndoBanner(message: String, batch: Map<LocalDate, DayType>) {
        lastUndoBatch = batch
        undoBannerText.text = message
        undoBannerRow.alpha = 0f
        undoBannerRow.translationY = -44f
        undoBannerRow.visibility = View.VISIBLE
        undoBannerRow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(OvershootInterpolator(0.9f))
            .start()
    }

    private fun ensureEditUnlocked(): Boolean {
        if (isEditUnlocked) return true
        shakeLockHint()
        showStatusPopup(false, "Locked — Tap 🔒 at top to unlock edits")
        return false
    }

    @Suppress("DEPRECATION")
    private fun launchSystemIntentForResult(intent: Intent, requestCode: Int) {
        isAwaitingSystemIntent = true
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            isAwaitingSystemIntent = false
            showStatusPopup(false, "Unable to open system picker")
        }
    }

    @Suppress("DEPRECATION")
    private fun promptDeviceCredential(requestCode: Int, title: String) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            isEditUnlocked = true
            isAppAuthenticated = true
            if (requestCode == REQ_UNLOCK_SETUP) showSetupMode() else showDashboardMode(playEntrance = true)
            showStatusPopup(true, "Unlocked (no device PIN/biometric set)")
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(
            title,
            "Verify your identity to modify OfficePace records"
        )
        if (intent != null) {
            launchSystemIntentForResult(intent, requestCode)
        } else {
            isEditUnlocked = true
            isAppAuthenticated = true
            if (requestCode == REQ_UNLOCK_SETUP) showSetupMode() else showDashboardMode(playEntrance = true)
        }
    }

    private fun buildAppLockOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GlassUiTheme.ambientBackdrop(isDark)
            setPadding(44, 40, 44, 44)
            visibility = View.GONE

            val outerCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.PRIMARY).second
            val innerCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.PRIMARY).first

            val animStage = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(210, 210).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }

            lockOuterHaloView = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(178, 178).apply {
                    gravity = Gravity.CENTER
                }
                background = GlassUiTheme.roundedShape(GlassUiTheme.statusTint(isDark, "info"), outerCol, 999f, 2)
                alpha = 0.25f
            }

            lockInnerHaloView = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(138, 138).apply {
                    gravity = Gravity.CENTER
                }
                background = GlassUiTheme.roundedShape(GlassUiTheme.statusTint(isDark, "info"), innerCol, 999f, 3)
                alpha = 0.45f
            }

            lockIconView = TextView(this@MainActivity).apply {
                text = "🔒"
                textSize = 50f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            animStage.addView(lockOuterHaloView)
            animStage.addView(lockInnerHaloView)
            animStage.addView(lockIconView)

            val title = TextView(this@MainActivity).apply {
                text = "OfficePace Locked"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cTextPrimary())
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 6)
            }
            val sub = TextView(this@MainActivity).apply {
                text = "Authenticate with Fingerprint, Face, or PIN to open your dashboard."
                textSize = 14.5f
                setTextColor(cTextSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }

            lockStatusCardView = makeCardContainer().apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
            }
            val topStatusLine = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val okCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
            lockStatusDotView = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(18, 18).apply {
                    setMargins(0, 0, 12, 0)
                }
                background = GlassUiTheme.roundedShape(okCol, okCol, 999f, 0)
            }
            lockStatusTitleView = TextView(this@MainActivity).apply {
                text = "Auto-Tracking Running"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(GlassUiTheme.statusSuccessText(isDark))
            }
            topStatusLine.addView(lockStatusDotView)
            topStatusLine.addView(lockStatusTitleView)

            lockStatusSummaryView = TextView(this@MainActivity).apply {
                textSize = GlassUiTheme.cardSubtextSp + 0.5f
                setTextColor(cTextSecondary())
                setPadding(0, 8, 0, 0)
            }
            lockStatusCardView.addView(topStatusLine)
            lockStatusCardView.addView(lockStatusSummaryView)

            val unlockBtn = makePillButton("🔓 Unlock Now", "#4F46E5", GlassUiTheme.textOnAccent) {
                promptDeviceCredential(REQ_UNLOCK_APP, "Unlock OfficePace")
            }

            addView(animStage)
            addView(title)
            addView(sub)
            addView(lockStatusCardView)
            addView(unlockBtn)
        }
    }

    private fun buildSetupWizardView(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 56)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "OfficePace Setup"
            textSize = 25f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
        }
        val subtitle = TextView(this).apply {
            text = "1. Grant permissions → 2. Link backup folder → 3. Confirm profile → 4. Office & policy."
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 16)
        }
        root.addView(title)
        root.addView(subtitle)

        // Section 1: Permissions & Background Readiness
        val permCard = makeCardContainer()
        permCard.addView(makeSectionTitle("1. Permissions & Background Readiness"))
        addSpacer(permCard, 10)

        setupPermStatusView = TextView(this).apply { textSize = GlassUiTheme.cardBodySp; setPadding(0, 3, 0, 3) }
        setupBgLocStatusView = TextView(this).apply { textSize = GlassUiTheme.cardBodySp; setPadding(0, 3, 0, 3) }
        setupGpsStatusView = TextView(this).apply { textSize = GlassUiTheme.cardBodySp; setPadding(0, 3, 0, 3) }
        setupExactAlarmStatusView = TextView(this).apply { textSize = GlassUiTheme.cardBodySp; setPadding(0, 3, 0, 3) }
        setupBatteryStatusView = TextView(this).apply { textSize = GlassUiTheme.cardBodySp; setPadding(0, 3, 0, 12) }

        permCard.addView(setupPermStatusView)
        permCard.addView(setupBgLocStatusView)
        permCard.addView(setupGpsStatusView)
        permCard.addView(setupExactAlarmStatusView)
        permCard.addView(setupBatteryStatusView)

        val permActionRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnGrantPermView = makeTallThumbButton("📍 Location & Alerts", GlassUiTheme.AccentRole.PRIMARY) {
            requestForegroundAndNotifPermissions()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnBgLocView = makeTallThumbButton("🛡️ All-Time Loc", GlassUiTheme.AccentRole.PRIMARY) {
            requestBackgroundLocation()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        permActionRow1.addView(btnGrantPermView)
        addHorizontalGap(permActionRow1, 10)
        permActionRow1.addView(btnBgLocView)
        permCard.addView(permActionRow1)
        addSpacer(permCard, 10)

        val permActionRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnGpsView = makeTallThumbButton("🛰️ GPS Switch", GlassUiTheme.AccentRole.SECONDARY) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnExactAlarmView = makeTallThumbButton("⏰ Exact Alarms", GlassUiTheme.AccentRole.SECONDARY) {
            openExactAlarmSettings()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        permActionRow2.addView(btnGpsView)
        addHorizontalGap(permActionRow2, 10)
        permActionRow2.addView(btnExactAlarmView)
        permCard.addView(permActionRow2)
        addSpacer(permCard, 10)

        btnBatteryView = makeTallThumbButton("🔋 Unrestricted Battery", GlassUiTheme.AccentRole.SUCCESS) {
            requestUnrestrictedBattery()
        }
        permCard.addView(btnBatteryView)
        root.addView(permCard)
        addSpacer(root, 14)

        // Section 2: Auto-Backup Folder
        val backupSetupCard = makeCardContainer()
        backupSetupCard.addView(makeSectionTitle("2. Auto-Backup Folder"))
        setupBackupFolderStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        val pickFolderBtn = makeTallThumbButton("📁 Choose Backup Folder (Auto-Imports CSV)", GlassUiTheme.AccentRole.PRIMARY) {
            launchBackupFolderPicker()
        }
        backupSetupCard.addView(setupBackupFolderStatusView)
        backupSetupCard.addView(pickFolderBtn)
        root.addView(backupSetupCard)
        addSpacer(root, 14)

        // Section 3: Profile Photo, Name & Privacy
        val profileCard = makeCardContainer()
        profileCard.addView(makeSectionTitle("3. Profile Photo, Name & Privacy"))
        addSpacer(profileCard, 12)

        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        setupAvatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true
            isFocusable = true
            setOnClickListener { showFullScreenProfilePhotoDialog() }
        }
        val photoBtnCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickPhotoBtn = makeTallThumbButton("📷 Choose Profile Photo", GlassUiTheme.AccentRole.SECONDARY) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            launchSystemIntentForResult(intent, REQ_PICK_PHOTO)
        }
        val removePhotoBtn = TextView(this).apply {
            text = "Remove Photo"
            textSize = GlassUiTheme.cardSubtextSp + 0.5f
            setTextColor(GlassUiTheme.statusDangerText(isDark))
            setPadding(10, 10, 10, 4)
            setOnClickListener {
                store.deleteProfilePhoto()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Profile photo removed")
            }
        }
        photoBtnCol.addView(pickPhotoBtn)
        photoBtnCol.addView(removePhotoBtn)
        avatarRow.addView(setupAvatarView)
        avatarRow.addView(photoBtnCol)
        profileCard.addView(avatarRow)
        addSpacer(profileCard, 12)

        setupNameInput = EditText(this).apply {
            hint = "Your Name"
            textSize = 15f
            setText(store.getUserName())
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(22, 18, 22, 18)
            background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.bannerRadiusPx)
        }
        profileCard.addView(setupNameInput)
        addSpacer(profileCard, 12)

        setupFullLockSwitch = Switch(this).apply {
            text = "Require Fingerprint/PIN to open app (Full Lock)"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isFullAppLockEnabled()
            setPadding(0, 8, 0, 4)
        }
        profileCard.addView(setupFullLockSwitch)
        root.addView(profileCard)
        addSpacer(root, 14)

        // Section 4: Office Wi-Fi & Geofence Location
        val trackingCard = makeCardContainer()
        trackingCard.addView(makeSectionTitle("4. Office Wi-Fi & Geofence Location"))
        setupLiveWifiView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 10)
        }
        val btnAddLive = makeTallThumbButton("➕ Auto-Detect Connected Wi-Fi", GlassUiTheme.AccentRole.PRIMARY) {
            val live = getConnectedWifiSsid()
            if (live.isEmpty()) {
                showStatusPopup(false, "No connected Wi-Fi detected (check Wi-Fi & Location)")
            } else {
                val existing = setupSsidInput.text.toString().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (existing.none { it.equals(live, ignoreCase = true) }) {
                    existing.add(live)
                    setupSsidInput.setText(existing.joinToString(", "))
                }
                showStatusPopup(true, "Added Wi-Fi SSID '$live'")
            }
        }
        setupSsidInput = EditText(this).apply {
            hint = "Enter Office Wi-Fi SSID(s), comma-separated"
            textSize = 15f
            setText(store.getTargetSsids().joinToString(", "))
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(22, 18, 22, 18)
            background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.bannerRadiusPx)
        }

        setupLocationSummaryView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextPrimary())
            setPadding(0, 12, 0, 10)
        }
        val btnSelectLocSetup = makeTallThumbButton("📍 Select Location (Search / GPS / Maps)", GlassUiTheme.AccentRole.SECONDARY) {
            showSelectLocationDialog()
        }

        trackingCard.addView(setupLiveWifiView)
        trackingCard.addView(btnAddLive)
        addSpacer(trackingCard, 10)
        trackingCard.addView(setupSsidInput)
        trackingCard.addView(setupLocationSummaryView)
        trackingCard.addView(btnSelectLocSetup)
        root.addView(trackingCard)
        addSpacer(root, 14)

        // Section 5: Monthly Policy & Alert Settings
        val policyCard = makeCardContainer()
        policyCard.addView(makeSectionTitle("5. Monthly Policy & Alert Settings"))

        val pctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 10)
        }
        setupTargetPctView = TextView(this).apply {
            textSize = 15.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minusBtn = makeSmallChipButton("− 5%") {
            store.setTargetPercent(store.getTargetPercent() - 5)
            refreshAllViews(animateCharts = false)
            showStatusPopup(true, "Mandate target set to ${store.getTargetPercent()}%")
        }
        val plusBtn = makeSmallChipButton("+ 5%") {
            store.setTargetPercent(store.getTargetPercent() + 5)
            refreshAllViews(animateCharts = false)
            showStatusPopup(true, "Mandate target set to ${store.getTargetPercent()}%")
        }
        pctRow.addView(setupTargetPctView)
        pctRow.addView(minusBtn)
        addHorizontalGap(pctRow, 8)
        pctRow.addView(plusBtn)
        policyCard.addView(pctRow)

        setupRoundUpSwitch = Switch(this).apply {
            text = "Round Up Fractional Days (e.g., 8.4 → 9 days)"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isRoundUpEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupExclHolidaySwitch = Switch(this).apply {
            text = "Exclude Marked Holidays from Target"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isExcludeHolidaysEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupExclLeaveSwitch = Switch(this).apply {
            text = "Exclude Marked Leaves from Target"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isExcludeLeavesEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupConnectAlertSwitch = Switch(this).apply {
            text = "Office Connected Alert (Wi-Fi / Geofence Entry)"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isConnectAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupDisconnectAlertSwitch = Switch(this).apply {
            text = "Office Disconnected Alert (Wi-Fi / Geofence Exit)"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isDisconnectAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupPlanReminderSwitch = Switch(this).apply {
            text = "Planned Office Day Alerts (Eve, Morning, Missed)"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isPlannedRemindersEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupMorningNudgeSwitch = Switch(this).apply {
            text = "Morning Pace Nudge on WFH Days"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isMorningNudgeEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupTargetBufferAlertSwitch = Switch(this).apply {
            text = "Monthly Target Met & Zero Buffer Alerts"
            textSize = 14.5f
            setTextColor(cTextPrimary())
            isChecked = store.isTargetBufferAlertEnabled()
            setPadding(0, 8, 0, 8)
        }
        setupQuarterAlertSwitch = Switch(this).apply {
            text = "Quarter-End Pace Alert"
            textSize = 14.5f
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
        addSpacer(root, 18)

        val saveFinishBtn = makePillButton("✅ Save Settings & Start Auto-Tracker", "#10B981", GlassUiTheme.textOnAccent) {
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
            showDashboardMode(playEntrance = true)
            showStatusPopup(true, "Settings saved & dashboard locked")
        }
        root.addView(saveFinishBtn)
        addSpacer(root, 10)

        val setupUtilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val setupPinWidgetBtn = makeTallThumbButton("📌 Pin Widget", GlassUiTheme.AccentRole.NEUTRAL) {
            requestPinOfficePaceWidget()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val setupHelpBtn = makeTallThumbButton("📖 Setup Guide", GlassUiTheme.AccentRole.SECONDARY) {
            showHelpGuideDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        setupUtilityRow.addView(setupPinWidgetBtn)
        addHorizontalGap(setupUtilityRow, 10)
        setupUtilityRow.addView(setupHelpBtn)
        root.addView(setupUtilityRow)
        addSpacer(root, 14)
        root.addView(makePersonalFooterView())

        return scroll
    }

    private fun buildMainScaffold(): LinearLayout {
        val scaffold = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        topHeaderBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 8)
        }

        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUiTheme.glassCard(isDark, GlassUiTheme.cardRadiusPx)
            setPadding(16, 12, 16, 12)
        }
        dashAvatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(92, 92)
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true
            isFocusable = true
            setOnClickListener { showFullScreenProfilePhotoDialog() }
        }
        val greetingCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dashGreetingView = TextView(this).apply {
            textSize = 19f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 0)
        }
        val okCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
        heartbeatDotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                setMargins(0, 0, 8, 0)
            }
            background = GlassUiTheme.roundedShape(okCol, okCol, 999f, 0)
        }
        dashServiceStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        }
        statusRow.addView(heartbeatDotView)
        statusRow.addView(dashServiceStatusView)

        greetingCol.addView(dashGreetingView)
        greetingCol.addView(statusRow)

        checkNowBtn = makeSmallChipButton("🔄") {
            checkNowBtn.animate().rotationBy(360f).setDuration(500L).setInterpolator(DecelerateInterpolator()).start()
            triggerManualCheckNow()
        }
        lockToggleBtn = makeSmallChipButton("🔒") {
            if (isEditUnlocked) {
                isEditUnlocked = false
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Dashboard locked")
            } else {
                promptDeviceCredential(REQ_UNLOCK_EDIT, "Unlock Edit Mode")
            }
        }

        profileHeader.addView(dashAvatarView)
        profileHeader.addView(greetingCol)
        profileHeader.addView(checkNowBtn)
        addHorizontalGap(profileHeader, 6)
        profileHeader.addView(lockToggleBtn)
        topHeaderBar.addView(profileHeader)

        todayLiveStripView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            gravity = Gravity.CENTER
            setPadding(16, 11, 16, 11)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        }
        topHeaderBar.addView(todayLiveStripView)

        undoBannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "info"))
            setPadding(20, 12, 16, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 0) }
        }
        undoBannerText = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undoActionBtn = TextView(this).apply {
            text = "UNDO"
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(null, Typeface.BOLD)
            setTextColor(GlassUiTheme.statusInfoText(isDark))
            setPadding(16, 8, 12, 8)
            setOnClickListener {
                if (lastUndoBatch.isNotEmpty()) {
                    store.restoreBatchDates(lastUndoBatch)
                    lastUndoBatch = emptyMap()
                    undoBannerRow.visibility = View.GONE
                    syncBackgroundAndWidget()
                    refreshAllViews(animateCharts = true)
                    showStatusPopup(true, "Action undone")
                }
            }
        }
        val dismissUndoBtn = TextView(this).apply {
            text = "✕"
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            setPadding(8, 8, 8, 8)
            setOnClickListener {
                lastUndoBatch = emptyMap()
                undoBannerRow.visibility = View.GONE
            }
        }
        undoBannerRow.addView(undoBannerText)
        undoBannerRow.addView(undoActionBtn)
        undoBannerRow.addView(dismissUndoBtn)
        topHeaderBar.addView(undoBannerRow)

        scaffold.addView(topHeaderBar)

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
            elevation = 0f
            background = GlassUiTheme.glassNavDock(isDark)
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(20, 4, 20, 12) }
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

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHomeTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 6, 24, 44)
        }
        scroll.addView(root)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }
        val prevBtn = makeSmallChipButton("◀") {
            selectedMonth = selectedMonth.minusMonths(1)
            refreshAllViews(animateCharts = true)
            animateCalendarGlide(-1)
        }
        monthTitleView = TextView(this).apply {
            textSize = 20f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            gravity = Gravity.CENTER
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        todayJumpBtn = makeSmallChipButton("Today") {
            selectedMonth = YearMonth.now()
            refreshAllViews(animateCharts = true)
            animateCalendarGlide(1)
        }
        val nextBtn = makeSmallChipButton("▶") {
            selectedMonth = selectedMonth.plusMonths(1)
            refreshAllViews(animateCharts = true)
            animateCalendarGlide(1)
        }
        navRow.addView(prevBtn)
        navRow.addView(monthTitleView)
        navRow.addView(todayJumpBtn)
        addHorizontalGap(navRow, 6)
        navRow.addView(nextBtn)
        root.addView(navRow)

        briefingCardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
        }
        briefingHeadlineView = TextView(this).apply {
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        }
        briefingSubView = TextView(this).apply {
            textSize = 13.5f
            setPadding(0, 4, 0, 8)
            setLineSpacing(3f, 1.0f)
        }
        briefingWeeklyView = TextView(this).apply {
            textSize = 13.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(0, 6, 0, 0)
        }
        briefingRecoveryView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setPadding(0, 6, 0, 0)
            setLineSpacing(3f, 1.0f)
            visibility = View.GONE
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(GlassUiTheme.borderColor(isDark))
            alpha = 0.55f
        }
        briefingCardView.addView(briefingHeadlineView)
        briefingCardView.addView(briefingSubView)
        briefingCardView.addView(divider)
        briefingCardView.addView(briefingWeeklyView)
        briefingCardView.addView(briefingRecoveryView)
        root.addView(briefingCardView)
        addSpacer(root, 12)

        heroCardContainer = makeCardContainer().apply {
            setPadding(24, 18, 24, 18)
        }
        circularRingView = CircularProgressRingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(280, 280).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        heroSubView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 4)
        }
        heroProjectedView = TextView(this).apply {
            textSize = 13.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(GlassUiTheme.statusInfoText(isDark))
            gravity = Gravity.CENTER
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 2, 0, 10) }
        }
        heroCardContainer.addView(circularRingView)
        heroCardContainer.addView(heroSubView)
        heroCardContainer.addView(heroProjectedView)

        val kpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.bannerRadiusPx)
            setPadding(12, 12, 12, 12)
        }
        val (colWorking, numWorking) = makeColorCodedKpiColumn("Working Days")
        val (colRemaining, numRemaining) = makeColorCodedKpiColumn("Still Needed")
        val (colBuffer, numBuffer) = makeColorCodedKpiColumn("WFH Buffer")
        kpiWorkingNumView = numWorking
        kpiRemainingNumView = numRemaining
        kpiBufferNumView = numBuffer
        kpiRow.addView(colWorking)
        kpiRow.addView(colRemaining)
        kpiRow.addView(colBuffer)
        heroCardContainer.addView(kpiRow)
        root.addView(heroCardContainer)
        addSpacer(root, 12)

        calendarCardContainer = makeCardContainer().apply {
            setPadding(22, 18, 22, 18)
        }
        val calHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val calTitle = makeSectionTitle("Attendance & Leave Calendar").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        clearPlanBtn = makeSmallChipButton("Clear Plan") {
            if (ensureEditUnlocked()) {
                val removed = store.clearPlannedDaysForMonth(selectedMonth)
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = true)
                if (removed.isNotEmpty()) {
                    showUndoBanner("Cleared ${removed.size} planned day(s)", removed)
                    showStatusPopup(true, "Cleared ${removed.size} planned day(s) & synced CSV")
                } else {
                    showStatusPopup(false, "No planned days to clear in ${selectedMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}")
                }
            }
        }
        autoPlanBtn = makeSmallChipButton("✨ Auto-Plan") {
            handleAutoPlanClicked()
        }
        calHeaderRow.addView(calTitle)
        calHeaderRow.addView(clearPlanBtn)
        addHorizontalGap(calHeaderRow, 6)
        calHeaderRow.addView(autoPlanBtn)
        calendarCardContainer.addView(calHeaderRow)

        val calHint = TextView(this).apply {
            text = "Swipe left/right for months • Tap day to inspect or mark"
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 10)
        }
        calendarCardContainer.addView(calHint)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        modeOfficeBtn = makeModeChip("🏢 Office") {
            if (ensureEditUnlocked()) {
                activeTapMode = DayType.OFFICE
                animateCellPop(modeOfficeBtn)
                refreshAllViews(animateCharts = false)
            }
        }
        modePlannedBtn = makeModeChip("📅 Plan") {
            if (ensureEditUnlocked()) {
                activeTapMode = DayType.PLANNED
                animateCellPop(modePlannedBtn)
                refreshAllViews(animateCharts = false)
            }
        }
        modeLeaveBtn = makeModeChip("🌴 Leave") {
            if (ensureEditUnlocked()) {
                activeTapMode = DayType.LEAVE
                animateCellPop(modeLeaveBtn)
                refreshAllViews(animateCharts = false)
            }
        }
        modeHolidayBtn = makeModeChip("🎉 Holiday") {
            if (ensureEditUnlocked()) {
                activeTapMode = DayType.HOLIDAY
                animateCellPop(modeHolidayBtn)
                refreshAllViews(animateCharts = false)
            }
        }

        modeRow.addView(modeOfficeBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modePlannedBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modeLeaveBtn)
        addHorizontalGap(modeRow, 6)
        modeRow.addView(modeHolidayBtn)
        calendarCardContainer.addView(modeRow)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var isHorizontalSwipe = false

        val swipeDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) * 1.2f && abs(dx) > 80 && abs(velocityX) > 100) {
                        val dir = if (dx < 0) 1 else -1
                        selectedMonth = if (dx < 0) selectedMonth.plusMonths(1) else selectedMonth.minusMonths(1)
                        refreshAllViews(animateCharts = true)
                        animateCalendarGlide(dir)
                        return true
                    }
                    return false
                }
            }
        )

        calendarContainer = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        isHorizontalSwipe = false
                        swipeDetector.onTouchEvent(ev)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        swipeDetector.onTouchEvent(ev)
                        val dx = abs(ev.x - downX)
                        val dy = abs(ev.y - downY)
                        if (!isHorizontalSwipe && dx > touchSlop && dx > dy * 1.3f) {
                            isHorizontalSwipe = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        isHorizontalSwipe = false
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                val handled = swipeDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    isHorizontalSwipe = false
                }
                return handled || isHorizontalSwipe || super.onTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }
        calendarCardContainer.addView(calendarContainer)

        val legendView = TextView(this).apply {
            text = "🟢 Office  •  🔵 Planned  •  🟠 Leave  •  🟣 Holiday  •  Corner dot = Note"
            textSize = GlassUiTheme.footerTextSp
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        calendarCardContainer.addView(legendView)
        root.addView(calendarCardContainer)
        addSpacer(root, 12)

        val logCard = makeCardContainer().apply {
            setPadding(22, 18, 22, 18)
        }
        logHeaderToggleView = TextView(this).apply {
            text = "Monthly Log & Timestamps   ▼"
            textSize = 16f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            isClickable = true
            isFocusable = true
            setPadding(0, 2, 0, 2)
            setOnClickListener {
                isMonthlyLogExpanded = !isMonthlyLogExpanded
                logExpandBodyView.visibility = if (isMonthlyLogExpanded) View.VISIBLE else View.GONE
                if (isMonthlyLogExpanded) {
                    animateTabReveal(logExpandBodyView)
                }
                refreshAllViews(animateCharts = false)
            }
        }
        logCard.addView(logHeaderToggleView)

        logExpandBodyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isMonthlyLogExpanded) View.VISIBLE else View.GONE
            setPadding(0, 12, 0, 0)
        }

        logSummaryBarView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
            background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.subtleRadiusPx)
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
        }
        logExpandBodyView.addView(logSummaryBarView)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        filterAllBtn = makeModeChip("All") { activeLogFilter = LogFilter.ALL; refreshAllViews(animateCharts = false) }
        filterOfficeBtn = makeModeChip("Office") { activeLogFilter = LogFilter.OFFICE; refreshAllViews(animateCharts = false) }
        filterPlannedBtn = makeModeChip("Planned") { activeLogFilter = LogFilter.PLANNED; refreshAllViews(animateCharts = false) }
        filterLeaveHolBtn = makeModeChip("Leave/Hol") { activeLogFilter = LogFilter.LEAVE_HOLIDAY; refreshAllViews(animateCharts = false) }

        filterRow.addView(filterAllBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterOfficeBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterPlannedBtn)
        addHorizontalGap(filterRow, 6)
        filterRow.addView(filterLeaveHolBtn)
        logExpandBodyView.addView(filterRow)

        auditLogView = TextView(this).apply {
            textSize = 13.5f
            setTextColor(cTextPrimary())
            setLineSpacing(8f, 1.0f)
        }
        logExpandBodyView.addView(auditLogView)
        logCard.addView(logExpandBodyView)
        root.addView(logCard)

        return scroll
    }

    private fun buildAnalyticsTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 48)
        }
        scroll.addView(root)

        val trendCard = makeCardContainer()
        trendCard.addView(makeSectionTitle("6-Month Attendance Trend"))
        trendLegendView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 10)
        }
        trendCard.addView(trendLegendView)

        trendChartView = TrendBarChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                340
            )
        }
        trendCard.addView(trendChartView)
        addSpacer(trendCard, 12)

        runRateFollowThroughView = TextView(this).apply {
            textSize = 13.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            setPadding(0, 4, 0, 4)
        }
        bufferTrajectoryView = TextView(this).apply {
            textSize = 13.5f
            setTextColor(cTextSecondary())
        }
        trendCard.addView(runRateFollowThroughView)
        trendCard.addView(bufferTrajectoryView)
        root.addView(trendCard)
        addSpacer(root, 14)

        val qtdCard = makeCardContainer()
        qtdTitleView = makeSectionTitle("Quarter-to-Date Rollup")
        qtdDetailView = TextView(this).apply {
            textSize = 14.5f
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 6)
        }
        qtdLeaveHolView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextSecondary())
            setPadding(0, 0, 0, 10)
        }
        rhythmView = TextView(this).apply {
            textSize = GlassUiTheme.cardBodySp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(GlassUiTheme.statusInfoText(isDark))
        }
        qtdCard.addView(qtdTitleView)
        qtdCard.addView(qtdDetailView)
        qtdCard.addView(qtdLeaveHolView)
        qtdCard.addView(rhythmView)
        root.addView(qtdCard)
        addSpacer(root, 14)

        val simCard = makeCardContainer()
        val simHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val simTitle = makeSectionTitle("What-If Leave & Holiday Simulator").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val simResetBtn = makeSmallChipButton("Reset") {
            simLeaves = 0
            simHolidays = 0
            refreshAllViews(animateCharts = false)
            showStatusPopup(true, "Simulator reset")
        }
        simHeaderRow.addView(simTitle)
        simHeaderRow.addView(simResetBtn)
        simCard.addView(simHeaderRow)

        val simSub = TextView(this).apply {
            text = "Preview how upcoming leaves or holidays shift your required days:"
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        simCard.addView(simSub)

        val simLeaveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 8)
        }
        simLeaveCountView = TextView(this).apply {
            textSize = 14.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val leaveMinus = makeSmallChipButton("−") {
            simLeaves = maxOf(0, simLeaves - 1)
            refreshAllViews(animateCharts = false)
        }
        val leavePlus = makeSmallChipButton("+") {
            simLeaves = minOf(15, simLeaves + 1)
            refreshAllViews(animateCharts = false)
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
            textSize = 14.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val holMinus = makeSmallChipButton("−") {
            simHolidays = maxOf(0, simHolidays - 1)
            refreshAllViews(animateCharts = false)
        }
        val holPlus = makeSmallChipButton("+") {
            simHolidays = minOf(15, simHolidays + 1)
            refreshAllViews(animateCharts = false)
        }
        simHolRow.addView(simHolidayCountView)
        simHolRow.addView(holMinus)
        addHorizontalGap(simHolRow, 8)
        simHolRow.addView(holPlus)
        simCard.addView(simHolRow)

        simResultView = TextView(this).apply {
            textSize = 13.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(20, 16, 20, 16)
        }
        simCard.addView(simResultView)
        root.addView(simCard)
        addSpacer(root, 14)

        val exportCard = makeCardContainer().apply {
            setPadding(22, 16, 22, 16)
        }
        val copySnapshotBtn = makePillButton("📎 Copy Audit Snapshot", "#0284C7", GlassUiTheme.textOnAccent) {
            val snap = store.buildMonthlySummaryReport(selectedMonth)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OfficePace Audit Snapshot", snap))
            showStatusPopup(true, "Audit snapshot copied to clipboard")
        }
        exportCard.addView(copySnapshotBtn)
        addSpacer(exportCard, 10)

        val shareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val shareMonthBtn = makeTallThumbButton("📋 Monthly Report", GlassUiTheme.AccentRole.PRIMARY) {
            val report = store.buildMonthlySummaryReport(selectedMonth)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OfficePace Attendance Summary - $selectedMonth")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share Monthly Summary"))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val shareYearBtn = makeTallThumbButton("🏆 ${selectedMonth.year} Review", GlassUiTheme.AccentRole.NEUTRAL) {
            val report = store.buildAnnualSummaryReport(selectedMonth.year)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OfficePace Annual Summary - ${selectedMonth.year}")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share Annual Summary"))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        shareRow.addView(shareMonthBtn)
        addHorizontalGap(shareRow, 10)
        shareRow.addView(shareYearBtn)
        exportCard.addView(shareRow)
        root.addView(exportCard)

        return scroll
    }

    private fun buildSettingsTab(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 48)
        }
        scroll.addView(root)

        val themeCard = makeCardContainer()
        themeCard.addView(makeSectionTitle("Appearance & Theme"))
        activeThemeStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 10)
        }
        themeCard.addView(activeThemeStatusView)

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        themeSystemBtn = makeModeChip("🌗 System") { setThemeChoiceAndApply(ThemeChoice.SYSTEM) }
        themeLightBtn = makeModeChip("☀️ Light") { setThemeChoiceAndApply(ThemeChoice.LIGHT) }
        themeDarkBtn = makeModeChip("🌙 Dark") { setThemeChoiceAndApply(ThemeChoice.DARK) }
        themeRow.addView(themeSystemBtn)
        addHorizontalGap(themeRow, 6)
        themeRow.addView(themeLightBtn)
        addHorizontalGap(themeRow, 6)
        themeRow.addView(themeDarkBtn)
        themeCard.addView(themeRow)
        root.addView(themeCard)
        addSpacer(root, 14)

        val modeCard = makeCardContainer()
        modeCard.addView(makeSectionTitle("Tracking Mode"))
        val modeSelectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 8)
        }
        modeWifiOnlyBtn = makeModeChip("📶 Wi-Fi") {
            if (ensureEditUnlocked()) {
                store.setTrackingMode(TrackingMode.WIFI_ONLY)
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Tracking mode set to Wi-Fi Only")
            }
        }
        modeLocOnlyBtn = makeModeChip("📍 Location") {
            if (ensureEditUnlocked()) {
                store.setTrackingMode(TrackingMode.LOCATION_ONLY)
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Tracking mode set to Location Only")
            }
        }
        modeBothBtn = makeModeChip("📶+📍 Both") {
            if (ensureEditUnlocked()) {
                store.setTrackingMode(TrackingMode.BOTH)
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Tracking mode set to Wi-Fi + Location")
            }
        }
        modeSelectorRow.addView(modeWifiOnlyBtn)
        addHorizontalGap(modeSelectorRow, 6)
        modeSelectorRow.addView(modeLocOnlyBtn)
        addHorizontalGap(modeSelectorRow, 6)
        modeSelectorRow.addView(modeBothBtn)
        modeCard.addView(modeSelectorRow)

        trackingModeHintView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 0)
        }
        modeCard.addView(trackingModeHintView)
        root.addView(modeCard)
        addSpacer(root, 14)

        locationSectionCard = makeCardContainer()
        locationSectionCard.addView(makeSectionTitle("Office Geofence Location"))
        selectedOfficeView = TextView(this).apply {
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            setPadding(0, 8, 0, 4)
        }
        officeDistanceView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 0, 0, 12)
        }
        locationSectionCard.addView(selectedOfficeView)
        locationSectionCard.addView(officeDistanceView)

        val selectLocBtn = makePillButton("🗺️ Select Location (Search / GPS / Maps)", "#0284C7", GlassUiTheme.textOnAccent) {
            if (ensureEditUnlocked()) {
                showSelectLocationDialog()
            }
        }
        locationSectionCard.addView(selectLocBtn)

        val radiusLabel = TextView(this).apply {
            text = "Geofence Radius:"
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextSecondary())
            setPadding(0, 14, 0, 8)
        }
        locationSectionCard.addView(radiusLabel)
        locationSectionCard.addView(buildRadiusSelectorRow())

        root.addView(locationSectionCard)
        addSpacer(root, 14)

        val priorityCard = makeCardContainer()
        priorityCard.addView(makeSectionTitle("Auto-Plan Preferred Weekdays (Priority Order)"))
        val prioHint = TextView(this).apply {
            text = "Tap a weekday to assign/cycle its priority badge (1, 2, 3...) for ✨ Auto-Plan:"
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 10)
        }
        priorityCard.addView(prioHint)
        priorityWeekdaysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        priorityCard.addView(priorityWeekdaysContainer)
        priorityOrderPreviewView = TextView(this).apply {
            textSize = 13.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(GlassUiTheme.statusInfoText(isDark))
            setPadding(0, 10, 0, 0)
        }
        priorityCard.addView(priorityOrderPreviewView)
        root.addView(priorityCard)
        addSpacer(root, 14)

        wifiSectionCard = makeCardContainer()
        wifiSectionCard.addView(makeSectionTitle("Office Wi-Fi Auto-Detection"))
        currentWifiView = TextView(this).apply {
            textSize = 13.5f
            setTextColor(cTextSecondary())
            setPadding(0, 6, 0, 12)
        }
        wifiSectionCard.addView(currentWifiView)

        wifiChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiSectionCard.addView(wifiChipsContainer)
        addSpacer(wifiSectionCard, 10)

        val addCurrentWifiBtn = makePillButton("➕ Add Connected Wi-Fi", "#4F46E5", GlassUiTheme.textOnAccent) {
            if (ensureEditUnlocked()) {
                val liveSsid = getConnectedWifiSsid()
                if (liveSsid.isEmpty()) {
                    showStatusPopup(false, "Connect to office Wi-Fi & turn ON Location first")
                } else {
                    val added = store.addTargetSsid(liveSsid)
                    syncBackgroundAndWidget()
                    refreshAllViews(animateCharts = false)
                    if (added) {
                        showStatusPopup(true, "Added '$liveSsid' to office Wi-Fi list")
                    } else {
                        showStatusPopup(false, "'$liveSsid' is already in your Wi-Fi list")
                    }
                }
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
            textSize = 14.5f
            setTextColor(cTextPrimary())
            setHintTextColor(cTextSecondary())
            setPadding(20, 18, 20, 18)
            background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.subtleRadiusPx)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addManualSsidBtn = makeSmallChipButton("+ Add") {
            if (ensureEditUnlocked()) {
                val typed = ssidInput.text.toString().trim()
                if (typed.isEmpty()) {
                    showStatusPopup(false, "Enter a Wi-Fi SSID before adding")
                } else {
                    typed.split(",").forEach { store.addTargetSsid(it) }
                    ssidInput.setText("")
                    syncBackgroundAndWidget()
                    refreshAllViews(animateCharts = false)
                    showStatusPopup(true, "Saved Wi-Fi SSID")
                }
            }
        }
        manualWifiRow.addView(ssidInput)
        addHorizontalGap(manualWifiRow, 10)
        manualWifiRow.addView(addManualSsidBtn)
        wifiSectionCard.addView(manualWifiRow)
        root.addView(wifiSectionCard)
        addSpacer(root, 14)

        val notifCard = makeCardContainer()
        val notifHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, 2, 0, 2)
        }
        val notifTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val notifTitleTv = makeSectionTitle("Notifications & Exact Alarm Status")
        notifSummaryTv = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 2, 0, 0)
        }
        notifTitleCol.addView(notifTitleTv)
        notifTitleCol.addView(notifSummaryTv)

        notifArrowTv = TextView(this).apply {
            text = "▼"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextSecondary())
            setPadding(12, 4, 4, 4)
        }
        notifHeaderRow.addView(notifTitleCol)
        notifHeaderRow.addView(notifArrowTv)
        notifCard.addView(notifHeaderRow)

        notifBodyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 10, 0, 0)
        }

        settingsNotifPermStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(4, 0, 4, 8)
        }
        notifBodyView.addView(settingsNotifPermStatusView)

        settingsExactAlarmStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(16, 12, 16, 12)
        }
        notifBodyView.addView(settingsExactAlarmStatusView)
        addSpacer(notifBodyView, 8)

        val openAlarmBtn = makeSmallChipButton("⏰ Check / Open Exact Alarm Settings") {
            openExactAlarmSettings()
        }
        notifBodyView.addView(openAlarmBtn)
        addSpacer(notifBodyView, 8)

        notifConnectSwitch = makeSettingsSwitch("Connect Alert (Day Captured)") { checked ->
            store.setConnectAlertEnabled(checked)
            showStatusPopup(true, "Connect alert ${if (checked) "enabled" else "disabled"}")
        }
        notifDisconnectSwitch = makeSettingsSwitch("Disconnect Alert (Left Wi-Fi / Zone)") { checked ->
            store.setDisconnectAlertEnabled(checked)
            showStatusPopup(true, "Disconnect alert ${if (checked) "enabled" else "disabled"}")
        }
        notifPlannedSwitch = makeSettingsSwitch("Planned Day Alerts (Eve, Morning, Missed)") { checked ->
            store.setPlannedRemindersEnabled(checked)
            showStatusPopup(true, "Planned day alerts ${if (checked) "enabled" else "disabled"}")
        }
        notifMorningSwitch = makeSettingsSwitch("Morning Pace Nudge on WFH Days") { checked ->
            store.setMorningNudgeEnabled(checked)
            showStatusPopup(true, "Morning pace nudge ${if (checked) "enabled" else "disabled"}")
        }
        notifTargetBufferSwitch = makeSettingsSwitch("Monthly Target Met & Zero Buffer Alerts") { checked ->
            store.setTargetBufferAlertEnabled(checked)
            showStatusPopup(true, "Target & buffer alerts ${if (checked) "enabled" else "disabled"}")
        }
        notifQuarterSwitch = makeSettingsSwitch("Quarter-End Pace Alert") { checked ->
            store.setQuarterPaceAlertEnabled(checked)
            showStatusPopup(true, "Quarter-end pace alert ${if (checked) "enabled" else "disabled"}")
        }

        notifBodyView.addView(notifConnectSwitch)
        notifBodyView.addView(notifDisconnectSwitch)
        notifBodyView.addView(notifPlannedSwitch)
        notifBodyView.addView(notifMorningSwitch)
        notifBodyView.addView(notifTargetBufferSwitch)
        notifBodyView.addView(notifQuarterSwitch)
        notifCard.addView(notifBodyView)

        notifHeaderRow.setOnClickListener {
            isNotificationsSectionExpanded = !notifBodyView.isShown
            syncCollapsibleSections()
            if (notifBodyView.isShown) animateTabReveal(notifBodyView)
        }
        root.addView(notifCard)
        addSpacer(root, 14)

        val holidayCard = makeCardContainer()
        val holHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, 2, 0, 2)
        }
        val holTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val holTitle = makeSectionTitle("Upcoming Holidays & Leaves")
        holidaySummaryTv = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 2, 0, 0)
        }
        holTitleCol.addView(holTitle)
        holTitleCol.addView(holidaySummaryTv)

        holidayArrowTv = TextView(this).apply {
            text = "▼"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cTextSecondary())
            setPadding(12, 4, 4, 4)
        }
        holHeaderRow.addView(holTitleCol)
        holHeaderRow.addView(holidayArrowTv)
        holidayCard.addView(holHeaderRow)

        holidayBodyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 10, 0, 0)
        }

        val clearUpcomingBtn = makeSmallChipButton("Clear Upcoming") {
            if (ensureEditUnlocked()) {
                val removedBatch = store.clearUpcomingHolidaysAndLeaves()
                if (removedBatch.isNotEmpty()) {
                    syncBackgroundAndWidget()
                    refreshAllViews(animateCharts = true)
                    showUndoBanner("Cleared ${removedBatch.size} upcoming entry(s)", removedBatch)
                    showStatusPopup(true, "Cleared ${removedBatch.size} upcoming holiday/leave entry(s)")
                } else {
                    showStatusPopup(false, "No upcoming holidays or leaves to clear")
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END }
        }
        holidayBodyView.addView(clearUpcomingBtn)

        upcomingHolLeaveSummaryView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 10) }
        }
        holidayBodyView.addView(upcomingHolLeaveSummaryView)

        upcomingHolLeaveListView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        holidayBodyView.addView(upcomingHolLeaveListView)
        addSpacer(holidayBodyView, 10)

        val holHint = TextView(this).apply {
            text = "Import an .ics calendar file or CSV (YYYY-MM-DD,Holiday Name) to mark holidays across the year."
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 2, 0, 10)
        }
        val importHolBtn = makeTallThumbButton("🎉 Import Holidays (.ics / CSV)", GlassUiTheme.AccentRole.SPECIAL) {
            if (ensureEditUnlocked()) {
                launchHolidayImport()
            }
        }
        holidayBodyView.addView(holHint)
        holidayBodyView.addView(importHolBtn)
        holidayCard.addView(holidayBodyView)

        holHeaderRow.setOnClickListener {
            isUpcomingHolidaysSectionExpanded = !holidayBodyView.isShown
            syncCollapsibleSections()
            if (holidayBodyView.isShown) animateTabReveal(holidayBodyView)
        }
        root.addView(holidayCard)
        addSpacer(root, 14)

        val backupCard = makeCardContainer()
        backupCard.addView(makeSectionTitle("Backup, Widget & Data Portability"))
        autoBackupStatusView = TextView(this).apply {
            textSize = GlassUiTheme.cardSubtextSp
            setPadding(0, 6, 0, 12)
        }
        backupCard.addView(autoBackupStatusView)

        val backupTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pinWidgetBtn = makeTallThumbButton("📌 Pin Widget", GlassUiTheme.AccentRole.PRIMARY) {
            requestPinOfficePaceWidget()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val autoFolderBtn = makeTallThumbButton("🔄 Backup Folder", GlassUiTheme.AccentRole.SECONDARY) {
            if (ensureEditUnlocked()) {
                launchBackupFolderPicker()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        backupTopRow.addView(pinWidgetBtn)
        addHorizontalGap(backupTopRow, 10)
        backupTopRow.addView(autoFolderBtn)
        backupCard.addView(backupTopRow)
        addSpacer(backupCard, 10)

        val backupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val exportBtn = makeTallThumbButton("💾 Save CSV", GlassUiTheme.AccentRole.NEUTRAL) {
            launchCsvExport()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val importBtn = makeTallThumbButton("📂 Restore CSV", GlassUiTheme.AccentRole.PRIMARY) {
            if (ensureEditUnlocked()) {
                launchCsvImport()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        backupRow.addView(exportBtn)
        addHorizontalGap(backupRow, 10)
        backupRow.addView(importBtn)
        backupCard.addView(backupRow)
        root.addView(backupCard)
        addSpacer(root, 14)

        val bottomActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val setupWizardBtn = makeTallThumbButton("🛠️ Policy & Profile", GlassUiTheme.AccentRole.PRIMARY) {
            if (isEditUnlocked) {
                showSetupMode()
            } else {
                promptDeviceCredential(REQ_UNLOCK_SETUP, "Unlock Policy & Profile Settings")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val helpGuideBtn = makeTallThumbButton("📖 How It Works", GlassUiTheme.AccentRole.SECONDARY) {
            showHelpGuideDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomActionsRow.addView(setupWizardBtn)
        addHorizontalGap(bottomActionsRow, 10)
        bottomActionsRow.addView(helpGuideBtn)
        root.addView(bottomActionsRow)
        addSpacer(root, 14)
        root.addView(makePersonalFooterView())

        return scroll
    }

    private fun syncCollapsibleSections() {
        val items = store.getUpcomingHolidaysAndLeaves()
        val holCount = items.count { it.type == DayType.HOLIDAY }
        val leaveCount = items.count { it.type == DayType.LEAVE }

        if (::notifBodyView.isInitialized && ::notifSummaryTv.isInitialized && ::notifArrowTv.isInitialized) {
            val shouldExpandNotif = isNotificationsSectionExpanded || !canScheduleExactAlarms()
            notifBodyView.visibility = if (shouldExpandNotif) View.VISIBLE else View.GONE
            notifArrowTv.text = if (shouldExpandNotif) "▲" else "▼"
            val enabledCount = listOf(
                store.isConnectAlertEnabled(),
                store.isDisconnectAlertEnabled(),
                store.isPlannedRemindersEnabled(),
                store.isMorningNudgeEnabled(),
                store.isTargetBufferAlertEnabled(),
                store.isQuarterPaceAlertEnabled()
            ).count { it }
            val alarmTag = if (canScheduleExactAlarms()) "Exact Alarms Ready" else "Exact Alarm Check Needed"
            notifSummaryTv.text = "$enabledCount/6 alerts active • $alarmTag"
        }

        if (::holidayBodyView.isInitialized && ::holidaySummaryTv.isInitialized && ::holidayArrowTv.isInitialized) {
            holidayBodyView.visibility = if (isUpcomingHolidaysSectionExpanded) View.VISIBLE else View.GONE
            holidayArrowTv.text = if (isUpcomingHolidaysSectionExpanded) "▲" else "▼"
            holidaySummaryTv.text = "$holCount holiday(s) • $leaveCount leave(s)"
        }
    }

    private fun showHelpGuideDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val outerFrame = FrameLayout(this).apply {
            setPadding(24, 36, 24, 36)
        }

        val sheetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUiTheme.glassNavDock(isDark)
            setPadding(24, 22, 24, 22)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 14)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sheetTitle = TextView(this).apply {
            text = "📖 OfficePace Guide"
            textSize = 20f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
        }
        val sheetSub = TextView(this).apply {
            text = "How auto-tracking, monthly rules, alerts & backups work"
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 2, 0, 0)
        }
        titleCol.addView(sheetTitle)
        titleCol.addView(sheetSub)

        val topCloseBtn = makeSmallChipButton("✕") {
            dialog.dismiss()
        }.apply {
            minHeight = 84
            setPadding(22, 10, 22, 10)
        }
        headerRow.addView(titleCol)
        headerRow.addView(topCloseBtn)
        sheetContainer.addView(headerRow)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val bodyContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }
        scrollView.addView(bodyContent)

        val permsReady = hasLocationPermission() && hasBackgroundLocationPermission() && isSystemLocationEnabled()
        val backupHealthy = store.getBackupHealthInfo().healthy
        val readinessCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUiTheme.glassCard(
                isDark,
                GlassUiTheme.bannerRadiusPx,
                GlassUiTheme.statusTint(isDark, if (permsReady && backupHealthy) "success" else "warning")
            )
            setPadding(22, 16, 22, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
        }
        val readinessTitle = TextView(this).apply {
            text = if (permsReady && backupHealthy) {
                "✅ System Ready: Auto-Tracking & Backup Active"
            } else {
                "⚡ Quick Readiness Check"
            }
            textSize = 14.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(
                if (permsReady && backupHealthy) GlassUiTheme.statusSuccessText(isDark)
                else GlassUiTheme.statusWarningText(isDark)
            )
        }
        val readinessSub = TextView(this).apply {
            val pTxt = if (permsReady) "Permissions OK" else "Location / Background Check Needed"
            val bTxt = if (backupHealthy) "Backup Synced" else "Auto-Backup Folder Not Linked"
            text = "$pTxt   •   $bTxt"
            textSize = GlassUiTheme.cardSubtextSp
            setTextColor(cTextSecondary())
            setPadding(0, 4, 0, 0)
        }
        readinessCard.addView(readinessTitle)
        readinessCard.addView(readinessSub)

        if (!permsReady || !backupHealthy) {
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 0)
            }
            if (!permsReady) {
                val permShortcut = makeTallThumbButton("🛡️ Fix Permissions", GlassUiTheme.AccentRole.PRIMARY) {
                    dialog.dismiss()
                    if (isEditUnlocked) showSetupMode() else promptDeviceCredential(REQ_UNLOCK_SETUP, "Unlock Setup")
                }.apply {
                    minHeight = 96
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                actionRow.addView(permShortcut)
                if (!backupHealthy) addHorizontalGap(actionRow, 8)
            }
            if (!backupHealthy) {
                val backupShortcut = makeTallThumbButton("📁 Link Backup", GlassUiTheme.AccentRole.SECONDARY) {
                    dialog.dismiss()
                    if (ensureEditUnlocked()) launchBackupFolderPicker()
                }.apply {
                    minHeight = 96
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                actionRow.addView(backupShortcut)
            }
            readinessCard.addView(actionRow)
        }
        bodyContent.addView(readinessCard)

        val learnedWindow = store.getLearnedArrivalWindowLabel()
        val lateCheckFormatted = store.getPlannedNotArrivedCheckTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val activeRadius = store.getGeofenceRadiusMeters()
        val targetPct = store.getTargetPercent()

        val sections = listOf(
            GuideSectionSpec(
                title = "1. Auto-Tracking & Shift Learning",
                subtitle = "Background Wi-Fi/GPS detection & learned window ($learnedWindow)",
                accentRole = GlassUiTheme.AccentRole.PRIMARY,
                bullets = listOf(
                    "Background Auto-Tracker" to "Runs silently in the background once Location permission is allowed.",
                    "Flexible Matching" to "Detects your office automatically via Office Wi-Fi, GPS Geofence (${activeRadius}m), or Both.",
                    "Smart Arrival & Exit Logs" to "Captures arrival time, refreshes active presence, and logs departure when you leave.",
                    "Learned Shift Window ($learnedWindow)" to "Adapts to your typical arrival pattern automatically with no manual shift setup."
                ),
                actionLabel = "🔄 Run Live Presence Check",
                onAction = {
                    dialog.dismiss()
                    triggerManualCheckNow()
                }
            ),
            GuideSectionSpec(
                title = "2. Monthly Mandate & Smart Auto-Plan",
                subtitle = "$targetPct%/month target • 1-hour buffer ($lateCheckFormatted) • Priority scheduling",
                accentRole = GlassUiTheme.AccentRole.SUCCESS,
                bullets = listOf(
                    "Planned + 1h Buffer ($lateCheckFormatted)" to "Checks Planned Office days 1 hour after your typical arrival window so traffic or errands never trigger early alerts.",
                    "Strictly Monthly Mandate" to "Calculated per calendar month ($targetPct%) with zero quarterly carryover.",
                    "Priority Auto-Plan & Undo" to "Schedules remaining needed days using your preferred weekday order, clears stale plan notes cleanly, and supports instant UNDO."
                ),
                actionLabel = "📅 Open Calendar & Plan",
                onAction = {
                    dialog.dismiss()
                    switchTab(ActiveTab.HOME)
                }
            ),
            GuideSectionSpec(
                title = "3. Silent vs. Audible Alerts",
                subtitle = "Quiet background status • Sound & vibration only for key events",
                accentRole = GlassUiTheme.AccentRole.WARNING,
                bullets = listOf(
                    "Silent Background Status" to "Ongoing tracker status and passive daily nudges stay quiet without sound or vibration.",
                    "Audible Action Alerts" to "Chimes and vibrates when office attendance is captured, on exit/disconnect, or if a planned day isn't logged past the 1-hour buffer.",
                    "Unplanned Office Bonus" to "Celebrates extra unscheduled office days once per day without reshuffling your existing plans.",
                    "Background Readiness" to "Keep Location (Allow all the time), GPS ON, Exact Alarms, and Unrestricted Battery enabled."
                ),
                actionLabel = "🔔 Review Alert Settings",
                onAction = {
                    dialog.dismiss()
                    isNotificationsSectionExpanded = true
                    switchTab(ActiveTab.SETTINGS)
                }
            ),
            GuideSectionSpec(
                title = "4. Full Backup, Profile Photo & Widget",
                subtitle = "Auto-synced CSV + dual-resolution avatar + Home Screen Glance",
                accentRole = GlassUiTheme.AccentRole.SECONDARY,
                bullets = listOf(
                    "Protected Restore Overlay" to "Freezes touches during folder restore or CSV import until all logs and preferences are safely applied.",
                    "Automatic Backup Sync" to "Saves officepace_attendance_autobackup.csv plus compressed and high-resolution profile photos whenever data changes.",
                    "Full Setup & Priority Restore" to "Restores your name, full lock setting, Wi-Fi SSIDs, office zone (${activeRadius}m), policy rules, weekday priorities, and alert preferences.",
                    "Home Screen Glance Widget" to "Shows today's live status, monthly progress bar, underlined weekday strip, and distance inside your ${activeRadius}m office zone."
                ),
                actionLabel = "📌 Pin Home Screen Widget",
                onAction = {
                    dialog.dismiss()
                    requestPinOfficePaceWidget()
                }
            )
        )

        sections.forEachIndexed { idx, spec ->
            val accentColor = GlassUiTheme.accentColors(isDark, spec.accentRole).second
            val isExpandedInitially = idx == 0

            val cardWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx < sections.lastIndex) setMargins(0, 0, 0, 12)
                }
            }

            val accentPill = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    8,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(14, 18, 0, 18)
                }
                background = GlassUiTheme.roundedShape(accentColor, accentColor, 999f, 0)
            }
            cardWrapper.addView(accentPill)

            val cardBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val sectionHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
            }
            val sectionTitleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val headerTv = TextView(this).apply {
                text = spec.title
                textSize = 15.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setTextColor(accentColor)
            }
            val subTv = TextView(this).apply {
                text = spec.subtitle
                textSize = 12.5f
                setTextColor(cTextSecondary())
                setPadding(0, 2, 0, 0)
            }
            sectionTitleCol.addView(headerTv)
            sectionTitleCol.addView(subTv)

            val arrowTv = TextView(this).apply {
                text = if (isExpandedInitially) "▲" else "▼"
                textSize = GlassUiTheme.tonalButtonSp
                setTypeface(null, Typeface.BOLD)
                setTextColor(cTextSecondary())
                setPadding(12, 4, 2, 4)
            }
            sectionHeaderRow.addView(sectionTitleCol)
            sectionHeaderRow.addView(arrowTv)
            cardBody.addView(sectionHeaderRow)

            val detailsBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (isExpandedInitially) View.VISIBLE else View.GONE
                setPadding(0, 10, 0, 2)
            }

            spec.bullets.forEachIndexed { bIdx, (lead, desc) ->
                val bulletRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, if (bIdx == 0) 2 else 10, 0, 0)
                }
                val dotTv = TextView(this).apply {
                    text = "•  "
                    textSize = GlassUiTheme.tonalButtonSp
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentColor)
                }

                val fullLine = "$lead: $desc"
                val spannable = SpannableString(fullLine).apply {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        lead.length + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                val textTv = TextView(this).apply {
                    text = spannable
                    textSize = 13.5f
                    setTextColor(cTextPrimary())
                    setLineSpacing(4f, 1.0f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                bulletRow.addView(dotTv)
                bulletRow.addView(textTv)
                detailsBox.addView(bulletRow)
            }

            if (spec.actionLabel != null && spec.onAction != null) {
                addSpacer(detailsBox, 12)
                val quickBtn = makeTallThumbButton(spec.actionLabel, spec.accentRole) {
                    spec.onAction.invoke()
                }.apply {
                    minHeight = 96
                    setPadding(16, 12, 16, 12)
                }
                detailsBox.addView(quickBtn)
            }

            cardBody.addView(detailsBox)

            sectionHeaderRow.setOnClickListener {
                val willExpand = detailsBox.visibility != View.VISIBLE
                detailsBox.visibility = if (willExpand) View.VISIBLE else View.GONE
                arrowTv.text = if (willExpand) "▲" else "▼"
                if (willExpand) {
                    animateTabReveal(detailsBox)
                }
            }

            cardWrapper.addView(cardBody)
            bodyContent.addView(cardWrapper)
        }

        sheetContainer.addView(scrollView)
        addSpacer(sheetContainer, 12)

        val gotItBtn = makePillButton("✅ Got It", "#0284C7", GlassUiTheme.textOnAccent) {
            dialog.dismiss()
        }
        sheetContainer.addView(gotItBtn)

        outerFrame.addView(sheetContainer)
        dialog.setContentView(outerFrame)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun buildRadiusSelectorRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            radius250Btn = makeModeChip("250m") { applyRadiusSelection(250) }
            radius500Btn = makeModeChip("500m") { applyRadiusSelection(500) }
            radius1000Btn = makeModeChip("1000m") { applyRadiusSelection(1000) }
            radius1500Btn = makeModeChip("1500m") { applyRadiusSelection(1500) }
            addView(radius250Btn)
            addHorizontalGap(this, 6)
            addView(radius500Btn)
            addHorizontalGap(this, 6)
            addView(radius1000Btn)
            addHorizontalGap(this, 6)
            addView(radius1500Btn)
        }
    }

    private fun syncRadiusChipHighlights() {
        val radius = store.getGeofenceRadiusMeters()
        if (::radius250Btn.isInitialized) styleModeChip(radius250Btn, radius == 250, "#0284C7")
        if (::radius500Btn.isInitialized) styleModeChip(radius500Btn, radius == 500, "#0284C7")
        if (::radius1000Btn.isInitialized) styleModeChip(radius1000Btn, radius == 1000, "#0284C7")
        if (::radius1500Btn.isInitialized) styleModeChip(radius1500Btn, radius == 1500, "#0284C7")
    }

    private fun renderPriorityWeekdays() {
        if (!::priorityWeekdaysContainer.isInitialized) return
        priorityWeekdaysContainer.removeAllViews()
        val ordered = store.getPreferredWeekdaysPriority()
        val weekdays = listOf(
            DayOfWeek.MONDAY to "Mon",
            DayOfWeek.TUESDAY to "Tue",
            DayOfWeek.WEDNESDAY to "Wed",
            DayOfWeek.THURSDAY to "Thu",
            DayOfWeek.FRIDAY to "Fri"
        )
        weekdays.forEachIndexed { idx, (dow, shortLabel) ->
            val rankIndex = ordered.indexOf(dow)
            val label = if (rankIndex >= 0) "${rankIndex + 1} • $shortLabel" else shortLabel
            val chip = makeModeChip(label) {
                if (ensureEditUnlocked()) {
                    store.cycleWeekdayPriority(dow)
                    refreshAllViews(animateCharts = false)
                    val updatedOrder = store.getPreferredWeekdaysPriority()
                    val newIdx = updatedOrder.indexOf(dow)
                    if (newIdx >= 0) {
                        showStatusPopup(true, "$shortLabel set to priority #${newIdx + 1}")
                    } else {
                        showStatusPopup(true, "$shortLabel removed from priority list")
                    }
                }
            }
            styleModeChip(chip, rankIndex >= 0, "#4F46E5")
            priorityWeekdaysContainer.addView(chip)
            if (idx < weekdays.lastIndex) addHorizontalGap(priorityWeekdaysContainer, 5)
        }

        if (::priorityOrderPreviewView.isInitialized) {
            val sequenceLabel = ordered.joinToString(" → ") {
                it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            priorityOrderPreviewView.text = "Auto-Plan order: $sequenceLabel"
        }
    }

    private fun renderUpcomingHolidaysAndLeaves() {
        if (!::upcomingHolLeaveSummaryView.isInitialized || !::upcomingHolLeaveListView.isInitialized) return
        val items = store.getUpcomingHolidaysAndLeaves()
        val holCount = items.count { it.type == DayType.HOLIDAY }
        val leaveCount = items.count { it.type == DayType.LEAVE }

        upcomingHolLeaveSummaryView.text = "🎉 $holCount Upcoming Holiday(s)   •   🌴 $leaveCount Upcoming Leave(s)"
        upcomingHolLeaveSummaryView.setTextColor(cTextPrimary())
        upcomingHolLeaveSummaryView.background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.subtleRadiusPx)

        upcomingHolLeaveListView.removeAllViews()
        if (items.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No upcoming holidays or leaves marked."
                textSize = GlassUiTheme.cardSubtextSp
                setTextColor(cTextSecondary())
                setPadding(4, 2, 4, 8)
            }
            upcomingHolLeaveListView.addView(emptyTv)
            return
        }

        val capped = items.take(8)
        capped.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.cellRadiusPx)
                setPadding(16, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 6) }
            }
            val icon = if (entry.type == DayType.HOLIDAY) "🎉" else "🌴"
            val labelTv = TextView(this).apply {
                text = "$icon ${entry.dateStr} (${entry.dayOfWeekShort}) • ${entry.detail}"
                textSize = GlassUiTheme.cardSubtextSp
                setTextColor(cTextPrimary())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(GlassUiTheme.statusDangerText(isDark))
                setPadding(16, 6, 8, 6)
                setOnClickListener {
                    if (ensureEditUnlocked()) {
                        val parsedDate = runCatching { LocalDate.parse(entry.dateStr) }.getOrNull()
                        if (parsedDate != null) {
                            val prevType = store.getDayType(parsedDate)
                            store.setExplicitDayType(parsedDate, DayType.NONE)
                            syncBackgroundAndWidget()
                            refreshAllViews(animateCharts = true)
                            showUndoBanner("Removed ${entry.dateStr}", mapOf(parsedDate to prevType))
                            showStatusPopup(true, "Removed ${entry.dateStr}")
                        }
                    }
                }
            }
            row.addView(labelTv)
            row.addView(delBtn)
            upcomingHolLeaveListView.addView(row)
        }

        if (items.size > capped.size) {
            val moreTv = TextView(this).apply {
                text = "+ ${items.size - capped.size} more upcoming entry(s) in calendar"
                textSize = GlassUiTheme.footerTextSp
                setTextColor(cTextSecondary())
                setPadding(4, 2, 4, 6)
            }
            upcomingHolLeaveListView.addView(moreTv)
        }
    }

    private fun makeSettingsSwitch(label: String, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = label
            textSize = GlassUiTheme.cardBodySp
            setTextColor(cTextPrimary())
            setPadding(0, 10, 0, 10)
            setOnCheckedChangeListener { view, checked ->
                if (!view.isPressed) return@setOnCheckedChangeListener
                if (!isEditUnlocked) {
                    isChecked = !checked
                    shakeLockHint()
                    showStatusPopup(false, "Locked — Tap 🔒 at top to unlock edits")
                    return@setOnCheckedChangeListener
                }
                onToggle(checked)
                refreshAllViews(animateCharts = false)
            }
        }
    }

    private fun applyRadiusSelection(meters: Int) {
        if (ensureEditUnlocked()) {
            store.setGeofenceRadiusMeters(meters)
            syncBackgroundAndWidget()
            refreshAllViews(animateCharts = false)
            showStatusPopup(true, "Geofence radius updated to ${meters}m")
        }
    }

    @Suppress("DEPRECATION")
    private fun showSelectLocationDialog() {
        var dialogRef: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 16)
        }

        val nameInput = EditText(this).apply {
            hint = "Office / Place Name"
            textSize = 15f
            setText(store.getOfficeLocationName())
            setPadding(20, 16, 20, 16)
        }
        container.addView(nameInput)
        addSpacer(container, 8)

        val coordOrLinkInput = EditText(this).apply {
            hint = "Coordinates (lat, lng) or Google Maps link"
            textSize = 15f
            if (store.hasOfficeLocationConfigured()) {
                setText(
                    String.format(
                        Locale.US,
                        "%.6f, %.6f",
                        store.getOfficeLat(),
                        store.getOfficeLng()
                    )
                )
            }
            setPadding(20, 16, 20, 16)
        }
        container.addView(coordOrLinkInput)
        addSpacer(container, 10)

        val searchResultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val searchByNameBtn = makePillButton("🔎 Search Place by Name", "#4F46E5", GlassUiTheme.textOnAccent) {
            val query = nameInput.text.toString().trim()
            if (query.isEmpty()) {
                showStatusPopup(false, "Enter a place or office name to search")
            } else {
                searchResultsBox.removeAllViews()
                val matches = runCatching {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    geocoder.getFromLocationName(query, 5).orEmpty()
                }.getOrElse { emptyList() }

                if (matches.isEmpty()) {
                    showStatusPopup(false, "No matching location found for '$query'")
                } else {
                    matches.forEachIndexed { i, addr: Address ->
                        val titleText = addr.featureName
                            ?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
                            ?: addr.locality
                            ?: query
                        val lineAddr = (0..addr.maxAddressLineIndex)
                            .mapNotNull { idx -> addr.getAddressLine(idx) }
                            .joinToString(", ")
                            .ifEmpty { String.format(Locale.US, "%.5f, %.5f", addr.latitude, addr.longitude) }

                        val resultBtn = makeSmallChipButton("📌 $lineAddr") {
                            val chosenRadius = store.getGeofenceRadiusMeters()
                            store.setOfficeLocation(titleText, addr.latitude, addr.longitude, chosenRadius)
                            nameInput.setText(titleText)
                            coordOrLinkInput.setText(
                                String.format(Locale.US, "%.6f, %.6f", addr.latitude, addr.longitude)
                            )
                            syncBackgroundAndWidget()
                            refreshAllViews(animateCharts = false)
                            showStatusPopup(true, "Saved '$titleText' (${chosenRadius}m)")
                        }.apply {
                            gravity = Gravity.START
                            setLineSpacing(4f, 1.0f)
                            alpha = 0f
                            translationY = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, 0, 8) }
                            animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setStartDelay((i * 55).toLong())
                                .setDuration(220L)
                                .start()
                        }
                        searchResultsBox.addView(resultBtn)
                    }
                    showStatusPopup(true, "Found ${matches.size} match(es) — tap one to save")
                }
            }
        }
        container.addView(searchByNameBtn)
        addSpacer(container, 8)
        container.addView(searchResultsBox)

        val useCurrentGpsBtn = makePillButton("📍 Use Current GPS + Auto Name", "#10B981", GlassUiTheme.textOnAccent) {
            val ok = captureCurrentLocationWithGeocoder()
            if (ok) {
                nameInput.setText(store.getOfficeLocationName())
                coordOrLinkInput.setText(
                    String.format(Locale.US, "%.6f, %.6f", store.getOfficeLat(), store.getOfficeLng())
                )
            }
        }
        container.addView(useCurrentGpsBtn)
        addSpacer(container, 8)

        val saveManualBtn = makePillButton("💾 Save Name & Coordinates", "#0284C7", GlassUiTheme.textOnAccent) {
            val rawCoord = coordOrLinkInput.text.toString().trim()
            val parsed = parseLatLng(rawCoord)
            val coords = parsed ?: if (rawCoord.isEmpty() && store.hasOfficeLocationConfigured()) {
                store.getOfficeLat() to store.getOfficeLng()
            } else {
                null
            }

            if (coords == null) {
                showStatusPopup(false, "Tap a search result above, use GPS, or enter 'lat, lng'")
            } else {
                val (lat, lng) = coords
                val enteredName = nameInput.text.toString().trim()
                val finalName = if (enteredName.isNotEmpty()) {
                    enteredName
                } else {
                    resolvePlaceNameFromCoordinates(lat, lng)
                }
                store.setOfficeLocation(finalName, lat, lng, store.getGeofenceRadiusMeters())
                coordOrLinkInput.setText(String.format(Locale.US, "%.6f, %.6f", lat, lng))
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Saved location '$finalName'")
            }
        }
        container.addView(saveManualBtn)
        addSpacer(container, 8)

        val openMapsBtn = makePillButton("🗺️ Open Google Maps (Browse / Share)", "#334155", GlassUiTheme.textOnAccent) {
            val query = nameInput.text.toString().trim().ifEmpty { "Hyderabad" }
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
                    showStatusPopup(false, "No map app available on device")
                }
            }
        }
        container.addView(openMapsBtn)
        addSpacer(container, 14)

        val radiusHeader = TextView(this).apply {
            text = "Select Geofence Range:"
            textSize = GlassUiTheme.cardBodySp
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(radiusHeader)

        val dialogRadiusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val d250 = makeModeChip("250m") {}
        val d500 = makeModeChip("500m") {}
        val d1000 = makeModeChip("1000m") {}
        val d1500 = makeModeChip("1500m") {}

        fun refreshDialogRadiusChips() {
            val cur = store.getGeofenceRadiusMeters()
            styleModeChip(d250, cur == 250, "#0284C7")
            styleModeChip(d500, cur == 500, "#0284C7")
            styleModeChip(d1000, cur == 1000, "#0284C7")
            styleModeChip(d1500, cur == 1500, "#0284C7")
        }

        listOf(d250 to 250, d500 to 500, d1000 to 1000, d1500 to 1500).forEach { (chip, meters) ->
            chip.setOnClickListener {
                store.setGeofenceRadiusMeters(meters)
                refreshDialogRadiusChips()
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Geofence range set to ${meters}m")
            }
        }
        refreshDialogRadiusChips()

        dialogRadiusRow.addView(d250)
        addHorizontalGap(dialogRadiusRow, 6)
        dialogRadiusRow.addView(d500)
        addHorizontalGap(dialogRadiusRow, 6)
        dialogRadiusRow.addView(d1000)
        addHorizontalGap(dialogRadiusRow, 6)
        dialogRadiusRow.addView(d1500)
        container.addView(dialogRadiusRow)

        dialogRef = AlertDialog.Builder(this)
            .setTitle("Select Office Location")
            .setView(container)
            .setPositiveButton("Done") { _, _ ->
                if (!store.hasOfficeLocationConfigured()) {
                    showStatusPopup(false, "No office location saved yet")
                }
            }
            .show()
    }

    private fun parseLatLng(input: String): Pair<Double, Double>? {
        if (input.isBlank()) return null
        val match = Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""").find(input)
            ?: Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""").find(input)
            ?: Regex("""[?&]q=(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""").find(input)
            ?: return null

        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun captureCurrentLocationWithGeocoder(): Boolean {
        if (!hasLocationPermission()) {
            requestForegroundAndNotifPermissions()
            showStatusPopup(false, "Location permission required")
            return false
        }
        val loc = getBestLastKnownLocation()
        if (loc == null) {
            showStatusPopup(false, "No current GPS fix available — turn ON Location or try again")
            return false
        }

        val resolvedName = resolvePlaceNameFromCoordinates(loc.latitude, loc.longitude)
        val currentRadius = store.getGeofenceRadiusMeters()
        store.setOfficeLocation(resolvedName, loc.latitude, loc.longitude, currentRadius)
        syncBackgroundAndWidget()
        refreshAllViews(animateCharts = false)
        showStatusPopup(true, "Location set to '$resolvedName' (${currentRadius}m)")
        return true
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
            candidate ?: String.format(Locale.US, "Office (%.4f, %.4f)", lat, lng)
        }.getOrDefault(String.format(Locale.US, "Office (%.4f, %.4f)", lat, lng))
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
        val today = LocalDate.now()
        val loc = getBestLastKnownLocation()
        if (loc != null) {
            store.setLastKnownLocation(loc.latitude, loc.longitude)
        }
        val liveSsid = getConnectedWifiSsid()
        val mode = store.getTrackingMode()
        var logged = false

        if ((mode == TrackingMode.WIFI_ONLY || mode == TrackingMode.BOTH) && liveSsid.isNotEmpty()) {
            val targets = store.getTargetSsids()
            if (targets.any { it.equals(liveSsid, ignoreCase = true) }) {
                val wasPlanned = store.wasPlannedBeforeOffice(today)
                val isNew = store.recordOfficePresence(liveSsid, today)
                logged = true
                if (isNew && !wasPlanned) {
                    notifyUnplannedOfficeAppreciation(today)
                } else if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_$today")) {
                    sendAlertNotification(this, "✅ Office Day Logged ($liveSsid)", "Connected to office Wi-Fi.")
                }
                postAnimatedStatusNotification("✅ Office Presence Active", "Matched Wi-Fi: $liveSsid", activePresence = true)
            }
        }

        if ((mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) && loc != null && store.hasOfficeLocationConfigured()) {
            val dist = store.distanceToOfficeMeters(loc.latitude, loc.longitude)
            if (dist != null && dist <= store.getGeofenceRadiusMeters()) {
                val label = store.getOfficeLocationName().ifEmpty { "Office Geofence" }
                val wasPlanned = store.wasPlannedBeforeOffice(today)
                val isNew = store.recordOfficePresence(label, today)
                logged = true
                if (isNew && !wasPlanned) {
                    notifyUnplannedOfficeAppreciation(today)
                } else if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_$today")) {
                    sendAlertNotification(this, "✅ Office Day Logged ($label)", "Within ${store.getGeofenceRadiusMeters()}m office zone.")
                }
                postAnimatedStatusNotification("✅ Office Presence Active", "Inside ${store.getGeofenceRadiusMeters()}m zone ($label)", activePresence = true)
            }
        }

        syncBackgroundAndWidget()
        refreshAllViews(animateCharts = true)
        if (logged) {
            showStatusPopup(true, "Office presence verified & refreshed")
        } else {
            showStatusPopup(true, "Status refreshed (not at office match)")
        }
    }

    private fun refreshAllViews(animateCharts: Boolean = false) {
        updateLockScreenStatusCard()

        if (::setupFullLockSwitch.isInitialized && !setupFullLockSwitch.isPressed) {
            setupFullLockSwitch.isChecked = store.isFullAppLockEnabled()
        }

        val locOk = hasLocationPermission()
        val notifOk = hasNotificationPermission()
        val bgLocOk = hasBackgroundLocationPermission()
        val gpsOk = isSystemLocationEnabled()
        val alarmOk = canScheduleExactAlarms()
        val batOk = isBatteryUnrestricted()
        val liveSsid = getConnectedWifiSsid()
        val targetPct = store.getTargetPercent()
        val mode = store.getTrackingMode()
        val themeChoice = getThemeChoice()

        if (::themeSystemBtn.isInitialized) styleModeChip(themeSystemBtn, themeChoice == ThemeChoice.SYSTEM, "#4F46E5")
        if (::themeLightBtn.isInitialized) styleModeChip(themeLightBtn, themeChoice == ThemeChoice.LIGHT, "#0284C7")
        if (::themeDarkBtn.isInitialized) styleModeChip(themeDarkBtn, themeChoice == ThemeChoice.DARK, "#334155")

        if (::activeThemeStatusView.isInitialized) {
            val modeName = when (themeChoice) {
                ThemeChoice.SYSTEM -> "System"
                ThemeChoice.LIGHT -> "Light"
                ThemeChoice.DARK -> "Dark"
            }
            val activeSurface = if (isDark) "Dark" else "Light"
            activeThemeStatusView.text = "Selected: $modeName • Active: $activeSurface (glassmorphism UI)"
        }

        val avatarBmp = loadCircularAvatarBitmap()
        setupAvatarView.setImageBitmap(avatarBmp)
        dashAvatarView.setImageBitmap(avatarBmp)

        setupPermStatusView.text = if (locOk && notifOk) {
            "✅ Location & Notifications: Allowed"
        } else {
            "⚠️ Location & Notifications: Action Required"
        }
        setupPermStatusView.setTextColor(
            if (locOk && notifOk) GlassUiTheme.statusSuccessText(isDark)
            else GlassUiTheme.statusDangerText(isDark)
        )
        if (::btnGrantPermView.isInitialized) {
            GlassUiTheme.styleReadinessButton(
                btn = btnGrantPermView,
                isDark = isDark,
                ready = locOk && notifOk,
                readyLabel = "Location & Alerts",
                actionLabel = "Grant Loc/Alerts",
                readyRole = GlassUiTheme.AccentRole.PRIMARY
            )
        }

        setupBgLocStatusView.text = if (bgLocOk) {
            "✅ Background Location: Allow all the time"
        } else {
            "⚠️ Background Location: Must set to \"Allow all the time\""
        }
        setupBgLocStatusView.setTextColor(
            if (bgLocOk) GlassUiTheme.statusSuccessText(isDark)
            else GlassUiTheme.statusDangerText(isDark)
        )
        if (::btnBgLocView.isInitialized) {
            GlassUiTheme.styleReadinessButton(
                btn = btnBgLocView,
                isDark = isDark,
                ready = bgLocOk,
                readyLabel = "All-Time Loc",
                actionLabel = "Set All-Time Loc",
                readyRole = GlassUiTheme.AccentRole.PRIMARY
            )
        }

        setupGpsStatusView.text = if (gpsOk) {
            "✅ Phone Location (GPS) Switch: ON"
        } else {
            "⚠️ Phone Location (GPS) Switch: OFF (Android hides Wi-Fi if OFF)"
        }
        setupGpsStatusView.setTextColor(
            if (gpsOk) GlassUiTheme.statusSuccessText(isDark)
            else GlassUiTheme.statusDangerText(isDark)
        )
        if (::btnGpsView.isInitialized) {
            GlassUiTheme.styleReadinessButton(
                btn = btnGpsView,
                isDark = isDark,
                ready = gpsOk,
                readyLabel = "GPS Switch ON",
                actionLabel = "Turn ON GPS",
                readyRole = GlassUiTheme.AccentRole.SECONDARY
            )
        }

        val alarmLabel = if (alarmOk) {
            "✅ Exact Alarms: Ready (On-Time Reminders)"
        } else {
            "⚠️ Exact Alarms: Action Required (May Be Delayed)"
        }
        val alarmColor = if (alarmOk) GlassUiTheme.statusSuccessText(isDark) else GlassUiTheme.statusWarningText(isDark)
        setupExactAlarmStatusView.text = alarmLabel
        setupExactAlarmStatusView.setTextColor(alarmColor)
        if (::btnExactAlarmView.isInitialized) {
            GlassUiTheme.styleReadinessButton(
                btn = btnExactAlarmView,
                isDark = isDark,
                ready = alarmOk,
                readyLabel = "Exact Alarms",
                actionLabel = "Enable Alarms",
                readyRole = GlassUiTheme.AccentRole.SECONDARY
            )
        }

        if (::settingsNotifPermStatusView.isInitialized) {
            settingsNotifPermStatusView.text = if (notifOk) {
                "🔔 System Notification Permission: Allowed"
            } else {
                "🔕 System Notification Permission: Not Allowed (Tap Setup to grant)"
            }
            settingsNotifPermStatusView.setTextColor(
                if (notifOk) GlassUiTheme.statusSuccessText(isDark)
                else GlassUiTheme.statusWarningText(isDark)
            )
        }

        if (::settingsExactAlarmStatusView.isInitialized) {
            settingsExactAlarmStatusView.text = alarmLabel
            settingsExactAlarmStatusView.setTextColor(alarmColor)
            settingsExactAlarmStatusView.background = GlassUiTheme.glassCard(
                isDark,
                GlassUiTheme.chipRadiusPx,
                GlassUiTheme.statusTint(isDark, if (alarmOk) "success" else "warning")
            )
        }

        setupBatteryStatusView.text = if (batOk) {
            "✅ Background Battery: Unrestricted"
        } else {
            "⚠️ Background Battery: Restricted (Tap below & choose Allow)"
        }
        setupBatteryStatusView.setTextColor(
            if (batOk) GlassUiTheme.statusSuccessText(isDark)
            else GlassUiTheme.statusDangerText(isDark)
        )
        if (::btnBatteryView.isInitialized) {
            GlassUiTheme.styleReadinessButton(
                btn = btnBatteryView,
                isDark = isDark,
                ready = batOk,
                readyLabel = "Unrestricted Battery",
                actionLabel = "Allow Unrestricted Battery",
                readyRole = GlassUiTheme.AccentRole.SUCCESS
            )
        }

        setupLiveWifiView.text = if (liveSsid.isEmpty()) {
            "Detected Wi-Fi: None (Connect to office Wi-Fi or type below)"
        } else {
            "Detected Wi-Fi: $liveSsid"
        }
        setupLocationSummaryView.text = if (store.hasOfficeLocationConfigured()) {
            "Office Zone: ${store.getOfficeLocationName()} (${store.getGeofenceRadiusMeters()}m radius)"
        } else {
            "Office Zone: Not configured yet (${store.getGeofenceRadiusMeters()}m default radius)"
        }
        setupTargetPctView.text = "Mandate Target: $targetPct%"

        val health = store.getBackupHealthInfo()
        setupBackupFolderStatusView.text = health.statusText
        autoBackupStatusView.text = health.statusText
        autoBackupStatusView.setTextColor(
            when {
                !health.configured -> cTextSecondary()
                health.healthy -> GlassUiTheme.statusSuccessText(isDark)
                else -> GlassUiTheme.statusDangerText(isDark)
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
            val okCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
            dashServiceStatusView.text = "Auto-Tracking Active"
            dashServiceStatusView.setTextColor(GlassUiTheme.statusSuccessText(isDark))
            heartbeatDotView.background = GlassUiTheme.roundedShape(okCol, okCol, 999f, 0)
            startHeartbeatAnimation(true)
        } else {
            val warnCol = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.WARNING).second
            dashServiceStatusView.text = "Setup Incomplete (Tap Settings)"
            dashServiceStatusView.setTextColor(GlassUiTheme.statusWarningText(isDark))
            heartbeatDotView.background = GlassUiTheme.roundedShape(warnCol, warnCol, 999f, 0)
            startHeartbeatAnimation(false)
        }

        lockToggleBtn.text = if (isEditUnlocked) "🔓 Unlocked" else "🔒 Locked"
        lockToggleBtn.background = GlassUiTheme.roundedShape(
            if (isEditUnlocked) {
                GlassUiTheme.statusTint(isDark, "success")
            } else {
                GlassUiTheme.statusTint(isDark, "info")
            },
            if (isEditUnlocked) {
                GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SUCCESS).second
            } else {
                GlassUiTheme.borderColor(isDark)
            },
            GlassUiTheme.cellRadiusPx,
            2
        )
        lockToggleBtn.setTextColor(
            if (isEditUnlocked) GlassUiTheme.statusSuccessText(isDark) else cTextPrimary()
        )

        val today = LocalDate.now()
        val todayType = store.getDayType(today)
        val isOnOfficePresence = store.wasConnectedToOfficeWifi()
        val activeSource = store.getActiveOfficeSsid()
        val todayStamp = today.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.getDefault()))

        val disconnectedLabel = when (mode) {
            TrackingMode.LOCATION_ONLY -> "Not at office location"
            TrackingMode.WIFI_ONLY -> "Not connected to office Wi-Fi"
            TrackingMode.BOTH -> "Not at office"
        }

        startLiveStripPulse(isOnOfficePresence)

        when {
            isOnOfficePresence -> {
                val srcTag = if (activeSource.isNotEmpty()) " ($activeSource)" else ""
                todayLiveStripView.text = "🟢 $todayStamp • At Office$srcTag"
                todayLiveStripView.setTextColor(GlassUiTheme.statusSuccessText(isDark))
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "success"))
            }
            todayType == DayType.OFFICE -> {
                todayLiveStripView.text = "✅ $todayStamp • Office Day Logged"
                todayLiveStripView.setTextColor(GlassUiTheme.statusSuccessText(isDark))
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "success"))
            }
            todayType == DayType.PLANNED -> {
                todayLiveStripView.text = "📅 $todayStamp • Planned Office Day"
                todayLiveStripView.setTextColor(GlassUiTheme.statusInfoText(isDark))
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "info"))
            }
            todayType == DayType.LEAVE -> {
                todayLiveStripView.text = "🌴 $todayStamp • On Leave"
                todayLiveStripView.setTextColor(GlassUiTheme.statusWarningText(isDark))
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "warning"))
            }
            todayType == DayType.HOLIDAY -> {
                todayLiveStripView.text = "🎉 $todayStamp • Holiday"
                todayLiveStripView.setTextColor(GlassUiTheme.statusHolidayText(isDark))
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx, GlassUiTheme.statusTint(isDark, "holiday"))
            }
            else -> {
                todayLiveStripView.text = "🏠 $todayStamp • WFH ($disconnectedLabel)"
                todayLiveStripView.setTextColor(cTextSecondary())
                todayLiveStripView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.bannerRadiusPx)
            }
        }

        todayJumpBtn.visibility = if (selectedMonth != YearMonth.now()) View.VISIBLE else View.GONE

        val s = store.getMonthStats(selectedMonth)
        val w = store.getWeeklyPaceStats()
        val r = store.getRhythmStats()

        monthTitleView.text = s.monthLabel

        if (::clearPlanBtn.isInitialized) {
            clearPlanBtn.visibility = if (s.plannedTotalCount > 0) View.VISIBLE else View.GONE
        }

        val plannedCoversTarget = s.futurePlannedDays > 0 && s.projectedAttendedDays >= s.requiredDays
        val extraPlannedDays = maxOf(0, s.projectedAttendedDays - s.requiredDays)

        val ringColor = GlassUiTheme.ringAccentColor(isDark, s.remainingDays, plannedCoversTarget, s.bufferDays)
        val cardTintColor: Int = when {
            s.remainingDays == 0 || plannedCoversTarget -> GlassUiTheme.statusTint(isDark, "success")
            s.bufferDays <= 0 -> GlassUiTheme.statusTint(isDark, "danger")
            else -> GlassUiTheme.statusTint(isDark, "warning")
        }
        val headlineColor: Int = when {
            s.remainingDays == 0 || plannedCoversTarget -> GlassUiTheme.statusSuccessText(isDark)
            s.bufferDays <= 0 -> GlassUiTheme.statusDangerText(isDark)
            else -> GlassUiTheme.statusWarningText(isDark)
        }
        val subColor: Int = when {
            s.remainingDays == 0 || plannedCoversTarget -> GlassUiTheme.statusSuccessText(isDark)
            s.bufferDays <= 0 -> GlassUiTheme.statusDangerText(isDark)
            else -> cTextSecondary()
        }

        val currentYm = YearMonth.from(today)
        val isCurrentMonth = selectedMonth == currentYm
        val isPastMonth = selectedMonth.isBefore(currentYm)

        if (::briefingCardView.isInitialized) {
            briefingCardView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.cardRadiusPx, cardTintColor)

            if (isCurrentMonth) {
                briefingHeadlineView.text = if (s.remainingDays > 0 && plannedCoversTarget) {
                    "🎯 Target Covered via Planned (${s.projectedAttendedDays}/${s.requiredDays} days • ${s.projectedPercent}% proj.)"
                } else {
                    r.safeToWfhVerdict
                }
                briefingHeadlineView.setTextColor(headlineColor)

                val monthlySummary = when {
                    s.remainingDays == 0 -> "${s.targetPercent}% monthly mandate met (${s.attendedDays}/${s.requiredDays} days)"
                    plannedCoversTarget -> {
                        val extraTag = if (extraPlannedDays > 0) " (+$extraPlannedDays over target)" else ""
                        "Covered with +${s.futurePlannedDays} planned$extraTag • ${maxOf(0, s.bufferDays)} WFH buffer left"
                    }
                    s.bufferDays < 0 -> "Shortfall: ${s.remainingDays} day(s) needed across ${s.availableWeekdaysLeft} left"
                    s.bufferDays == 0 -> "Zero buffer: all ${s.availableWeekdaysLeft} working day(s) needed"
                    else -> "On track • ${s.bufferDays} WFH buffer day(s) left"
                }
                briefingSubView.visibility = View.VISIBLE
                briefingSubView.text = "$monthlySummary • Window ${r.learnedArrivalWindowLabel}"
                briefingSubView.setTextColor(subColor)

                briefingWeeklyView.visibility = View.VISIBLE
                briefingWeeklyView.text = if (w.remainingThisWeek == 0) {
                    "📆 This Week: ${w.attendedThisWeek}/${w.weeklyQuota} days done (pace met)"
                } else {
                    "📆 This Week: ${w.attendedThisWeek}/${w.weeklyQuota} days done • Need ${w.remainingThisWeek} more (${w.weekdaysLeftThisWeek} left)"
                }
                briefingWeeklyView.setTextColor(cTextPrimary())

                val daysLeftInMonth = selectedMonth.atEndOfMonth().dayOfMonth - today.dayOfMonth
                if (s.remainingDays > 0 && daysLeftInMonth in 0..7 && s.recoverySuggestedDates.isNotEmpty()) {
                    val dateList = s.recoverySuggestedDates.joinToString(", ") {
                        it.format(DateTimeFormatter.ofPattern("dd MMM (EEE)"))
                    }
                    briefingRecoveryView.visibility = View.VISIBLE
                    briefingRecoveryView.setTextColor(subColor)
                    briefingRecoveryView.text = "📌 Suggested dates: $dateList (${String.format(Locale.US, "%.2f", s.requiredDailyRunRate)}/wkday)"
                } else {
                    briefingRecoveryView.visibility = View.GONE
                }
            } else {
                briefingSubView.visibility = View.GONE
                briefingWeeklyView.visibility = View.GONE
                briefingRecoveryView.visibility = View.GONE

                if (isPastMonth) {
                    if (s.remainingDays == 0) {
                        briefingHeadlineView.text = "✅ ${s.monthLabel} — Target Met (${s.attendedDays}/${s.requiredDays} days • ${s.actualPercent}%)"
                    } else {
                        briefingHeadlineView.text = "⚠️ ${s.monthLabel} — Shortfall of ${s.remainingDays} day(s) (${s.attendedDays}/${s.requiredDays} days)"
                    }
                } else {
                    val plannedSuffix = if (s.futurePlannedDays > 0) " • ${s.futurePlannedDays} planned" else ""
                    if (s.remainingDays == 0) {
                        briefingHeadlineView.text = "✅ ${s.monthLabel} — Target Met$plannedSuffix"
                    } else if (plannedCoversTarget) {
                        briefingHeadlineView.text = "🎯 ${s.monthLabel} — Target covered via planned (${s.projectedAttendedDays}/${s.requiredDays} • ${s.projectedPercent}%)$plannedSuffix"
                    } else if (s.bufferDays < 0) {
                        briefingHeadlineView.text = "⚠️ ${s.monthLabel} — Shortfall risk: ${s.remainingDays} day(s) needed across ${s.availableWeekdaysLeft} left$plannedSuffix"
                    } else {
                        briefingHeadlineView.text = "📅 ${s.monthLabel} — On track • ${s.remainingDays} needed • ${s.bufferDays} WFH buffer$plannedSuffix"
                    }
                }
                briefingHeadlineView.setTextColor(headlineColor)
            }
        }

        circularRingView.setData(
            attended = s.attendedDays,
            projected = s.projectedAttendedDays,
            required = s.requiredDays,
            actualPct = s.actualPercent,
            accentColor = ringColor,
            isDark = isDark,
            animate = animateCharts
        )
        heroSubView.text = "${s.actualPercent}% of ${s.effectiveWorkingDays} working days (${s.targetPercent}% mandate)"

        if (s.futurePlannedDays > 0) {
            heroProjectedView.visibility = View.VISIBLE
            heroProjectedView.background = GlassUiTheme.glassCard(
                isDark,
                GlassUiTheme.cellRadiusPx,
                GlassUiTheme.statusTint(isDark, if (plannedCoversTarget) "success" else "info")
            )
            heroProjectedView.setTextColor(
                if (plannedCoversTarget) GlassUiTheme.statusSuccessText(isDark)
                else GlassUiTheme.statusInfoText(isDark)
            )
            val cushionSuffix = if (extraPlannedDays > 0) " (+$extraPlannedDays above target)" else ""
            heroProjectedView.text = if (plannedCoversTarget) {
                "🎯 Target covered with +${s.futurePlannedDays} planned → ${s.projectedAttendedDays}/${s.requiredDays} days (${s.projectedPercent}% vs ${s.targetPercent}%)$cushionSuffix"
            } else {
                "📅 +${s.futurePlannedDays} planned → Projected: ${s.projectedAttendedDays}/${s.requiredDays} days (${s.projectedPercent}%)"
            }
        } else {
            heroProjectedView.visibility = View.GONE
        }

        kpiWorkingNumView.text = "${s.effectiveWorkingDays}"
        kpiWorkingNumView.setTextColor(cTextPrimary())

        kpiRemainingNumView.text = "${s.remainingDays}"
        kpiRemainingNumView.setTextColor(
            if (s.remainingDays == 0) GlassUiTheme.statusSuccessText(isDark)
            else GlassUiTheme.statusInfoText(isDark)
        )

        val safeBuffer = maxOf(0, s.bufferDays)
        kpiBufferNumView.text = "$safeBuffer"
        kpiBufferNumView.setTextColor(
            when {
                s.remainingDays == 0 || safeBuffer >= 2 -> GlassUiTheme.statusSuccessText(isDark)
                safeBuffer == 1 -> GlassUiTheme.statusWarningText(isDark)
                else -> GlassUiTheme.statusDangerText(isDark)
            }
        )

        styleModeChip(modeOfficeBtn, activeTapMode == DayType.OFFICE, "#10B981")
        styleModeChip(modePlannedBtn, activeTapMode == DayType.PLANNED, "#4F46E5")
        styleModeChip(modeLeaveBtn, activeTapMode == DayType.LEAVE, "#F59E0B")
        styleModeChip(modeHolidayBtn, activeTapMode == DayType.HOLIDAY, "#8B5CF6")

        renderCalendarGrid(s)

        val arrow = if (isMonthlyLogExpanded) "▲" else "▼"
        logHeaderToggleView.text = "Monthly Log & Timestamps (${s.dayLogs.size} entries • ${s.attendedDays} Office)   $arrow"
        logExpandBodyView.visibility = if (isMonthlyLogExpanded) View.VISIBLE else View.GONE

        if (::logSummaryBarView.isInitialized) {
            logSummaryBarView.text =
                "🏢 ${s.attendedDays} Office  •  📅 ${s.plannedTotalCount} Planned  •  🌴 ${s.leaveDays} Leave  •  🎉 ${s.holidayDays} Holiday"
        }

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
        if (filteredLogs.isEmpty()) {
            auditLogView.gravity = Gravity.CENTER
            auditLogView.setTextColor(cTextSecondary())
            auditLogView.text = "📭 No matching entries in ${s.monthLabel}.\nTap ✨ Auto-Plan above or unlock 🔒 to log days."
        } else {
            auditLogView.gravity = Gravity.START
            auditLogView.setTextColor(cTextPrimary())
            auditLogView.text = filteredLogs.joinToString("\n") { log ->
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
        val trends = store.getMultiMonthTrend(selectedMonth, 6)
        if (::trendLegendView.isInitialized) {
            trendLegendView.text = "🟢 Met ≥$targetPct%   •   🟣 Below $targetPct%   •   🟠 Target Line ($targetPct%)"
        }
        trendChartView.setData(trends, isDark, animate = animateCharts)

        if (::runRateFollowThroughView.isInitialized) {
            runRateFollowThroughView.text =
                "⚡ Run-Rate: ${String.format(Locale.US, "%.2f", s.requiredDailyRunRate)}/wkday • 🎯 Follow-Through: ${s.planFollowThroughPercent}% • ⏱️ Avg: ${String.format(Locale.US, "%.1f", s.avgOfficeHours)}h"
        }
        if (::bufferTrajectoryView.isInitialized) {
            bufferTrajectoryView.text = "📈 ${s.bufferTrajectoryLabel}   •   ${q.driftSummary}"
        }

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
        simResultView.background = GlassUiTheme.glassCard(isDark, GlassUiTheme.cellRadiusPx, GlassUiTheme.statusTint(isDark, "info"))
        simResultView.setTextColor(GlassUiTheme.statusInfoText(isDark))
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
        if (store.hasOfficeLocationConfigured()) {
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
        } else {
            selectedOfficeView.text = "📍 No office location selected (${radius}m radius)"
            officeDistanceView.text = "Tap Select Location to search by name, use GPS, or paste coordinates."
        }

        syncRadiusChipHighlights()
        renderPriorityWeekdays()
        renderUpcomingHolidaysAndLeaves()
        syncCollapsibleSections()

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
                textSize = 13.5f
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
                background = GlassUiTheme.glassSubtleSurface(isDark, GlassUiTheme.bannerRadiusPx)
                setPadding(22, 14, 18, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            val nameTv = TextView(this).apply {
                text = "📶  $ssid"
                textSize = 14.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setTextColor(GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.PRIMARY).second)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(GlassUiTheme.statusDangerText(isDark))
                setPadding(16, 4, 8, 4)
                setOnClickListener {
                    if (ensureEditUnlocked()) {
                        store.removeTargetSsid(ssid)
                        syncBackgroundAndWidget()
                        refreshAllViews(animateCharts = false)
                        showStatusPopup(true, "Removed '$ssid'")
                    }
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
                textSize = GlassUiTheme.cardSubtextSp
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setTextColor(cTextSecondary())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 4, 0, 8)
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
                setPadding(0, 3, 0, 3)
            }
            for (col in 0..6) {
                val cellWrapper = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 92, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                }
                if ((currentDay == 1 && col < firstDayOffset) || currentDay > daysInMonth) {
                    // Empty placeholder cell
                } else {
                    val date = ym.atDay(currentDay)
                    val dateStr = date.toString()
                    val dtype = stats.dayTypeMap[dateStr] ?: DayType.NONE
                    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                    val isToday = date == today
                    val hasNote = store.getDayNote(dateStr).isNotEmpty()

                    val dayNumTv = TextView(this).apply {
                        text = currentDay.toString()
                        gravity = Gravity.CENTER
                        textSize = GlassUiTheme.cardBodySp
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    cellWrapper.background = GlassUiTheme.calendarCellDrawable(isDark, dtype, isToday)
                    when (dtype) {
                        DayType.OFFICE, DayType.LEAVE, DayType.HOLIDAY -> {
                            dayNumTv.setTextColor(GlassUiTheme.textOnAccent)
                            dayNumTv.setTypeface(null, Typeface.BOLD)
                        }
                        DayType.PLANNED -> {
                            dayNumTv.setTextColor(GlassUiTheme.statusInfoText(isDark))
                            dayNumTv.setTypeface(null, Typeface.BOLD)
                        }
                        DayType.NONE -> {
                            if (isWeekend) {
                                dayNumTv.setTextColor(cTextSecondary())
                                cellWrapper.alpha = GlassUiTheme.weekendDimAlpha
                            } else {
                                dayNumTv.setTextColor(cTextPrimary())
                            }
                        }
                    }

                    cellWrapper.addView(dayNumTv)

                    if (hasNote) {
                        val noteDot = View(this).apply {
                            layoutParams = FrameLayout.LayoutParams(10, 10).apply {
                                gravity = Gravity.TOP or Gravity.END
                                setMargins(0, 10, 10, 0)
                            }
                            val dotColor = if (dtype == DayType.NONE || dtype == DayType.PLANNED) {
                                GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.PRIMARY).second
                            } else {
                                GlassUiTheme.textOnAccent
                            }
                            background = GlassUiTheme.roundedShape(dotColor, dotColor, 999f, 0)
                        }
                        cellWrapper.addView(noteDot)
                    }

                    if (isToday) {
                        val todayBar = View(this).apply {
                            layoutParams = FrameLayout.LayoutParams(24, 5).apply {
                                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                setMargins(0, 0, 0, 9)
                            }
                            val barColor = if (dtype == DayType.NONE || dtype == DayType.PLANNED) {
                                GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.SECONDARY).second
                            } else {
                                GlassUiTheme.textOnAccent
                            }
                            background = GlassUiTheme.roundedShape(barColor, barColor, 999f, 0)
                        }
                        cellWrapper.addView(todayBar)
                    }

                    cellWrapper.setOnClickListener { view ->
                        if (!isEditUnlocked) {
                            showDayInspectorDialog(date)
                        } else {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            animateCellPop(view)
                            val prevType = dtype
                            val resulting = store.applyManualDayType(date, activeTapMode)
                            syncBackgroundAndWidget()
                            refreshAllViews(animateCharts = false)
                            if (activeTapMode == DayType.OFFICE && resulting == DayType.OFFICE && prevType != DayType.OFFICE) {
                                handleUnplannedOfficeLogged(date)
                            } else {
                                val msg = if (resulting == DayType.NONE) {
                                    "Cleared $dateStr"
                                } else {
                                    "Marked $dateStr as ${resulting.name}"
                                }
                                showStatusPopup(true, msg)
                            }
                        }
                    }

                    cellWrapper.setOnLongClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showDayInspectorDialog(date)
                        true
                    }
                    currentDay++
                }
                weekRow.addView(cellWrapper)
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
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        val windowTv = TextView(this).apply {
            text = info.timeWindowLabel
            textSize = GlassUiTheme.cardBodySp
            setPadding(0, 0, 0, 4)
        }
        val spanTv = TextView(this).apply {
            text = info.durationLabel
            textSize = GlassUiTheme.cardBodySp
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 14)
        }
        container.addView(statusTv)
        container.addView(windowTv)
        container.addView(spanTv)

        if (isEditUnlocked) {
            if (info.type == DayType.PLANNED) {
                val missedBtn = makeSmallChipButton("Missed Plan? (Leave / Holiday / Alternate)") {
                    dialogRef?.dismiss()
                    showMissedPlannedDayFlow(date)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 12) }
                }
                container.addView(missedBtn)
            }

            val switchLabel = TextView(this).apply {
                text = "Quick Status Change:"
                textSize = GlassUiTheme.cardSubtextSp
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
                    refreshAllViews(animateCharts = false)
                    dialogRef?.dismiss()
                    showStatusPopup(true, "Updated $date")
                }
                styleModeChip(chip, info.type == targetType, "#4F46E5")
                quickStatusRow.addView(chip)
                if (idx < options.lastIndex) addHorizontalGap(quickStatusRow, 4)
            }
            container.addView(quickStatusRow)
        }

        val noteInput = EditText(this).apply {
            hint = if (isEditUnlocked) "Add note or tap a preset below..." else "Unlock 🔒 to edit note"
            textSize = 14.5f
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
                refreshAllViews(animateCharts = false)
                showStatusPopup(true, "Note saved")
            }
        }

        dialogRef = builder.show()
    }

    private fun handleAutoPlanClicked() {
        if (!ensureEditUnlocked()) return
        val monthStats = store.getMonthStats(selectedMonth)
        val isJanuaryOrEmptyHolidays =
            store.isExcludeHolidaysEnabled() &&
                (selectedMonth.monthValue == 1 || monthStats.holidayDays == 0)

        if (isJanuaryOrEmptyHolidays) {
            val promptTitle = if (selectedMonth.monthValue == 1) {
                "New Year Holiday Check (${monthStats.monthLabel})"
            } else {
                "No Holidays Marked (${monthStats.monthLabel})"
            }
            AlertDialog.Builder(this)
                .setTitle(promptTitle)
                .setMessage(
                    "Are you sure there are no holidays in ${monthStats.monthLabel} yet? " +
                        "Auto-Plan will schedule days based on current working days."
                )
                .setPositiveButton("Yes, Auto-Plan Now") { _, _ ->
                    executeAutoPlanForSelectedMonth()
                }
                .setNeutralButton("Import Holidays") { _, _ ->
                    launchHolidayImport()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            executeAutoPlanForSelectedMonth()
        }
    }

    private fun executeAutoPlanForSelectedMonth() {
        autoPlanBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        animateCellPop(autoPlanBtn)
        val modified = store.autoPlanRemainingDaysForMonth(selectedMonth)
        syncBackgroundAndWidget()
        refreshAllViews(animateCharts = true)
        if (modified.isNotEmpty()) {
            showUndoBanner("Auto-planned ${modified.size} office day(s)", modified)
            showStatusPopup(true, "Auto-planned ${modified.size} office day(s)")
        } else {
            showStatusPopup(false, "Monthly target days are already planned or met")
        }
    }

    private fun notifyUnplannedOfficeAppreciation(date: LocalDate) {
        val stats = store.getMonthStats(YearMonth.from(date))
        val msg = if (stats.remainingDays == 0) {
            "Extra office day on $date banked! ${stats.targetPercent}% monthly mandate met with extra cushion."
        } else {
            "Extra office day on $date appreciated! ${stats.remainingDays} still needed • ${maxOf(0, stats.bufferDays)} WFH buffer left (plans untouched)."
        }
        if (store.claimUnplannedAppreciationOnce(date)) {
            postAnimatedStatusNotification("🌟 Extra Office Day Banked", msg, activePresence = true)
        }
        showStatusPopup(true, msg)
    }

    private fun handleUnplannedOfficeLogged(date: LocalDate) {
        notifyUnplannedOfficeAppreciation(date)
    }

    private fun showMissedPlannedDayFlow(missedDate: LocalDate) {
        if (!ensureEditUnlocked()) return
        val formattedDate = missedDate.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))

        AlertDialog.Builder(this)
            .setTitle("Missed Planned Day ($formattedDate)")
            .setMessage("Step 1 of 2: Was $formattedDate a Leave or Holiday?")
            .setPositiveButton("Mark as Leave") { _, _ ->
                showAlternateDayPickerDialog(missedDate, DayType.LEAVE)
            }
            .setNeutralButton("Mark as Holiday") { _, _ ->
                showAlternateDayPickerDialog(missedDate, DayType.HOLIDAY)
            }
            .setNegativeButton("Pick Alternate Only") { _, _ ->
                showAlternateDayPickerDialog(missedDate, DayType.NONE)
            }
            .show()
    }

    private fun showAlternateDayPickerDialog(missedDate: LocalDate, missedDayChoice: DayType) {
        val ym = YearMonth.from(missedDate)
        val startSearchDate = maxOf(LocalDate.now().plusDays(1), missedDate.plusDays(1))
        val candidates = store.getAlternateEmptyWeekdays(ym, startSearchDate)
        val priorities = store.getPreferredWeekdaysPriority()

        val labels = candidates.map { d ->
            val dowShort = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val prefTag = if (priorities.contains(d.dayOfWeek)) "★ Preferred" else "Empty Day"
            "${d.format(DateTimeFormatter.ofPattern("dd MMM"))} ($dowShort) — $prefTag"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Step 2: Pick Alternate Empty Day")
            .setMessage(
                if (candidates.isEmpty()) {
                    "No empty weekdays left in ${ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}."
                } else {
                    "Preferred empty weekdays are listed first, followed by other empty weekdays:"
                }
            )
            .setItems(labels) { _, which ->
                val chosenAlt = candidates[which]
                val undoMap = store.movePlannedDayToAlternateDate(missedDate, chosenAlt, missedDayChoice)
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = true)
                showUndoBanner("Moved plan to ${chosenAlt.format(DateTimeFormatter.ofPattern("dd MMM"))}", undoMap)

                val postStats = store.getMonthStats(ym)
                val statusMsg = if (postStats.bufferDays >= 0) {
                    "Moved to ${chosenAlt.format(DateTimeFormatter.ofPattern("dd MMM (EEE)"))} — On track (${postStats.remainingDays} needed, ${maxOf(0, postStats.bufferDays)} WFH buffer left)"
                } else {
                    "Moved to ${chosenAlt.format(DateTimeFormatter.ofPattern("dd MMM (EEE)"))} — Shortfall risk (${postStats.remainingDays} needed across ${postStats.availableWeekdaysLeft} left)"
                }
                showStatusPopup(true, statusMsg)
            }
            .setNeutralButton("Skip Alternate (Recalculate Only)") { _, _ ->
                val undoMap = linkedMapOf(missedDate to store.getDayType(missedDate))
                if (missedDayChoice == DayType.LEAVE || missedDayChoice == DayType.HOLIDAY) {
                    store.setExplicitDayType(missedDate, missedDayChoice)
                } else {
                    store.setExplicitDayType(missedDate, DayType.NONE)
                }
                syncBackgroundAndWidget()
                refreshAllViews(animateCharts = true)
                showUndoBanner("Updated $missedDate", undoMap)
                val postStats = store.getMonthStats(ym)
                showStatusPopup(
                    true,
                    "Recalculated: ${postStats.remainingDays} still needed • ${maxOf(0, postStats.bufferDays)} WFH buffer left"
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        paint.color = GlassUiTheme.accentColors(isDark, GlassUiTheme.AccentRole.PRIMARY).first
        canvas.drawOval(rectF, paint)

        val name = store.getUserName()
        val initial = if (name.isNotEmpty()) name.first().uppercaseChar().toString() else "O"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassUiTheme.textOnAccent
            textSize = 68f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, canvas.width / 2f, yPos, textPaint)
        return output
    }

    private fun requestPinOfficePaceWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showStatusPopup(false, "Long-press home screen → Widgets to add OfficePace Glance")
            return
        }
        val awm = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, WfoWidgetProvider::class.java)
        if (!awm.isRequestPinAppWidgetSupported) {
            showStatusPopup(false, "Launcher does not support in-app widget pinning")
            return
        }
        WfoWidgetProvider.updateAllWidgets(this)
        val accepted = awm.requestPinAppWidget(provider, null, null)
        if (!accepted) {
            showStatusPopup(false, "Could not open pin prompt — add via home screen Widgets")
        }
    }

    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun openExactAlarmSettings() {
        if (canScheduleExactAlarms()) {
            showStatusPopup(true, "Exact alarms are already permitted")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }.onFailure {
                showStatusPopup(false, "Unable to open Exact Alarm settings")
            }
        }
    }

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
            putExtra(Intent.EXTRA_TITLE, "officepace_attendance_backup.csv")
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 11
            )
        } else {
            showStatusPopup(true, "Background Location is already enabled")
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        if (isBatteryUnrestricted()) {
            showStatusPopup(true, "Battery is already unrestricted")
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
            .replace("\u00E2\u2014\u201A", "-")
            .replace("\u00E2\u2014\u20AC", "-")
            .replace("â\u0094\u2080", "-")
            .replace("\u2013", "-")
            .replace("\u2014", "-")
            .replace("\u2212", "-")
    }

    private fun cTextPrimary(): Int = GlassUiTheme.textPrimaryColor(isDark)
    private fun cTextSecondary(): Int = GlassUiTheme.textSecondaryColor(isDark)

    private fun makePersonalFooterView(): TextView {
        return TextView(this).apply {
            text = "Developed by Eleazer Patil • Local-First, Privacy-Focused"
            textSize = GlassUiTheme.footerTextSp
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 14)
        }
    }

    private fun makeCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 0f
            setPadding(24, 20, 24, 20)
            background = GlassUiTheme.glassCard(isDark, GlassUiTheme.cardRadiusPx)
        }
    }

    private fun makeSectionTitle(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            GlassUiTheme.styleGlassSectionHeader(this, isDark)
        }
    }

    private fun makePillButton(
        label: String,
        bgHex: String,
        textColor: Int,
        onClick: () -> Unit = {}
    ): TextView {
        return TextView(this).apply {
            text = label
            GlassUiTheme.stylePrimaryPillButton(this, bgHex, textColor, isDark)
            setOnClickListener {
                animateCellPop(it)
                onClick()
            }
        }
    }

    private fun makeTallThumbButton(
        label: String,
        role: GlassUiTheme.AccentRole,
        onClick: () -> Unit = {}
    ): TextView {
        return TextView(this).apply {
            text = label
            GlassUiTheme.styleTonalButton(this, role, isDark)
            setOnClickListener {
                animateCellPop(it)
                onClick()
            }
        }
    }

    private fun makeSmallChipButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            GlassUiTheme.styleSmallChip(this, isDark)
            setOnClickListener { onClick() }
        }
    }

    private fun makeNavTabButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            GlassUiTheme.styleNavTab(this, selected = false, isDark = isDark)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun styleNavTab(btn: TextView, selected: Boolean) {
        GlassUiTheme.styleNavTab(btn, selected, isDark)
    }

    private fun makeModeChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            GlassUiTheme.styleModeChip(this, selected = false, activeHex = "#4F46E5", isDark = isDark)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun styleModeChip(chip: TextView, selected: Boolean, activeHex: String) {
        GlassUiTheme.styleModeChip(chip, selected, activeHex, isDark)
    }

    private fun makeColorCodedKpiColumn(caption: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val numTv = TextView(this).apply {
            textSize = 20f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextPrimary())
            gravity = Gravity.CENTER
        }
        val labelTv = TextView(this).apply {
            text = caption
            textSize = GlassUiTheme.footerTextSp + 0.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(cTextSecondary())
            gravity = Gravity.CENTER
            setPadding(0, 3, 0, 0)
        }
        col.addView(numTv)
        col.addView(labelTv)
        return col to numTv
    }

    private fun addSpacer(parent: LinearLayout, heightPx: Int) {
        val space = TextView(this).apply { height = heightPx }
        parent.addView(space)
    }

    private fun addHorizontalGap(parent: LinearLayout, widthPx: Int) {
        val gap = TextView(this).apply { width = widthPx }
        parent.addView(gap)
    }

    private class CircularProgressRingView(context: Context) : View(context) {
        private var targetAttended = 0
        private var targetProjected = 0
        private var required = 9
        private var targetPct = 0
        private var animFraction = 1f
        private var accentColor = GlassUiTheme.accentColors(true, GlassUiTheme.AccentRole.PRIMARY).second
        private var dark = false
        private var ringAnimator: ValueAnimator? = null

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
        }
        private val projectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f
            strokeCap = Paint.Cap.BUTT
            pathEffect = GlassUiTheme.createDashEffect()
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
        }
        private val goalMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 54f
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 29f
        }

        fun setData(
            attended: Int,
            projected: Int,
            required: Int,
            actualPct: Int,
            accentColor: Int,
            isDark: Boolean,
            animate: Boolean = true
        ) {
            this.targetAttended = attended
            this.targetProjected = projected
            this.required = required
            this.targetPct = actualPct
            this.accentColor = accentColor
            this.dark = isDark

            ringAnimator?.cancel()
            if (animate) {
                ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 650L
                    interpolator = DecelerateInterpolator(1.4f)
                    addUpdateListener { va ->
                        animFraction = va.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                animFraction = 1f
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 28f
            val rect = RectF(pad, pad, width - pad, height - pad)

            trackPaint.color = GlassUiTheme.ringTrackColor(dark)
            projectedPaint.color = GlassUiTheme.accentColors(dark, GlassUiTheme.AccentRole.PRIMARY).second
            progressPaint.color = accentColor
            mainTextPaint.color = GlassUiTheme.textPrimaryColor(dark)
            subTextPaint.color = GlassUiTheme.textSecondaryColor(dark)

            canvas.drawArc(rect, 0f, 360f, false, trackPaint)

            val denom = maxOf(1, required).toFloat()
            val projSweep = (((targetProjected / denom) * 360f) * animFraction).coerceIn(0f, 360f)
            val actualSweep = (((targetAttended / denom) * 360f) * animFraction).coerceIn(0f, 360f)

            if (projSweep > actualSweep) {
                canvas.drawArc(rect, -90f + actualSweep, projSweep - actualSweep, false, projectedPaint)
            }
            if (actualSweep > 0f) {
                canvas.drawArc(rect, -90f, actualSweep, false, progressPaint)
            }

            val cx = width / 2f
            val cy = height / 2f
            goalMarkerPaint.color = if (targetAttended >= required) {
                GlassUiTheme.accentColors(dark, GlassUiTheme.AccentRole.SUCCESS).second
            } else {
                GlassUiTheme.textSecondaryColor(dark)
            }
            canvas.drawCircle(cx, pad, 7f, goalMarkerPaint)

            val displayPct = (targetPct * animFraction).roundToInt()
            canvas.drawText("$targetAttended / $required", cx, cy + 8f, mainTextPaint)
            canvas.drawText("days ($displayPct%)", cx, cy + 48f, subTextPaint)
        }
    }

    private class TrendBarChartView(context: Context) : View(context) {
        private var items: List<MonthlyTrendPoint> = emptyList()
        private var dark = false
        private var barFractions: FloatArray = FloatArray(0)
        private val barAnimators = mutableListOf<ValueAnimator>()

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = GlassUiTheme.createDashEffect(floatArrayOf(12f, 8f))
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 26f
        }

        fun setData(points: List<MonthlyTrendPoint>, isDark: Boolean, animate: Boolean = true) {
            this.items = points
            this.dark = isDark

            barAnimators.forEach { it.cancel() }
            barAnimators.clear()

            if (points.isEmpty()) {
                barFractions = FloatArray(0)
                invalidate()
                return
            }

            if (animate) {
                barFractions = FloatArray(points.size) { 0f }
                points.indices.forEach { idx ->
                    val va = ValueAnimator.ofFloat(0f, 1f).apply {
                        startDelay = (idx * 55).toLong()
                        duration = 460L
                        interpolator = OvershootInterpolator(0.75f)
                        addUpdateListener { anim ->
                            barFractions[idx] = (anim.animatedValue as Float).coerceAtLeast(0f)
                            invalidate()
                        }
                        start()
                    }
                    barAnimators.add(va)
                }
            } else {
                barFractions = FloatArray(points.size) { 1f }
                invalidate()
            }
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
            targetLinePaint.color = GlassUiTheme.trendTargetLineColor(dark)
            canvas.drawLine(16f, targetY, width - 16f, targetY, targetLinePaint)

            labelPaint.color = GlassUiTheme.textSecondaryColor(dark)
            valuePaint.color = GlassUiTheme.textPrimaryColor(dark)

            items.forEachIndexed { idx, pt ->
                val frac = barFractions.getOrElse(idx) { 1f }
                val cx = colWidth * idx + colWidth / 2f
                val barHalfW = (colWidth * 0.26f).coerceAtMost(32f)
                val ratio = ((pt.actualPercent / maxPct.toFloat()) * frac).coerceIn(0f, 1.1f)
                val barTop = topMargin + chartHeight * (1f - ratio)
                val barBottom = topMargin + chartHeight

                barPaint.color = GlassUiTheme.trendBarColor(dark, pt.actualPercent >= pt.targetPercent)
                val barRect = RectF(cx - barHalfW, barTop.coerceAtMost(barBottom - 6f), cx + barHalfW, barBottom)
                canvas.drawRoundRect(barRect, 10f, 10f, barPaint)

                val shownPct = (pt.actualPercent * frac).roundToInt()
                canvas.drawText("${shownPct}%", cx, barTop - 10f, valuePaint)
                canvas.drawText(pt.shortMonth, cx, height - 12f, labelPaint)
            }
        }
    }
}
