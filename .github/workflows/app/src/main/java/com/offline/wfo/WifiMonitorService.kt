package com.offline.wfo

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class WifiMonitorService : Service() {

    companion object {
        const val CHANNEL_MONITOR = "wfo_monitor_channel"
        const val CHANNEL_ALERTS = "wfo_alerts_channel"
        const val ACTION_DATA_UPDATED = "com.offline.wfo.DATA_UPDATED"
        const val ACTION_QUICK_LOG_TODAY = "com.offline.wfo.QUICK_LOG_TODAY"
        const val ACTION_REFRESH_STATUS = "com.offline.wfo.REFRESH_STATUS"
    }

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var store: AttendanceStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStayRunnable: Runnable? = null
    private var isConnectedToOfficeWifi = false
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                evaluateConnectedSsid(extractSsid(caps))
            }
        }

        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                evaluateConnectedSsid(extractSsid(caps))
            }
        }

        override fun onLost(network: Network) {
            handleWifiDisconnected()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        store = AttendanceStore(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannels()

        try {
            startForeground(101, buildPersistentNotification())
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        callbackRegistered = true

        ReminderReceiver.scheduleNextCheckpoint(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission() || !callbackRegistered) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_QUICK_LOG_TODAY -> {
                if (shouldShowQuickLogAction()) {
                    val isNew = store.recordOfficePresence()
                    if (isNew) {
                        notifyCheckInSuccess("Manual Quick-Log")
                    }
                }
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
            }
            ACTION_REFRESH_STATUS -> {
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
            }
            else -> {
                checkCurrentActiveWifi()
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
            }
        }

        ReminderReceiver.evaluateReminders(this)
        ReminderReceiver.scheduleNextCheckpoint(this)
        return START_STICKY
    }

    override fun onDestroy() {
        cancelPendingStayCheck()
        if (callbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun checkCurrentActiveWifi() {
        val activeCaps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            evaluateConnectedSsid(extractSsid(activeCaps))
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSsid(caps: NetworkCapabilities): String {
        var rawSsid: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            rawSsid = wifiInfo?.ssid
        }
        if (rawSsid.isNullOrEmpty() || rawSsid == "<unknown ssid>") {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            rawSsid = wifiManager.connectionInfo?.ssid
        }
        return rawSsid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    }

    private fun evaluateConnectedSsid(connectedSsid: String) {
        if (connectedSsid.isEmpty() || connectedSsid == "<unknown ssid>") return

        val targets = store.getTargetSsids()
        val matched = targets.any { it.equals(connectedSsid, ignoreCase = true) }

        if (matched) {
            isConnectedToOfficeWifi = true
            val thresholdMet = store.evaluateWifiStayThreshold()

            if (thresholdMet) {
                cancelPendingStayCheck()
                val isNewToday = store.recordOfficePresence()
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))

                if (isNewToday) {
                    notifyCheckInSuccess(connectedSsid)
                }
            } else {
                val mins = store.getMinStayMinutes()
                updatePersistentNotification("Connected to $connectedSsid • Verifying ${mins}m stay...")
                scheduleStayCompletionCheck(mins)
            }
        } else {
            handleWifiDisconnected()
        }
    }

    private fun handleWifiDisconnected() {
        cancelPendingStayCheck()
        if (isConnectedToOfficeWifi) {
            store.updateLastSeenOnly()
            isConnectedToOfficeWifi = false
            sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
        }
        if (store.getDayType(LocalDate.now()) != DayType.OFFICE) {
            store.clearPendingWifiStay()
        }
        updatePersistentNotification()
    }

    private fun scheduleStayCompletionCheck(minStayMinutes: Int) {
        if (pendingStayRunnable != null) return
        val delayMs = (minStayMinutes * 60_000L) + 2_000L
        val runnable = Runnable {
            pendingStayRunnable = null
            checkCurrentActiveWifi()
        }
        pendingStayRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingStayCheck() {
        pendingStayRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStayRunnable = null
    }

    private fun notifyCheckInSuccess(sourceLabel: String) {
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        val stats = store.getMonthStats(ym, today)

        val checkInBody = if (stats.remainingDays == 0) {
            "Office logged (${stats.attendedDays}/${stats.requiredDays} days). ${stats.targetPercent}% monthly target met!"
        } else {
            "Office logged (${stats.attendedDays}/${stats.requiredDays} days). Need ${stats.remainingDays} more day(s) • ${maxOf(0, stats.bufferDays)} WFH buffer day(s) left."
        }

        if (store.claimOnceFlag("notif_checkin_$today")) {
            sendAlertNotification(this, "WFO Checked In ($sourceLabel)", checkInBody)
        }

        if (stats.remainingDays == 0 && store.claimOnceFlag("notif_target_met_$ym")) {
            sendAlertNotification(
                this,
                "🎉 ${stats.targetPercent}% Monthly Target Completed!",
                "You've reached ${stats.attendedDays}/${stats.requiredDays} office days for ${stats.monthLabel}. Safe to WFH for the rest of the month."
            )
        } else if (stats.remainingDays > 0 && stats.bufferDays == 0 && store.claimOnceFlag("notif_zero_buffer_$today")) {
            sendAlertNotification(
                this,
                "🚨 Zero WFH Buffer Remaining",
                "You need ${stats.remainingDays} more office day(s) and have ${stats.availableWeekdaysLeft} working weekday(s) left this month."
            )
        }
    }

    private fun shouldShowQuickLogAction(
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): Boolean {
        val isWeekday = today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
        if (!isWeekday) return false

        val inWindow = !now.isBefore(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(11, 0))
        if (!inWindow) return false

        return store.getDayType(today) != DayType.OFFICE
    }

    private fun buildPersistentNotification(overrideText: String? = null): Notification {
        val stats = store.getMonthStats()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val quickLogIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WifiMonitorService::class.java).setAction(ACTION_QUICK_LOG_TODAY),
            PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSummary = if (stats.remainingDays == 0) {
            "${stats.monthLabel}: ${stats.attendedDays}/${stats.requiredDays} days • Target Met!"
        } else {
            "${stats.monthLabel}: ${stats.attendedDays}/${stats.requiredDays} days • ${maxOf(0, stats.bufferDays)} buffer day(s) left"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("WFO Wi-Fi Monitor Active")
            .setContentText(overrideText ?: defaultSummary)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)

        if (shouldShowQuickLogAction()) {
            builder.addAction(
                android.R.drawable.ic_input_add,
                "Log Today",
                quickLogIntent
            )
        }

        return builder.build()
    }

    private fun updatePersistentNotification(overrideText: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(101, buildPersistentNotification(overrideText))
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Background Wi-Fi Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Attendance & Plan Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}

fun sendAlertNotification(context: Context, title: String, body: String) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(
            WifiMonitorService.CHANNEL_ALERTS,
            "Attendance & Plan Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
    )
    val openIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )
    val notif = NotificationCompat.Builder(context, WifiMonitorService.CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(openIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    nm.notify(System.currentTimeMillis().toInt(), notif)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    context.startForegroundService(Intent(context, WifiMonitorService::class.java))
                }
            }
            ReminderReceiver.scheduleNextCheckpoint(context)
            WfoWidgetProvider.updateAllWidgets(context)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        evaluateReminders(context)
        scheduleNextCheckpoint(context)
        WfoWidgetProvider.updateAllWidgets(context)

        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                context.startForegroundService(
                    Intent(context, WifiMonitorService::class.java)
                        .setAction(WifiMonitorService.ACTION_REFRESH_STATUS)
                )
            }
        }
    }

    companion object {
        fun evaluateReminders(context: Context) {
            val store = AttendanceStore(context)
            val now = LocalTime.now()
            val today = LocalDate.now()
            val todayType = store.getDayType(today)
            val isWeekday = today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY

            // 1. Morning-Of Planned Office Reminder (07:30 - 09:30)
            if (store.isPlannedRemindersEnabled() &&
                todayType == DayType.PLANNED &&
                !now.isBefore(LocalTime.of(7, 30)) &&
                now.isBefore(LocalTime.of(9, 30)) &&
                store.claimOnceFlag("notif_plan_morning_$today")
            ) {
                sendAlertNotification(
                    context,
                    "📅 Planned Office Day Today",
                    "Today is marked as a Planned Office day. Connect to office Wi-Fi upon arrival to auto-confirm."
                )
            }

            // 2. Morning Pace Nudge on Unscheduled WFH Days (08:30 - 10:30)
            if (store.isMorningNudgeEnabled() &&
                isWeekday &&
                todayType == DayType.NONE &&
                !now.isBefore(LocalTime.of(8, 30)) &&
                now.isBefore(LocalTime.of(10, 30))
            ) {
                val stats = store.getMonthStats(YearMonth.from(today), today)
                if (stats.remainingDays > 0 && stats.bufferDays <= 2 &&
                    store.claimOnceFlag("notif_pace_nudge_$today")
                ) {
                    val bufTxt = if (stats.bufferDays <= 0) "no WFH buffer left" else "only ${stats.bufferDays} WFH buffer day(s) left"
                    sendAlertNotification(
                        context,
                        "⏱️ ${stats.targetPercent}% WFO Pace Nudge",
                        "You still need ${stats.remainingDays} office day(s) this month ($bufTxt)."
                    )
                }
            }

            // 3. Missed-Plan Rollover Alert (11:30 - 14:30)
            if (store.isPlannedRemindersEnabled() &&
                todayType == DayType.PLANNED &&
                !now.isBefore(LocalTime.of(11, 30)) &&
                now.isBefore(LocalTime.of(14, 30)) &&
                store.claimOnceFlag("notif_plan_missed_$today")
            ) {
                sendAlertNotification(
                    context,
                    "⚠️ Planned Office Visit Not Detected",
                    "No office Wi-Fi detected yet for today's planned visit. Open WFO Tracker to reschedule another day if plans changed."
                )
            }

            // 4. Night-Before Prep Alert (20:00 - 22:00)
            val tomorrow = today.plusDays(1)
            if (store.isPlannedRemindersEnabled() &&
                store.getDayType(tomorrow) == DayType.PLANNED &&
                !now.isBefore(LocalTime.of(20, 0)) &&
                now.isBefore(LocalTime.of(22, 0)) &&
                store.claimOnceFlag("notif_plan_eve_$today")
            ) {
                sendAlertNotification(
                    context,
                    "🎒 Office Day Tomorrow",
                    "Reminder: Tomorrow ($tomorrow) is scheduled as a Planned Office day."
                )
            }
        }

        fun scheduleNextCheckpoint(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = LocalDateTime.now()
            val checkpoints = listOf(
                LocalTime.of(7, 35),
                LocalTime.of(8, 35),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                LocalTime.of(11, 35),
                LocalTime.of(20, 5)
            )

            var nextTrigger: LocalDateTime? = null
            for (cp in checkpoints) {
                val candidate = now.toLocalDate().atTime(cp)
                if (candidate.isAfter(now.plusMinutes(1))) {
                    nextTrigger = candidate
                    break
                }
            }
            if (nextTrigger == null) {
                nextTrigger = now.toLocalDate().plusDays(1).atTime(checkpoints.first())
            }

            val triggerMillis = nextTrigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                context,
                301,
                Intent(context, ReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            }
        }
    }
}
