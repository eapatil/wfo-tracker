package com.offline.wfo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class WfoWidgetProvider : AppWidgetProvider() {

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
            val today = LocalDate.now()
            val ym = YearMonth.from(today)
            val stats = store.getMonthStats(ym, today)
            val todayType = store.getDayType(today)
            val isOnOffice = store.wasConnectedToOfficeWifi()
            val activeSrc = store.getActiveOfficeSsid()
            val mode = store.getTrackingMode()

            val views = RemoteViews(context.packageName, R.layout.wfo_widget)

            // Header: Month & Sync Timestamp
            val shortMonthYear = ym.format(DateTimeFormatter.ofPattern("MMM yyyy"))
            val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            views.setTextViewText(R.id.widget_month_title, "WFO • $shortMonthYear")
            views.setTextViewText(R.id.widget_updated_time, "Updated $nowTime")

            // Dedicated Sub-Line for Long Office / Wi-Fi Name
            if (isOnOffice && activeSrc.isNotEmpty()) {
                views.setViewVisibility(R.id.widget_source_detail, View.VISIBLE)
                views.setTextViewText(R.id.widget_source_detail, "📍 $activeSrc")
            } else {
                views.setViewVisibility(R.id.widget_source_detail, View.GONE)
            }

            // Live Today Status Pill
            val disconnectedHint = when (mode) {
                TrackingMode.LOCATION_ONLY -> "Not at office"
                TrackingMode.WIFI_ONLY -> "Not on office Wi-Fi"
                TrackingMode.BOTH -> "WFH Today"
            }

            when {
                isOnOffice -> {
                    views.setTextViewText(R.id.widget_status_pill, "🟢 At Office")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#065F46"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_green)
                }
                todayType == DayType.OFFICE -> {
                    views.setTextViewText(R.id.widget_status_pill, "✅ Office Logged")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#065F46"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_green)
                }
                todayType == DayType.PLANNED -> {
                    views.setTextViewText(R.id.widget_status_pill, "📅 Planned Office")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#3730A3"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_indigo)
                }
                todayType == DayType.LEAVE -> {
                    views.setTextViewText(R.id.widget_status_pill, "🌴 On Leave")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#92400E"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_amber)
                }
                todayType == DayType.HOLIDAY -> {
                    views.setTextViewText(R.id.widget_status_pill, "🎉 Holiday")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#5B21B6"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_purple)
                }
                else -> {
                    views.setTextViewText(R.id.widget_status_pill, "🏠 $disconnectedHint")
                    views.setInt(R.id.widget_status_pill, "setTextColor", Color.parseColor("#475569"))
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.widget_pill_neutral)
                }
            }

            // Hero Progress Ratio & Bar
            views.setTextViewText(
                R.id.widget_hero_ratio,
                "${stats.attendedDays} / ${stats.requiredDays} days (${stats.actualPercent}%)"
            )
            val progressMax = maxOf(1, stats.requiredDays)
            val progressVal = stats.attendedDays.coerceIn(0, progressMax)
            views.setProgressBar(R.id.widget_progress_bar, progressMax, progressVal, false)

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

            // This Week 5-Day Mon–Fri Strip
            val monday = today.with(DayOfWeek.MONDAY)
            val dayIds = listOf(
                R.id.widget_day_mon to "M",
                R.id.widget_day_tue to "T",
                R.id.widget_day_wed to "W",
                R.id.widget_day_thu to "T",
                R.id.widget_day_fri to "F"
            )
            dayIds.forEachIndexed { idx, (viewId, letter) ->
                val d = monday.plusDays(idx.toLong())
                val t = store.getDayType(d)
                views.setTextViewText(viewId, letter)
                when (t) {
                    DayType.OFFICE -> {
                        views.setInt(viewId, "setTextColor", Color.WHITE)
                        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_office)
                    }
                    DayType.PLANNED -> {
                        views.setInt(viewId, "setTextColor", Color.parseColor("#3730A3"))
                        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_planned)
                    }
                    DayType.LEAVE -> {
                        views.setInt(viewId, "setTextColor", Color.WHITE)
                        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_leave)
                    }
                    DayType.HOLIDAY -> {
                        views.setInt(viewId, "setTextColor", Color.WHITE)
                        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_holiday)
                    }
                    DayType.NONE -> {
                        views.setInt(viewId, "setTextColor", Color.parseColor("#64748B"))
                        views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_none)
                    }
                }
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
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
    }
}
