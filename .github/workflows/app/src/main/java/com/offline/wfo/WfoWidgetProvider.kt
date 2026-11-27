package com.offline.wfo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class WfoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.offline.wfo.ACTION_WIDGET_REFRESH"
        const val EXTRA_INSPECT_DATE = "com.offline.wfo.EXTRA_INSPECT_DATE"
        private const val WIDGET_PREFS = "officepace_ui_prefs"
        private const val KEY_ARRIVAL_START_EPOCH_MS = "widget_arrival_start_epoch_ms"

        fun updateAllWidgets(context: Context, syncing: Boolean = false) {
            val awm = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WfoWidgetProvider::class.java)
            val ids = awm.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val provider = WfoWidgetProvider()
                ids.forEach { id ->
                    val options = runCatching { awm.getAppWidgetOptions(id) }.getOrNull()
                    provider.updateSingleWidget(context, awm, id, options, syncing = syncing)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val options = runCatching { appWidgetManager.getAppWidgetOptions(id) }.getOrNull()
            updateSingleWidget(context, appWidgetManager, id, options, syncing = false)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateSingleWidget(context, appWidgetManager, appWidgetId, newOptions, syncing = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            updateAllWidgets(context, syncing = true)
            performImmediateWidgetCheck(context)
            updateAllWidgets(context, syncing = false)
        }
    }

    private fun performImmediateWidgetCheck(context: Context) {
        val store = AttendanceStore(context)
        store.performDailyRolloverIfNeeded()
        val hasFine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val today = LocalDate.now()
        val mode = store.getTrackingMode()
        val liveSsid = readConnectedSsid(context)
        var matchedAny = false

        if ((mode == TrackingMode.WIFI_ONLY || mode == TrackingMode.BOTH) && liveSsid.isNotEmpty()) {
            if (store.getTargetSsids().any { it.equals(liveSsid, ignoreCase = true) }) {
                val isNew = store.recordOfficePresence(liveSsid, today)
                store.setWasConnectedToOfficeWifi(true)
                store.setActiveOfficeSsid(liveSsid)
                matchedAny = true
                if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_$today")) {
                    sendAlertNotification(context, "✅ Office Day Logged ($liveSsid)", "Detected via widget refresh.")
                }
            }
        }

        if ((mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) && store.hasOfficeLocationConfigured()) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val bestLoc = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }

            if (bestLoc != null) {
                store.setLastKnownLocation(bestLoc.latitude, bestLoc.longitude)
                val dist = store.distanceToOfficeMeters(bestLoc.latitude, bestLoc.longitude)
                if (dist != null && dist <= store.getGeofenceRadiusMeters()) {
                    val label = store.getOfficeLocationName().ifEmpty { "Office Geofence" }
                    val isNew = store.recordOfficePresence(label, today)
                    store.setWasConnectedToOfficeWifi(true)
                    store.setActiveOfficeSsid(label)
                    matchedAny = true
                    if (isNew && store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_$today")) {
                        sendAlertNotification(context, "✅ Office Day Logged ($label)", "Within ${store.getGeofenceRadiusMeters()}m zone.")
                    }
                }
            }
        }

        if (!matchedAny && store.wasConnectedToOfficeWifi()) {
            val activeSrc = store.getActiveOfficeSsid().ifEmpty { "Office" }
            store.updateLastSeenTimestamp(activeSrc, today)
        }

        store.backupNowAsync()
        ReminderReceiver.scheduleNextCheckpoint(context)
        runCatching {
            context.startForegroundService(
                Intent(context, WifiMonitorService::class.java)
                    .setAction(WifiMonitorService.ACTION_REFRESH_STATUS)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readConnectedSsid(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val raw = wm.connectionInfo?.ssid ?: ""
        if (raw.isEmpty() || raw == "<unknown ssid>") return ""
        return raw.removePrefix("\"").removeSuffix("\"")
    }

    private fun updateSingleWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle?,
        syncing: Boolean
    ) {
        runCatching {
            val isDark = GlassUiTheme.resolveWidgetIsDark(context)
            val store = AttendanceStore(context)
            store.performDailyRolloverIfNeeded()

            val today = LocalDate.now()
            val nowTime = LocalTime.now()
            val ym = YearMonth.from(today)
            val stats = store.getMonthStats(ym, today)
            val rhythm = store.getRhythmStats(today)

            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) ?: 140
            val isCompact = minWidthDp < 245 || minHeightDp < 115

            val views = RemoteViews(context.packageName, R.layout.wfo_widget)

            val bmpW = ((minWidthDp.coerceIn(180, 480)) * context.resources.displayMetrics.density)
                .roundToInt()
                .coerceIn(160, GlassUiTheme.maxWidgetBitmapWidthPx)
            val bmpH = ((minHeightDp.coerceIn(95, 220)) * context.resources.displayMetrics.density)
                .roundToInt()
                .coerceIn(96, GlassUiTheme.maxWidgetBitmapHeightPx)
            views.setImageViewBitmap(
                R.id.widget_bg_glass,
                GlassUiTheme.createWidgetGlassBitmap(bmpW, bmpH, isDark)
            )

            val textPrimary = GlassUiTheme.textPrimaryColor(isDark)
            val textSecondary = GlassUiTheme.textSecondaryColor(isDark)

            val dateLabel = today.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.getDefault()))
            views.setTextViewText(R.id.widget_header_date, if (isCompact) dateLabel else "OfficePace • $dateLabel")
            views.setTextColor(R.id.widget_header_date, textPrimary)

            val isConnected = store.wasConnectedToOfficeWifi()
            val todayType = store.getDayType(today)
            val (startMin, _) = store.getLearnedArrivalWindowMinutes()
            val arrivalFormatted = String.format(Locale.US, "%02d:%02d", startMin / 60, startMin % 60)
            val graceCheckFormatted = store.getPlannedNotArrivedCheckTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))

            val statusPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            val statusLabel: String
            val statusKind: GlassUiTheme.StatusKind?

            if (isConnected) {
                statusLabel = if (isCompact) "At Office" else "✓ At Office"
                statusKind = GlassUiTheme.StatusKind.SUCCESS
                val existingBase = statusPrefs.getLong(KEY_ARRIVAL_START_EPOCH_MS, 0L)
                val baseElapsed = if (existingBase > 0L) {
                    existingBase
                } else {
                    val nowElapsed = SystemClock.elapsedRealtime()
                    statusPrefs.edit().putLong(KEY_ARRIVAL_START_EPOCH_MS, nowElapsed).apply()
                    nowElapsed
                }
                views.setViewVisibility(R.id.widget_office_chrono, View.VISIBLE)
                views.setChronometer(R.id.widget_office_chrono, baseElapsed, null, true)
                views.setTextColor(R.id.widget_office_chrono, GlassUiTheme.widgetStatusPillTextColor(isDark, statusKind))
            } else {
                statusPrefs.edit().remove(KEY_ARRIVAL_START_EPOCH_MS).apply()
                views.setViewVisibility(R.id.widget_office_chrono, View.GONE)

                when {
                    todayType == DayType.OFFICE -> {
                        statusLabel = "✓ Office Logged"
                        statusKind = GlassUiTheme.StatusKind.SUCCESS
                    }
                    todayType == DayType.HOLIDAY -> {
                        statusLabel = "🎉 Holiday"
                        statusKind = GlassUiTheme.StatusKind.HOLIDAY
                    }
                    todayType == DayType.LEAVE -> {
                        statusLabel = "🌴 On Leave"
                        statusKind = GlassUiTheme.StatusKind.WARNING
                    }
                    todayType == DayType.PLANNED && store.isInSmartCommuteWindow(nowTime, today) -> {
                        statusLabel = "⏳ Grace till $graceCheckFormatted"
                        statusKind = GlassUiTheme.StatusKind.INFO
                    }
                    todayType == DayType.PLANNED -> {
                        statusLabel = "📅 Planned • $arrivalFormatted"
                        statusKind = GlassUiTheme.StatusKind.INFO
                    }
                    store.isInSmartCommuteWindow(nowTime, today) -> {
                        statusLabel = "🕒 Window $arrivalFormatted"
                        statusKind = GlassUiTheme.StatusKind.INFO
                    }
                    else -> {
                        statusLabel = "🏠 WFH"
                        statusKind = null
                    }
                }
            }

            views.setTextViewText(R.id.widget_status_text, statusLabel)
            views.setTextColor(
                R.id.widget_status_text,
                GlassUiTheme.widgetStatusPillTextColor(isDark, statusKind)
            )

            views.setTextViewText(
                R.id.widget_hero_ratio,
                "${stats.attendedDays} / ${stats.requiredDays} days (${stats.actualPercent}%)"
            )
            views.setTextColor(R.id.widget_hero_ratio, textPrimary)

            val plannedCoversGap = stats.futurePlannedDays > 0 && stats.projectedAttendedDays >= stats.requiredDays
            val showProjectedBadge = !isCompact && minWidthDp >= 300 && stats.futurePlannedDays > 0
            if (showProjectedBadge) {
                views.setViewVisibility(R.id.widget_projected_badge, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_projected_badge,
                    "→ ${stats.projectedAttendedDays}/${stats.requiredDays} proj."
                )
                views.setTextColor(
                    R.id.widget_projected_badge,
                    if (plannedCoversGap) GlassUiTheme.statusSuccessText(isDark)
                    else GlassUiTheme.statusInfoText(isDark)
                )
            } else {
                views.setViewVisibility(R.id.widget_projected_badge, View.GONE)
            }

            val nextPlannedDate = (1..ym.lengthOfMonth())
                .map { ym.atDay(it) }
                .firstOrNull { !it.isBefore(today) && store.getDayType(it) == DayType.PLANNED }

            val paceText: String
            val paceColor: Int
            when {
                stats.remainingDays == 0 -> {
                    paceText = if (isCompact) "🎉 Met" else "🎉 Mandate met • WFH ok"
                    paceColor = GlassUiTheme.statusSuccessText(isDark)
                }
                stats.bufferDays <= 0 -> {
                    paceText = "🚨 0 buf • ${stats.remainingDays} left"
                    paceColor = GlassUiTheme.statusDangerText(isDark)
                }
                rhythm.consecutiveWfhWeekdays >= 3 && !isCompact -> {
                    paceText = "⚠️ ${rhythm.consecutiveWfhWeekdays} WFH days • ${stats.bufferDays} buf"
                    paceColor = GlassUiTheme.statusWarningText(isDark)
                }
                nextPlannedDate != null && !isCompact -> {
                    val pLabel = nextPlannedDate.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.getDefault()))
                    paceText = "📅 Next: $pLabel • ${stats.bufferDays} buf"
                    paceColor = GlassUiTheme.statusInfoText(isDark)
                }
                else -> {
                    paceText = "${stats.bufferDays} buffer • ${stats.remainingDays} left"
                    paceColor = if (stats.bufferDays == 1) {
                        GlassUiTheme.statusWarningText(isDark)
                    } else {
                        textSecondary
                    }
                }
            }
            views.setTextViewText(R.id.widget_pace_line, paceText)
            views.setTextColor(R.id.widget_pace_line, paceColor)

            val denom = maxOf(1, stats.requiredDays)
            val loggedPct = ((stats.attendedDays.toFloat() / denom) * 100f).roundToInt().coerceIn(0, 100)
            val projectedPct = ((stats.projectedAttendedDays.toFloat() / denom) * 100f).roundToInt().coerceIn(loggedPct, 100)
            views.setProgressBar(R.id.widget_progress_bar, 100, loggedPct, false)
            views.setInt(R.id.widget_progress_bar, "setSecondaryProgress", projectedPct)

            val monday = today.with(DayOfWeek.MONDAY)
            val dayViews = listOf(
                R.id.widget_day_mon to "M",
                R.id.widget_day_tue to "T",
                R.id.widget_day_wed to "W",
                R.id.widget_day_thu to "T",
                R.id.widget_day_fri to "F"
            )
            dayViews.forEachIndexed { idx, (viewId, letter) ->
                val d = monday.plusDays(idx.toLong())
                val dType = store.getDayType(d)
                val isToday = d == today

                val prefix = when (dType) {
                    DayType.PLANNED -> "[$letter]"
                    else -> letter
                }
                val label = if (isToday) {
                    SpannableString(prefix).apply {
                        setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    SpannableString(prefix)
                }
                views.setTextViewText(viewId, label)
                views.setTextColor(viewId, GlassUiTheme.widgetDayTextColor(isDark, dType, isToday))

                val inspectIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_INSPECT_DATE, d.toString())
                }
                val inspectPending = PendingIntent.getActivity(
                    context,
                    appWidgetId * 10 + idx + 1,
                    inspectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(viewId, inspectPending)
            }

            val timeFormatted = nowTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
            views.setTextViewText(
                R.id.widget_updated_time,
                if (syncing) "Syncing…" else "Updated $timeFormatted"
            )
            views.setTextColor(
                R.id.widget_updated_time,
                if (syncing) GlassUiTheme.statusInfoText(isDark) else textSecondary
            )
            views.setTextColor(R.id.widget_refresh_btn, textPrimary)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)

            val refreshIntent = Intent(context, WfoWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 5000,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
