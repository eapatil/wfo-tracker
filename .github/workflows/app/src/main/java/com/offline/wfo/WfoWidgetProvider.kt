package com.offline.wfo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
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

class WfoWidgetProvider : AppWidgetProvider() {

    private data class DaySlot(
        val viewId: Int,
        val letter: String
    )

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.offline.wfo.ACTION_WIDGET_REFRESH"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WfoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    updateSingleWidget(context, manager, id)
                }
            }
        }

        private fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val store = AttendanceStore(context)
            store.performDailyRolloverIfNeeded()

            val isDark = GlassUiTheme.resolveWidgetIsDark(context)
            val textPrimary = GlassUiTheme.textPrimaryColor(isDark)
            val textSecondary = GlassUiTheme.textSecondaryColor(isDark)

            val today = LocalDate.now()
            val ym = YearMonth.from(today)
            val stats = store.getMonthStats(ym, today)
            val todayType = store.getDayType(today)
            val isOnOffice = store.wasConnectedToOfficeWifi()
            val activeSrc = store.getActiveOfficeSsid()
            val mode = store.getTrackingMode()
            val radius = store.getGeofenceRadiusMeters()
            val hasLoc = store.hasOfficeLocationConfigured()

            val distMeters: Float? = if (hasLoc && store.hasLastKnownLocation()) {
                store.distanceToOfficeMeters(store.getLastKnownLat(), store.getLastKnownLng())
            } else {
                null
            }

            val isInsideRadius = hasLoc &&
                (mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) &&
                ((distMeters != null && distMeters <= radius) || (distMeters == null && isOnOffice))

            val views = RemoteViews(context.packageName, R.layout.wfo_widget)

            // Frosted Glass Card Background (Light vs Dark)
            val options = manager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280) ?: 280
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) ?: 110
            val density = context.resources.displayMetrics.density
            views.setImageViewBitmap(
                R.id.widget_glass_bg,
                GlassUiTheme.createWidgetGlassBitmap(
                    widthPx = (minWidthDp * density).toInt().coerceAtLeast(360),
                    heightPx = (minHeightDp * density).toInt().coerceAtLeast(160),
                    isDark = isDark,
                    radiusPx = GlassUiTheme.cardRadiusPx
                )
            )

            // Apply theme-aware typography colors
            views.setTextColor(R.id.widget_month_title, textPrimary)
            views.setTextColor(R.id.widget_hero_ratio, textPrimary)
            views.setTextColor(R.id.widget_pace_text, textSecondary)
            views.setTextColor(R.id.widget_source_detail, textSecondary)
            views.setTextColor(R.id.widget_updated_time, textSecondary)

            // Header: Current Day & Date + Sync Timestamp
            val todayStamp = today.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.getDefault()))
            val shortMonthYear = ym.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
            val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            views.setTextViewText(R.id.widget_month_title, "OfficePace • $todayStamp")
            views.setTextViewText(R.id.widget_updated_time, "Updated $nowTime")

            // Dedicated Sub-Line
            when {
                isInsideRadius && distMeters != null -> {
                    views.setViewVisibility(R.id.widget_source_detail, View.VISIBLE)
                    views.setTextViewText(
                        R.id.widget_source_detail,
                        "📍 Inside ${radius}m office zone (~${distMeters.toInt()}m away)"
                    )
                }
                isInsideRadius -> {
                    views.setViewVisibility(R.id.widget_source_detail, View.VISIBLE)
                    views.setTextViewText(
                        R.id.widget_source_detail,
                        "📍 Inside ${radius}m office zone"
                    )
                }
                isOnOffice && activeSrc.isNotEmpty() -> {
                    views.setViewVisibility(R.id.widget_source_detail, View.VISIBLE)
                    views.setTextViewText(R.id.widget_source_detail, "📍 $activeSrc ($shortMonthYear)")
                }
                else -> {
                    views.setViewVisibility(R.id.widget_source_detail, View.GONE)
                }
            }

            // Live Today Status Pill
            val disconnectedHint = when (mode) {
                TrackingMode.LOCATION_ONLY -> "Not at office"
                TrackingMode.WIFI_ONLY -> "Not on Wi-Fi"
                TrackingMode.BOTH -> "WFH Today"
            }

            val (pillText, statusKind, pillDrawableRes) = when {
                isOnOffice -> Triple("🟢 At Office", GlassUiTheme.StatusKind.SUCCESS, R.drawable.widget_pill_green)
                todayType == DayType.OFFICE -> Triple("✅ Office Logged", GlassUiTheme.StatusKind.SUCCESS, R.drawable.widget_pill_green)
                todayType == DayType.PLANNED -> Triple("📅 Planned Office", GlassUiTheme.StatusKind.INFO, R.drawable.widget_pill_indigo)
                todayType == DayType.LEAVE -> Triple("🌴 On Leave", GlassUiTheme.StatusKind.WARNING, R.drawable.widget_pill_amber)
                todayType == DayType.HOLIDAY -> Triple("🎉 Holiday", GlassUiTheme.StatusKind.HOLIDAY, R.drawable.widget_pill_purple)
                else -> Triple("🏠 $disconnectedHint", null, R.drawable.widget_pill_neutral)
            }

            views.setTextViewText(R.id.widget_status_pill, pillText)
            views.setTextColor(
                R.id.widget_status_pill,
                GlassUiTheme.widgetStatusPillTextColor(isDark, statusKind)
            )
            views.setInt(R.id.widget_status_pill, "setBackgroundResource", pillDrawableRes)

            // Hero Progress Ratio & Bar
            views.setTextViewText(
                R.id.widget_hero_ratio,
                "${stats.attendedDays} / ${stats.requiredDays} days (${stats.actualPercent}%)"
            )
            val progressMax = maxOf(1, stats.requiredDays)
            val progressVal = stats.attendedDays.coerceIn(0, progressMax)
            views.setProgressBar(R.id.widget_progress_bar, progressMax, progressVal, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val plannedCoversTarget = stats.futurePlannedDays > 0 &&
                    stats.projectedAttendedDays >= stats.requiredDays
                val progressTint = GlassUiTheme.widgetProgressColor(
                    isDark = isDark,
                    remainingDays = stats.remainingDays,
                    plannedCoversTarget = plannedCoversTarget,
                    bufferDays = stats.bufferDays
                )
                views.setColorStateList(
                    R.id.widget_progress_bar,
                    "setProgressTintList",
                    ColorStateList.valueOf(progressTint)
                )
                views.setColorStateList(
                    R.id.widget_progress_bar,
                    "setProgressBackgroundTintList",
                    ColorStateList.valueOf(GlassUiTheme.ringTrackColor(isDark))
                )
            }

            // Pace & Buffer Line
            val paceText = when {
                stats.remainingDays == 0 ->
                    "🎉 ${stats.targetPercent}% target met — safe to WFH"
                stats.bufferDays < 0 ->
                    "⚠️ Need ${stats.remainingDays}d (${stats.availableWeekdaysLeft} left)"
                stats.bufferDays == 0 ->
                    "🚨 0 buffer — attend all ${stats.availableWeekdaysLeft} left"
                else ->
                    "${stats.remainingDays} needed • ${stats.bufferDays} WFH buffer left"
            }
            views.setTextViewText(R.id.widget_pace_text, paceText)

            // This Week 5-Day Mon–Fri Strip (with Today underlined)
            val monday = today.with(DayOfWeek.MONDAY)
            val daySlots = listOf(
                DaySlot(R.id.widget_day_mon, "M"),
                DaySlot(R.id.widget_day_tue, "T"),
                DaySlot(R.id.widget_day_wed, "W"),
                DaySlot(R.id.widget_day_thu, "T"),
                DaySlot(R.id.widget_day_fri, "F")
            )
            daySlots.forEachIndexed { idx, slot ->
                val d = monday.plusDays(idx.toLong())
                val t = store.getDayType(d)
                val isToday = d == today
                val label: CharSequence = if (isToday) {
                    SpannableString(slot.letter).apply {
                        setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    slot.letter
                }
                val dayDrawableRes = when (t) {
                    DayType.OFFICE -> R.drawable.widget_day_office
                    DayType.PLANNED -> R.drawable.widget_day_planned
                    DayType.LEAVE -> R.drawable.widget_day_leave
                    DayType.HOLIDAY -> R.drawable.widget_day_holiday
                    DayType.NONE -> R.drawable.widget_day_none
                }
                views.setTextViewText(slot.viewId, label)
                views.setTextColor(
                    slot.viewId,
                    GlassUiTheme.widgetDayTextColor(isDark, t, isToday)
                )
                views.setInt(slot.viewId, "setBackgroundResource", dayDrawableRes)
            }

            // Tap Card Body -> Open MainActivity
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root_container, openPending)

            // Tap Refresh Icon -> Trigger Immediate Background Wi-Fi / Geofence Check
            val refreshIntent = Intent(context, WfoWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                900 + appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            manager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateSingleWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                runCatching {
                    val serviceIntent = Intent(context, WifiMonitorService::class.java).apply {
                        action = WifiMonitorService.ACTION_REFRESH_STATUS
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
                updateAllWidgets(context)
            }
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_CONFIGURATION_CHANGED -> updateAllWidgets(context)
        }
    }
}
