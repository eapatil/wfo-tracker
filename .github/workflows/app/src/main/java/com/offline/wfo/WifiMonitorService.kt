package com.offline.wfo

import android.Manifest
import android.annotation.SuppressLint
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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import java.time.format.DateTimeFormatter

class WifiMonitorService : Service() {

    companion object {
        const val CHANNEL_MONITOR = "wfo_monitor_channel"
        const val CHANNEL_ALERTS = "wfo_alerts_channel"
        const val ACTION_DATA_UPDATED = "com.offline.wfo.DATA_UPDATED"
        const val ACTION_QUICK_LOG_TODAY = "com.offline.wfo.QUICK_LOG_TODAY"
        const val ACTION_REFRESH_STATUS = "com.offline.wfo.REFRESH_STATUS"
        const val ACTION_PLAN_MARK_LEAVE = "com.offline.wfo.PLAN_MARK_LEAVE"
        const val ACTION_PLAN_MOVE_TOMORROW = "com.offline.wfo.PLAN_MOVE_TOMORROW"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        const val EXTRA_DATE_STR = "extra_date_str"
    }

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var locationManager: LocationManager
    private lateinit var store: AttendanceStore
    private var callbackRegistered = false
    private var locationListenerRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                evaluateWifiChange(extractSsid(caps))
            }
        }

        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                evaluateWifiChange(extractSsid(caps))
            }
        }

        override fun onLost(network: Network) {
            reconcilePresenceState(forceWifiLost = true)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            store.setLastKnownLocation(location.latitude, location.longitude)
            reconcilePresenceState(latestLocation = location)
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
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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

        registerLocationUpdatesIfNeeded()
        ReminderReceiver.scheduleNextCheckpoint(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission() || !callbackRegistered) {
            stopSelf()
            return START_NOT_STICKY
        }

        store.performDailyRolloverIfNeeded()
        registerLocationUpdatesIfNeeded()

        when (intent?.action) {
            ACTION_QUICK_LOG_TODAY -> {
                if (shouldShowQuickLogAction()) {
                    val isNew = store.recordOfficePresence("Manual Quick-Log")
                    store.setWasConnectedToOfficeWifi(true)
                    store.setActiveOfficeSsid("Manual Quick-Log")
                    if (isNew && store.isConnectAlertEnabled()) {
                        notifyCheckInSuccess("Manual Quick-Log")
                    }
                }
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
            }
            ACTION_REFRESH_STATUS -> {
                reconcilePresenceState()
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
            }
            else -> {
                reconcilePresenceState()
                updatePersistentNotification()
                WfoWidgetProvider.updateAllWidgets(this)
            }
        }

        ReminderReceiver.evaluateReminders(this)
        ReminderReceiver.scheduleNextCheckpoint(this)
        return START_STICKY
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        if (locationListenerRegistered) {
            runCatching { locationManager.removeUpdates(locationListener) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdatesIfNeeded() {
        if (!hasLocationPermission()) return
        if (locationListenerRegistered) {
            runCatching { locationManager.removeUpdates(locationListener) }
            locationListenerRegistered = false
        }

        val inCommuteWindow = store.isInSmartCommuteWindow()
        val intervalMs = if (inCommuteWindow) 5 * 60 * 1000L else 15 * 60 * 1000L
        val minDistMeters = if (inCommuteWindow) 25f else 50f

        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        for (provider in providers) {
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    intervalMs,
                    minDistMeters,
                    locationListener,
                    Looper.getMainLooper()
                )
                locationListenerRegistered = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()?.let { loc ->
                if (best == null || loc.time > best!!.time) {
                    best = loc
                }
            }
        }
        return best
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

    private fun currentConnectedSsid(forceWifiLost: Boolean): String {
        if (forceWifiLost) return ""
        val activeCaps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            extractSsid(activeCaps)
        } else {
            ""
        }
    }

    private fun evaluateWifiChange(connectedSsid: String) {
        if (connectedSsid.isEmpty() || connectedSsid == "<unknown ssid>") return
        reconcilePresenceState(explicitSsid = connectedSsid)
    }

    private fun reconcilePresenceState(
        explicitSsid: String? = null,
        latestLocation: Location? = null,
        forceWifiLost: Boolean = false
    ) {
        val mode = store.getTrackingMode()
        val ssidNow = explicitSsid ?: currentConnectedSsid(forceWifiLost)

        val wifiMatched = (mode == TrackingMode.WIFI_ONLY || mode == TrackingMode.BOTH) &&
            ssidNow.isNotEmpty() &&
            store.getTargetSsids().any { it.equals(ssidNow, ignoreCase = true) }

        val loc = if (mode == TrackingMode.WIFI_ONLY && wifiMatched) {
            null
        } else {
            latestLocation ?: getBestLastKnownLocation()
        }
        if (loc != null) {
            store.setLastKnownLocation(loc.latitude, loc.longitude)
        }
        val radiusMeters = store.getGeofenceRadiusMeters()
        val distance = loc?.let { store.distanceToOfficeMeters(it.latitude, it.longitude) }
        val locationMatched = (mode == TrackingMode.LOCATION_ONLY || mode == TrackingMode.BOTH) &&
            distance != null &&
            distance <= radiusMeters

        val isPresentNow = wifiMatched || locationMatched
        val wasPresentBefore = store.wasConnectedToOfficeWifi()
        val prevSource = store.getActiveOfficeSsid()
        val sourceLabel = when {
            wifiMatched && ssidNow.isNotEmpty() -> ssidNow
            locationMatched -> store.getOfficeLocationName().ifEmpty { "Office Zone" }
            else -> ""
        }

        if (isPresentNow) {
            val isNewToday = store.recordOfficePresence(sourceLabel)
            store.setWasConnectedToOfficeWifi(true)
            store.setActiveOfficeSsid(sourceLabel)
            updatePersistentNotification()
            WfoWidgetProvider.updateAllWidgets(this)
            sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))

            if (isNewToday && store.isConnectAlertEnabled()) {
                notifyCheckInSuccess(sourceLabel)
            }
        } else if (wasPresentBefore) {
            store.updateLastSeenTimestamp(prevSource)
            store.setWasConnectedToOfficeWifi(false)
            store.setActiveOfficeSsid("")
            updatePersistentNotification()
            WfoWidgetProvider.updateAllWidgets(this)
            sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
            if (store.isDisconnectAlertEnabled()) {
                val leaveMsg = when (mode) {
                    TrackingMode.LOCATION_ONLY -> "Left ${store.getOfficeLocationName()} (${radiusMeters}m zone). Departure time recorded."
                    TrackingMode.WIFI_ONLY -> "Disconnected from office Wi-Fi. Disconnect time recorded."
                    TrackingMode.BOTH -> "Left office Wi-Fi / ${radiusMeters}m zone. Disconnect time recorded."
                }
                sendAlertNotification(this, "🔌 Office Disconnected", leaveMsg)
            }
        } else {
            updatePersistentNotification()
        }
    }

    private fun notifyCheckInSuccess(sourceLabel: String) {
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        val stats = store.getMonthStats(ym, today)

        val checkInBody = if (stats.remainingDays == 0) {
            "Today is confirmed as an Office day (${stats.attendedDays}/${stats.requiredDays} days). ${stats.targetPercent}% monthly target met!"
        } else {
            "Today is confirmed as an Office day (${stats.attendedDays}/${stats.requiredDays} days). Need ${stats.remainingDays} more day(s) • ${maxOf(0, stats.bufferDays)} WFH buffer left."
        }

        if (store.isConnectAlertEnabled() && store.claimOnceFlag("notif_checkin_$today")) {
            sendAlertNotification(this, "✅ Office Day Logged ($sourceLabel)", checkInBody)
        }

        if (store.isTargetBufferAlertEnabled()) {
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
    }

    fun shouldShowQuickLogAction(
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
        val stats = store.getMonthStats(YearMonth.now())
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val quickLogIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WifiMonitorService::class.java).setAction(ACTION_QUICK_LOG_TODAY),
            PendingIntent.FLAG_IMMUTABLE
        )

        val isConnected = store.wasConnectedToOfficeWifi()
        val activeSource = store.getActiveOfficeSsid()

        val titleText = if (isConnected) {
            val srcName = if (activeSource.isNotEmpty()) activeSource else "Office Zone"
            "🟢 Connected / At $srcName"
        } else {
            "WFO Tracker Active (${store.getTrackingMode().name})"
        }

        val defaultSummary = if (isConnected) {
            "Today logged • ${stats.attendedDays}/${stats.requiredDays} days (${stats.actualPercent}%)"
        } else if (stats.remainingDays == 0) {
            "${stats.monthLabel}: ${stats.attendedDays}/${stats.requiredDays} days • Target Met!"
        } else {
            "${stats.monthLabel}: ${stats.attendedDays}/${stats.requiredDays} days • ${maxOf(0, stats.bufferDays)} buffer day(s) left"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(titleText)
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
            NotificationChannel(CHANNEL_MONITOR, "Background WFO Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Attendance & Plan Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}

fun sendAlertNotification(
    context: Context,
    title: String,
    body: String,
    notifId: Int = System.currentTimeMillis().toInt(),
    actions: List<NotificationCompat.Action> = emptyList()
) {
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
    val builder = NotificationCompat.Builder(context, WifiMonitorService.CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(openIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    actions.forEach { builder.addAction(it) }
    nm.notify(notifId, builder.build())
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AttendanceStore(context).performDailyRolloverIfNeeded()
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
        val store = AttendanceStore(context)
        store.performDailyRolloverIfNeeded()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent?.action) {
            WifiMonitorService.ACTION_PLAN_MARK_LEAVE -> {
                val dateStr = intent.getStringExtra(WifiMonitorService.EXTRA_DATE_STR) ?: LocalDate.now().toString()
                val notifId = intent.getIntExtra(WifiMonitorService.EXTRA_NOTIF_ID, -1)
                runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { targetDate ->
                    store.setExplicitDayType(targetDate, DayType.LEAVE)
                    store.setDayNote(targetDate, "Leave (from Alert)")
                }
                if (notifId != -1) nm.cancel(notifId)
                WfoWidgetProvider.updateAllWidgets(context)
                context.sendBroadcast(Intent(WifiMonitorService.ACTION_DATA_UPDATED).setPackage(context.packageName))
                return
            }
            WifiMonitorService.ACTION_PLAN_MOVE_TOMORROW -> {
                val dateStr = intent.getStringExtra(WifiMonitorService.EXTRA_DATE_STR) ?: LocalDate.now().toString()
                val notifId = intent.getIntExtra(WifiMonitorService.EXTRA_NOTIF_ID, -1)
                runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { targetDate ->
                    store.setExplicitDayType(targetDate, DayType.NONE)
                    var nextDay = targetDate.plusDays(1)
                    while (nextDay.dayOfWeek == DayOfWeek.SATURDAY || nextDay.dayOfWeek == DayOfWeek.SUNDAY) {
                        nextDay = nextDay.plusDays(1)
                    }
                    if (store.getDayType(nextDay) == DayType.NONE) {
                        store.setExplicitDayType(nextDay, DayType.PLANNED)
                        store.setDayNote(nextDay, "Rescheduled Office")
                    }
                }
                if (notifId != -1) nm.cancel(notifId)
                WfoWidgetProvider.updateAllWidgets(context)
                context.sendBroadcast(Intent(WifiMonitorService.ACTION_DATA_UPDATED).setPackage(context.packageName))
                return
            }
        }

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
                    "Today is marked as a Planned Office day. Auto-tracking will confirm when you arrive."
                )
            }

            // 2. Morning Pace & WFH Briefing on Unscheduled Weekdays (08:30 - 10:30)
            if (store.isMorningNudgeEnabled() &&
                isWeekday &&
                todayType == DayType.NONE &&
                !store.wasConnectedToOfficeWifi() &&
                !now.isBefore(LocalTime.of(8, 30)) &&
                now.isBefore(LocalTime.of(10, 30))
            ) {
                val stats = store.getMonthStats(YearMonth.from(today), today)
                val rhythm = store.getRhythmStats(today)
                if ((stats.remainingDays > 0 && stats.bufferDays <= 2) || rhythm.consecutiveWfhWeekdays >= 3) {
                    if (store.claimOnceFlag("notif_pace_nudge_$today")) {
                        sendAlertNotification(
                            context,
                            "🧭 ${rhythm.safeToWfhVerdict}",
                            rhythm.safeToWfhDetail
                        )
                    }
                }
            }

            // 3. Actionable Missed-Plan Rollover Alert (11:30 - 14:30)
            if (store.isPlannedRemindersEnabled() &&
                todayType == DayType.PLANNED &&
                !now.isBefore(LocalTime.of(11, 30)) &&
                now.isBefore(LocalTime.of(14, 30)) &&
                store.claimOnceFlag("notif_plan_missed_$today")
            ) {
                val notifId = 4401
                val leaveIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = WifiMonitorService.ACTION_PLAN_MARK_LEAVE
                    putExtra(WifiMonitorService.EXTRA_DATE_STR, today.toString())
                    putExtra(WifiMonitorService.EXTRA_NOTIF_ID, notifId)
                }
                val moveIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = WifiMonitorService.ACTION_PLAN_MOVE_TOMORROW
                    putExtra(WifiMonitorService.EXTRA_DATE_STR, today.toString())
                    putExtra(WifiMonitorService.EXTRA_NOTIF_ID, notifId)
                }

                val leavePi = PendingIntent.getBroadcast(
                    context, 411, leaveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val movePi = PendingIntent.getBroadcast(
                    context, 412, moveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val actions = listOf(
                    NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Mark as Leave", leavePi),
                    NotificationCompat.Action(android.R.drawable.ic_menu_my_calendar, "Move to Tomorrow", movePi)
                )

                sendAlertNotification(
                    context,
                    "⚠️ Planned Office Visit Not Detected",
                    "Office presence not detected yet for today's planned visit. Update directly below:",
                    notifId = notifId,
                    actions = actions
                )
            }

            // 4. Night-Before Prep & Monthly End-of-Month Recovery Alert (20:00 - 22:00)
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

            val ym = YearMonth.from(today)
            val daysLeftInMonth = ym.atEndOfMonth().dayOfMonth - today.dayOfMonth
            if (store.isTargetBufferAlertEnabled() &&
                daysLeftInMonth in 0..7 &&
                !now.isBefore(LocalTime.of(20, 0)) &&
                now.isBefore(LocalTime.of(22, 0))
            ) {
                val mStats = store.getMonthStats(ym, today)
                if (mStats.remainingDays > 0 && mStats.recoverySuggestedDates.isNotEmpty() &&
                    store.claimOnceFlag("notif_month_recovery_$today")
                ) {
                    val datesText = mStats.recoverySuggestedDates.joinToString(", ") {
                        it.format(DateTimeFormatter.ofPattern("dd MMM"))
                    }
                    sendAlertNotification(
                        context,
                        "📌 ${mStats.monthLabel} Recovery Needed",
                        "Need ${mStats.remainingDays} more office day(s) before month-end. Suggested dates: $datesText."
                    )
                }
            }

            // 5. Auto-End-of-Day Disconnect Safety Net (23:50 - 23:59)
            if (!now.isBefore(LocalTime.of(23, 50)) && store.wasConnectedToOfficeWifi()) {
                store.updateLastSeenTimestamp(store.getActiveOfficeSsid(), today, now)
                store.setWasConnectedToOfficeWifi(false)
                store.setActiveOfficeSsid("")
                context.sendBroadcast(Intent(WifiMonitorService.ACTION_DATA_UPDATED).setPackage(context.packageName))
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
                LocalTime.of(20, 5),
                LocalTime.of(23, 55)
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
