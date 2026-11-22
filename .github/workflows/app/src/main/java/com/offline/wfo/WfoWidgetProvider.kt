package com.offline.wfo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class WfoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WfoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                updateSingleWidget(context, manager, id)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val store = AttendanceStore(context)
            val stats = store.getMonthStats()

            val views = RemoteViews(context.packageName, R.layout.widget_wfo)
            views.setTextViewText(
                R.id.widget_month,
                stats.monthLabel.uppercase(Locale.getDefault())
            )
            views.setTextViewText(
                R.id.widget_count,
                "${stats.attendedDays} / ${stats.requiredDays} Days (${stats.actualPercent}%)"
            )

            val (statusTxt, statusColor) = when {
                stats.remainingDays == 0 ->
                    "Target met! Safe to WFH" to "#34D399"
                stats.bufferDays <= 0 ->
                    "${stats.remainingDays} needed • 0 buffer days!" to "#F87171"
                else ->
                    "${stats.remainingDays} needed • ${stats.bufferDays} buffer days" to "#FBBF24"
            }
            views.setTextViewText(R.id.widget_status, statusTxt)
            views.setTextColor(R.id.widget_status, Color.parseColor(statusColor))

            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            val today = LocalDate.now()
            val now = LocalTime.now()
            val isWeekday = today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
            val inWindow = !now.isBefore(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(11, 0))
            val canQuickLog = isWeekday && inWindow && store.getDayType(today) != DayType.OFFICE

            if (canQuickLog) {
                views.setViewVisibility(R.id.widget_quick_log, View.VISIBLE)
                val quickIntent = Intent(context, WifiMonitorService::class.java)
                    .setAction(WifiMonitorService.ACTION_QUICK_LOG_TODAY)
                val quickPi = PendingIntent.getService(
                    context,
                    4,
                    quickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_quick_log, quickPi)
            } else {
                views.setViewVisibility(R.id.widget_quick_log, View.GONE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
