package com.offline.wfo

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class DayType { NONE, OFFICE, PLANNED, LEAVE, HOLIDAY }
enum class TrackingMode { WIFI_ONLY, LOCATION_ONLY, BOTH }

data class DayLogEntry(
    val dateStr: String,
    val dayOfWeekShort: String,
    val type: DayType,
    val detail: String
)

data class DayInspectorInfo(
    val dateStr: String,
    val type: DayType,
    val statusLabel: String,
    val timeWindowLabel: String,
    val durationLabel: String,
    val note: String
)

data class MonthStats(
    val yearMonth: YearMonth,
    val monthLabel: String,
    val targetPercent: Int,
    val totalWeekdaysInMonth: Int,
    val holidayDays: Int,
    val leaveDays: Int,
    val effectiveWorkingDays: Int,
    val requiredDays: Int,
    val attendedDays: Int,
    val futurePlannedDays: Int,
    val projectedAttendedDays: Int,
    val actualPercent: Int,
    val projectedPercent: Int,
    val remainingDays: Int,
    val availableWeekdaysLeft: Int,
    val bufferDays: Int,
    val requiredDailyRunRate: Double,
    val planFollowThroughPercent: Int,
    val plannedTotalCount: Int,
    val plannedFulfilledCount: Int,
    val bufferTrajectoryLabel: String,
    val recoverySuggestedDates: List<LocalDate>,
    val dayTypeMap: Map<String, DayType>,
    val dayLogs: List<DayLogEntry>
)

data class WeeklyPaceStats(
    val attendedThisWeek: Int,
    val weeklyQuota: Int,
    val remainingThisWeek: Int,
    val weekdaysLeftThisWeek: Int
)

data class QuarterStats(
    val quarterLabel: String,
    val effectiveWorkingDays: Int,
    val requiredDays: Int,
    val attendedDays: Int,
    val leaveDays: Int,
    val holidayDays: Int,
    val actualPercent: Int,
    val isEarlyDriftWarning: Boolean,
    val driftSummary: String
)

data class MonthlyTrendPoint(
    val shortMonth: String,
    val actualPercent: Int,
    val targetPercent: Int
)

data class RhythmStats(
    val weeklyStreak: Int,
    val topDaysLabel: String,
    val consecutiveWfhWeekdays: Int,
    val learnedArrivalWindowLabel: String,
    val safeToWfhVerdict: String,
    val safeToWfhDetail: String
)

data class BackupHealthInfo(
    val configured: Boolean,
    val healthy: Boolean,
    val statusText: String
)

class AttendanceStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wfo_tracker_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_FULL_APP_LOCK = "full_app_lock_enabled"
        private const val KEY_TARGET_SSIDS = "target_ssids"
        private const val KEY_TRACKING_MODE = "tracking_mode"
        private const val KEY_OFFICE_LOC_NAME = "office_loc_name"
        private const val KEY_OFFICE_LAT = "office_lat"
        private const val KEY_OFFICE_LNG = "office_lng"
        private const val KEY_OFFICE_RADIUS = "office_radius_meters"
        private const val KEY_LAST_LOC_LAT = "last_loc_lat"
        private const val KEY_LAST_LOC_LNG = "last_loc_lng"
        private const val KEY_HAS_LAST_LOC = "has_last_loc"

        private const val KEY_TARGET_PCT = "target_percent"
        private const val KEY_ROUND_UP = "round_up_days"
        private const val KEY_EXCL_HOLIDAYS = "exclude_holidays"
        private const val KEY_EXCL_LEAVES = "exclude_leaves"

        private const val KEY_ALERT_CONNECT = "alert_connect"
        private const val KEY_ALERT_DISCONNECT = "alert_disconnect"
        private const val KEY_ALERT_PLANNED = "alert_planned"
        private const val KEY_ALERT_MORNING = "alert_morning"
        private const val KEY_ALERT_TARGET_BUFFER = "alert_target_buffer"
        private const val KEY_ALERT_QUARTER = "alert_quarter"

        private const val KEY_PREFERRED_WEEKDAYS = "preferred_weekdays_priority"
        private const val KEY_DAY_TYPES = "day_types_map"
        private const val KEY_DAY_NOTES = "day_notes_map"
        private const val KEY_FIRST_SEEN = "first_seen_map"
        private const val KEY_LAST_SEEN = "last_seen_map"
        private const val KEY_SOURCE_MAP = "source_map"
        private const val KEY_AUTO_LOGGED_DATES = "auto_logged_dates"
        private const val KEY_WAS_ON_WIFI = "was_on_office_wifi"
        private const val KEY_ACTIVE_SSID = "active_office_ssid"
        private const val KEY_ONCE_FLAGS = "once_flags_set"
        private const val KEY_BACKUP_TREE_URI = "auto_backup_tree_uri"
        private const val KEY_LAST_BACKUP_TIME = "last_auto_backup_time"
        private const val KEY_LAST_BACKUP_OK = "last_auto_backup_ok"
        private const val KEY_LAST_ROLLOVER_DATE = "last_rollover_date"
        private const val KEY_ARRIVAL_MINUTES_SAMPLES = "arrival_minutes_samples"

        private const val AUTO_BACKUP_FILENAME = "wfo_attendance_autobackup.csv"
    }

    init {
        performDailyRolloverIfNeeded()
    }

    // ==================== HANDS-FREE DAILY ROLLOVER & ARRIVAL LEARNING ====================

    fun performDailyRolloverIfNeeded(nowDate: LocalDate = LocalDate.now()) {
        val todayStr = nowDate.toString()
        val lastRolloverStr = prefs.getString(KEY_LAST_ROLLOVER_DATE, "") ?: ""
        if (lastRolloverStr == todayStr) return

        val prevDate = runCatching {
            if (lastRolloverStr.isNotEmpty()) LocalDate.parse(lastRolloverStr) else nowDate.minusDays(1)
        }.getOrDefault(nowDate.minusDays(1))

        var cursor = prevDate
        var changed = false
        while (!cursor.isAfter(nowDate.minusDays(1))) {
            val cStr = cursor.toString()
            val curType = getDayTypesMap()[cStr]
            if (curType == DayType.PLANNED) {
                setDayTypeInternal(cStr, DayType.NONE)
                changed = true
            }
            cursor = cursor.plusDays(1)
        }

        prefs.edit().putString(KEY_LAST_ROLLOVER_DATE, todayStr).apply()
        rebalanceAdaptivePlannedDays(YearMonth.from(nowDate), nowDate)
        if (changed) {
            triggerSilentAutoBackup()
        }
    }

    private fun recordArrivalMinuteSample(time: LocalTime) {
        val minutesFromMidnight = time.hour * 60 + time.minute
        if (minutesFromMidnight !in 360..840) return
        val raw = prefs.getString(KEY_ARRIVAL_MINUTES_SAMPLES, "") ?: ""
        val samples = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableList()
        samples.add(minutesFromMidnight)
        while (samples.size > 20) {
            samples.removeAt(0)
        }
        prefs.edit().putString(KEY_ARRIVAL_MINUTES_SAMPLES, samples.joinToString(",")).apply()
    }

    fun getLearnedArrivalWindowMinutes(): Pair<Int, Int> {
        val raw = prefs.getString(KEY_ARRIVAL_MINUTES_SAMPLES, "") ?: ""
        val samples = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (samples.isEmpty()) return 540 to 630
        val avg = samples.average().roundToInt()
        val start = (avg - 45).coerceIn(360, 720)
        val end = (avg + 60).coerceIn(start + 30, 900)
        return start to end
    }

    fun getLearnedArrivalWindowLabel(): String {
        val (startMin, endMin) = getLearnedArrivalWindowMinutes()
        val s = String.format(Locale.US, "%02d:%02d", startMin / 60, startMin % 60)
        val e = String.format(Locale.US, "%02d:%02d", endMin / 60, endMin % 60)
        return "$s–$e"
    }

    fun isInSmartCommuteWindow(nowTime: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return false
        val tType = getDayType(today)
        if (tType == DayType.HOLIDAY || tType == DayType.LEAVE) return false
        val (startMin, endMin) = getLearnedArrivalWindowMinutes()
        val curMin = nowTime.hour * 60 + nowTime.minute
        return curMin in (startMin - 15)..(endMin + 30)
    }

    fun rebalanceAdaptivePlannedDays(ym: YearMonth, today: LocalDate = LocalDate.now()) {
        val stats = getMonthStats(ym, today)
        val map = getDayTypesMap().toMutableMap()
        val existingPlanned = map.filter { (k, v) ->
            v == DayType.PLANNED && runCatching {
                val d = LocalDate.parse(k)
                YearMonth.from(d) == ym && !d.isBefore(today)
            }.getOrDefault(false)
        }.keys.toList()

        if (existingPlanned.isEmpty()) return

        if (stats.remainingDays <= 0) {
            existingPlanned.forEach { map.remove(it) }
            saveDayTypesMap(map)
            return
        }

        val desiredCount = stats.remainingDays
        if (existingPlanned.size > desiredCount) {
            val sortedPlanned = existingPlanned.sortedByDescending { it }
            sortedPlanned.take(existingPlanned.size - desiredCount).forEach { map.remove(it) }
            saveDayTypesMap(map)
        }
    }

    // ==================== SETUP & PROFILE ====================

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun setSetupComplete(complete: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply()
    }

    fun getUserName(): String = prefs.getString(KEY_USER_NAME, "")?.trim() ?: ""

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun isFullAppLockEnabled(): Boolean = prefs.getBoolean(KEY_FULL_APP_LOCK, false)

    fun setFullAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_APP_LOCK, enabled).apply()
    }

    fun getProfilePhotoFile(): File = File(context.filesDir, "user_profile_avatar.jpg")

    fun deleteProfilePhoto() {
        val f = getProfilePhotoFile()
        if (f.exists()) f.delete()
    }

    // ==================== TRACKING MODE, WI-FI & GEOFENCE ====================

    fun getTrackingMode(): TrackingMode {
        val raw = prefs.getString(KEY_TRACKING_MODE, TrackingMode.BOTH.name) ?: TrackingMode.BOTH.name
        return runCatching { TrackingMode.valueOf(raw) }.getOrDefault(TrackingMode.BOTH)
    }

    fun setTrackingMode(mode: TrackingMode) {
        prefs.edit().putString(KEY_TRACKING_MODE, mode.name).apply()
    }

    fun getTargetSsids(): List<String> {
        val raw = prefs.getString(KEY_TARGET_SSIDS, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setTargetSsids(csvInput: String) {
        val cleaned = csvInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(KEY_TARGET_SSIDS, cleaned.joinToString(",")).apply()
    }

    fun addTargetSsid(ssid: String): Boolean {
        val clean = ssid.trim()
        if (clean.isEmpty()) return false
        val current = getTargetSsids().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        current.add(clean)
        prefs.edit().putString(KEY_TARGET_SSIDS, current.joinToString(",")).apply()
        return true
    }

    fun removeTargetSsid(ssid: String) {
        val updated = getTargetSsids().filterNot { it.equals(ssid, ignoreCase = true) }
        prefs.edit().putString(KEY_TARGET_SSIDS, updated.joinToString(",")).apply()
    }

    fun getOfficeLocationName(): String = prefs.getString(KEY_OFFICE_LOC_NAME, "")?.trim() ?: ""
    fun getOfficeLat(): Double = Double.fromBits(prefs.getLong(KEY_OFFICE_LAT, 0L))
    fun getOfficeLng(): Double = Double.fromBits(prefs.getLong(KEY_OFFICE_LNG, 0L))
    fun getGeofenceRadiusMeters(): Int = prefs.getInt(KEY_OFFICE_RADIUS, 500).coerceIn(50, 5000)

    fun hasOfficeLocationConfigured(): Boolean {
        val lat = getOfficeLat()
        val lng = getOfficeLng()
        return !(lat == 0.0 && lng == 0.0) && lat in -90.0..90.0 && lng in -180.0..180.0
    }

    fun setOfficeLocation(name: String, lat: Double, lng: Double, radiusMeters: Int) {
        prefs.edit()
            .putString(KEY_OFFICE_LOC_NAME, name.trim())
            .putLong(KEY_OFFICE_LAT, lat.toBits())
            .putLong(KEY_OFFICE_LNG, lng.toBits())
            .putInt(KEY_OFFICE_RADIUS, radiusMeters.coerceIn(50, 5000))
            .apply()
    }

    fun setGeofenceRadiusMeters(radiusMeters: Int) {
        prefs.edit().putInt(KEY_OFFICE_RADIUS, radiusMeters.coerceIn(50, 5000)).apply()
    }

    fun setLastKnownLocation(lat: Double, lng: Double) {
        prefs.edit()
            .putLong(KEY_LAST_LOC_LAT, lat.toBits())
            .putLong(KEY_LAST_LOC_LNG, lng.toBits())
            .putBoolean(KEY_HAS_LAST_LOC, true)
            .apply()
    }

    fun hasLastKnownLocation(): Boolean = prefs.getBoolean(KEY_HAS_LAST_LOC, false)
    fun getLastKnownLat(): Double = Double.fromBits(prefs.getLong(KEY_LAST_LOC_LAT, 0L))
    fun getLastKnownLng(): Double = Double.fromBits(prefs.getLong(KEY_LAST_LOC_LNG, 0L))

    fun distanceToOfficeMeters(lat: Double, lng: Double): Float? {
        if (!hasOfficeLocationConfigured()) return null
        val results = FloatArray(1)
        Location.distanceBetween(lat, lng, getOfficeLat(), getOfficeLng(), results)
        return results[0]
    }

    // ==================== POLICY & NOTIFICATION PREFERENCES ====================

    fun getTargetPercent(): Int = prefs.getInt(KEY_TARGET_PCT, 40).coerceIn(5, 100)

    fun setTargetPercent(pct: Int) {
        prefs.edit().putInt(KEY_TARGET_PCT, pct.coerceIn(5, 100)).apply()
    }

    fun isRoundUpEnabled(): Boolean = prefs.getBoolean(KEY_ROUND_UP, true)
    fun setRoundUpEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ROUND_UP, enabled).apply()

    fun isExcludeHolidaysEnabled(): Boolean = prefs.getBoolean(KEY_EXCL_HOLIDAYS, true)
    fun setExcludeHolidaysEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_EXCL_HOLIDAYS, enabled).apply()

    fun isExcludeLeavesEnabled(): Boolean = prefs.getBoolean(KEY_EXCL_LEAVES, true)
    fun setExcludeLeavesEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_EXCL_LEAVES, enabled).apply()

    fun isConnectAlertEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_CONNECT, true)
    fun setConnectAlertEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_CONNECT, enabled).apply()

    fun isDisconnectAlertEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_DISCONNECT, true)
    fun setDisconnectAlertEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_DISCONNECT, enabled).apply()

    fun isPlannedRemindersEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_PLANNED, true)
    fun setPlannedRemindersEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_PLANNED, enabled).apply()

    fun isMorningNudgeEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_MORNING, true)
    fun setMorningNudgeEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_MORNING, enabled).apply()

    fun isTargetBufferAlertEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_TARGET_BUFFER, true)
    fun setTargetBufferAlertEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_TARGET_BUFFER, enabled).apply()

    fun isQuarterPaceAlertEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_QUARTER, true)
    fun setQuarterPaceAlertEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALERT_QUARTER, enabled).apply()

    // ==================== PRIORITY WEEKDAYS ====================

    fun getPreferredWeekdaysPriority(): List<DayOfWeek> {
        val defaultList = listOf(
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.MONDAY,
            DayOfWeek.FRIDAY
        )
        val raw = prefs.getString(KEY_PREFERRED_WEEKDAYS, null) ?: return defaultList
        val parsed = raw.split(",").mapNotNull { token ->
            runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull()
        }.distinct()
        return parsed.ifEmpty { defaultList }
    }

    fun cycleWeekdayPriority(dow: DayOfWeek) {
        val current = getPreferredWeekdaysPriority().toMutableList()
        val idx = current.indexOf(dow)
        if (idx == -1) {
            current.add(dow)
        } else if (current.size > 1) {
            current.removeAt(idx)
        }
        prefs.edit().putString(KEY_PREFERRED_WEEKDAYS, current.joinToString(",") { it.name }).apply()
    }

    // ==================== PRESENCE & ATTENDANCE MUTATIONS ====================

    fun wasConnectedToOfficeWifi(): Boolean = prefs.getBoolean(KEY_WAS_ON_WIFI, false)

    fun setWasConnectedToOfficeWifi(connected: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_ON_WIFI, connected).apply()
    }

    fun getActiveOfficeSsid(): String = prefs.getString(KEY_ACTIVE_SSID, "") ?: ""

    fun setActiveOfficeSsid(ssid: String) {
        prefs.edit().putString(KEY_ACTIVE_SSID, ssid).apply()
    }

    fun claimOnceFlag(flagKey: String): Boolean {
        val set = getOnceFlagsSet().toMutableSet()
        if (set.contains(flagKey)) return false
        set.add(flagKey)
        if (set.size > 250) {
            val trimmed = set.toList().takeLast(150).toSet()
            prefs.edit().putStringSet(KEY_ONCE_FLAGS, trimmed).apply()
        } else {
            prefs.edit().putStringSet(KEY_ONCE_FLAGS, set).apply()
        }
        return true
    }

    private fun getOnceFlagsSet(): Set<String> {
        return prefs.getStringSet(KEY_ONCE_FLAGS, emptySet())?.toSet() ?: emptySet()
    }

    fun recordOfficePresence(
        sourceLabel: String,
        nowDate: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now()
    ): Boolean {
        val dateStr = nowDate.toString()
        val timeStr = nowTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val cleanSource = sourceLabel.trim().ifEmpty { "Office" }

        val firstSeen = getStringMap(KEY_FIRST_SEEN).toMutableMap()
        val lastSeen = getStringMap(KEY_LAST_SEEN).toMutableMap()
        val sourceMap = getStringMap(KEY_SOURCE_MAP).toMutableMap()
        val autoSet = getAutoLoggedSet().toMutableSet()
        val dayMap = getDayTypesMap().toMutableMap()

        if (!firstSeen.containsKey(dateStr)) {
            firstSeen[dateStr] = timeStr
            recordArrivalMinuteSample(nowTime)
        }
        lastSeen[dateStr] = timeStr
        sourceMap[dateStr] = cleanSource

        val prevType = dayMap[dateStr] ?: DayType.NONE
        val isNewOfficeDay = prevType != DayType.OFFICE
        if (isNewOfficeDay) {
            dayMap[dateStr] = DayType.OFFICE
            autoSet.add(dateStr)
        }

        saveStringMap(KEY_FIRST_SEEN, firstSeen)
        saveStringMap(KEY_LAST_SEEN, lastSeen)
        saveStringMap(KEY_SOURCE_MAP, sourceMap)
        prefs.edit().putStringSet(KEY_AUTO_LOGGED_DATES, autoSet).apply()
        saveDayTypesMap(dayMap)
        rebalanceAdaptivePlannedDays(YearMonth.from(nowDate), nowDate)
        triggerSilentAutoBackup()
        return isNewOfficeDay
    }

    fun updateLastSeenTimestamp(
        sourceLabel: String,
        nowDate: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now()
    ) {
        val dateStr = nowDate.toString()
        val timeStr = nowTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val lastSeen = getStringMap(KEY_LAST_SEEN).toMutableMap()
        val sourceMap = getStringMap(KEY_SOURCE_MAP).toMutableMap()
        lastSeen[dateStr] = timeStr
        if (sourceLabel.isNotBlank()) sourceMap[dateStr] = sourceLabel.trim()
        saveStringMap(KEY_LAST_SEEN, lastSeen)
        saveStringMap(KEY_SOURCE_MAP, sourceMap)
        triggerSilentAutoBackup()
    }

    fun applyManualDayType(date: LocalDate, mode: DayType): DayType {
        val dateStr = date.toString()
        val map = getDayTypesMap().toMutableMap()
        val current = map[dateStr] ?: DayType.NONE
        val next = if (current == mode) DayType.NONE else mode
        if (next == DayType.NONE) map.remove(dateStr) else map[dateStr] = next
        saveDayTypesMap(map)
        rebalanceAdaptivePlannedDays(YearMonth.from(date))
        triggerSilentAutoBackup()
        return next
    }

    fun setExplicitDayType(date: LocalDate, type: DayType) {
        setDayTypeInternal(date.toString(), type)
        rebalanceAdaptivePlannedDays(YearMonth.from(date))
        triggerSilentAutoBackup()
    }

    private fun setDayTypeInternal(dateStr: String, type: DayType) {
        val map = getDayTypesMap().toMutableMap()
        if (type == DayType.NONE) map.remove(dateStr) else map[dateStr] = type
        saveDayTypesMap(map)
    }

    fun restoreBatchDates(previousStates: Map<LocalDate, DayType>) {
        val map = getDayTypesMap().toMutableMap()
        previousStates.forEach { (date, prevType) ->
            val key = date.toString()
            if (prevType == DayType.NONE) map.remove(key) else map[key] = prevType
        }
        saveDayTypesMap(map)
        triggerSilentAutoBackup()
    }

    fun getDayType(date: LocalDate): DayType = getDayTypesMap()[date.toString()] ?: DayType.NONE
    fun getAllMarkedDates(): Map<String, DayType> = getDayTypesMap()

    fun getDayNote(dateStr: String): String = getStringMap(KEY_DAY_NOTES)[dateStr] ?: ""

    fun setDayNote(date: LocalDate, note: String) {
        val map = getStringMap(KEY_DAY_NOTES).toMutableMap()
        val clean = note.trim()
        if (clean.isEmpty()) map.remove(date.toString()) else map[date.toString()] = clean
        saveStringMap(KEY_DAY_NOTES, map)
        triggerSilentAutoBackup()
    }

    fun getUpcomingHolidaysAndLeaves(today: LocalDate = LocalDate.now()): List<DayLogEntry> {
        val notes = getStringMap(KEY_DAY_NOTES)
        return getDayTypesMap().entries
            .mapNotNull { (dateStr, type) ->
                if (type != DayType.HOLIDAY && type != DayType.LEAVE) return@mapNotNull null
                val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@mapNotNull null
                if (d.isBefore(today)) return@mapNotNull null
                val dowShort = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val note = notes[dateStr]?.takeIf { it.isNotEmpty() }
                val baseLabel = if (type == DayType.HOLIDAY) "Holiday" else "Leave"
                DayLogEntry(
                    dateStr = dateStr,
                    dayOfWeekShort = dowShort,
                    type = type,
                    detail = if (note != null) "$baseLabel ($note)" else baseLabel
                )
            }
            .sortedBy { it.dateStr }
    }

    fun clearUpcomingHolidaysAndLeaves(today: LocalDate = LocalDate.now()): Map<LocalDate, DayType> {
        val map = getDayTypesMap().toMutableMap()
        val removed = mutableMapOf<LocalDate, DayType>()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value == DayType.HOLIDAY || entry.value == DayType.LEAVE) {
                val d = runCatching { LocalDate.parse(entry.key) }.getOrNull()
                if (d != null && !d.isBefore(today)) {
                    removed[d] = entry.value
                    iterator.remove()
                }
            }
        }
        if (removed.isNotEmpty()) {
            saveDayTypesMap(map)
            rebalanceAdaptivePlannedDays(YearMonth.from(today), today)
            triggerSilentAutoBackup()
        }
        return removed
    }

    fun autoPlanRemainingDaysForMonth(ym: YearMonth): Map<LocalDate, DayType> {
        val stats = getMonthStats(ym)
        val needed = stats.remainingDays
        if (needed <= 0) return emptyMap()

        val today = LocalDate.now()
        val map = getDayTypesMap().toMutableMap()
        val priorities = getPreferredWeekdaysPriority()

        val candidates = mutableListOf<LocalDate>()
        for (day in 1..ym.lengthOfMonth()) {
            val d = ym.atDay(day)
            if (d.isBefore(today)) continue
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) continue
            val cur = map[d.toString()] ?: DayType.NONE
            if (cur == DayType.NONE) {
                candidates.add(d)
            }
        }

        val sortedCandidates = candidates.sortedWith(
            compareBy<LocalDate> { d ->
                val idx = priorities.indexOf(d.dayOfWeek)
                if (idx >= 0) idx else 99
            }.thenBy { it }
        )

        val modified = mutableMapOf<LocalDate, DayType>()
        for (d in sortedCandidates.take(needed)) {
            modified[d] = map[d.toString()] ?: DayType.NONE
            map[d.toString()] = DayType.PLANNED
        }
        if (modified.isNotEmpty()) {
            saveDayTypesMap(map)
            triggerSilentAutoBackup()
        }
        return modified
    }

    fun getSuggestedRecoveryDates(ym: YearMonth, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val stats = getMonthStats(ym, today)
        if (stats.remainingDays <= 0) return emptyList()
        val priorities = getPreferredWeekdaysPriority()
        val candidates = mutableListOf<LocalDate>()
        for (day in 1..ym.lengthOfMonth()) {
            val d = ym.atDay(day)
            if (d.isBefore(today)) continue
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) continue
            val cur = getDayType(d)
            if (cur == DayType.NONE || cur == DayType.PLANNED) {
                candidates.add(d)
            }
        }
        return candidates.sortedWith(
            compareBy<LocalDate> { d ->
                val idx = priorities.indexOf(d.dayOfWeek)
                if (idx >= 0) idx else 99
            }.thenBy { it }
        ).take(stats.remainingDays)
    }

    // ==================== INSPECTOR & ANALYTICS ====================

    fun getDayInspectorInfo(date: LocalDate): DayInspectorInfo {
        val dateStr = date.toString()
        val type = getDayType(date)
        val firstSeen = getStringMap(KEY_FIRST_SEEN)[dateStr]
        val lastSeen = getStringMap(KEY_LAST_SEEN)[dateStr]
        val src = getStringMap(KEY_SOURCE_MAP)[dateStr]
        val note = getDayNote(dateStr)
        val isAuto = getAutoLoggedSet().contains(dateStr)

        val statusLabel = when (type) {
            DayType.OFFICE -> if (isAuto) "🏢 Office Day (Auto-Tracked)" else "🏢 Office Day (Manual)"
            DayType.PLANNED -> "📅 Planned Office Day"
            DayType.LEAVE -> "🌴 Leave Day"
            DayType.HOLIDAY -> "🎉 Holiday"
            DayType.NONE -> "🏠 Unmarked / WFH"
        }

        val timeWindowLabel = when {
            firstSeen != null && lastSeen != null && firstSeen != lastSeen -> {
                val via = if (!src.isNullOrEmpty()) " via $src" else ""
                "Window: $firstSeen → $lastSeen$via"
            }
            firstSeen != null -> {
                val via = if (!src.isNullOrEmpty()) " via $src" else ""
                "Logged at: $firstSeen$via"
            }
            else -> "No arrival/departure timestamp recorded"
        }

        val durationLabel = if (firstSeen != null && lastSeen != null && firstSeen != lastSeen) {
            val mins = computeMinutesDiff(firstSeen, lastSeen)
            if (mins > 0) {
                val h = mins / 60
                val m = mins % 60
                "Active Span: ${h}h ${m}m"
            } else {
                "Active Span: Recorded"
            }
        } else {
            "Active Span: —"
        }

        return DayInspectorInfo(
            dateStr = dateStr,
            type = type,
            statusLabel = statusLabel,
            timeWindowLabel = timeWindowLabel,
            durationLabel = durationLabel,
            note = note
        )
    }

    private fun computeMinutesDiff(startHhMm: String, endHhMm: String): Int {
        return runCatching {
            val sParts = startHhMm.split(":")
            val eParts = endHhMm.split(":")
            val sMin = sParts[0].toInt() * 60 + sParts[1].toInt()
            val eMin = eParts[0].toInt() * 60 + eParts[1].toInt()
            maxOf(0, eMin - sMin)
        }.getOrDefault(0)
    }

    fun getMonthStats(
        ym: YearMonth,
        today: LocalDate = LocalDate.now(),
        extraSimulatedLeaves: Int = 0,
        extraSimulatedHolidays: Int = 0
    ): MonthStats {
        val targetPct = getTargetPercent()
        val roundUp = isRoundUpEnabled()
        val exclHolidays = isExcludeHolidaysEnabled()
        val exclLeaves = isExcludeLeavesEnabled()

        val allTypes = getDayTypesMap()
        val firstSeen = getStringMap(KEY_FIRST_SEEN)
        val lastSeen = getStringMap(KEY_LAST_SEEN)
        val sourceMap = getStringMap(KEY_SOURCE_MAP)
        val notesMap = getStringMap(KEY_DAY_NOTES)
        val autoSet = getAutoLoggedSet()

        var totalWeekdays = 0
        var holidayCount = 0
        var leaveCount = 0
        var officeCount = 0
        var futurePlannedCount = 0
        var plannedTotalCount = 0
        var plannedFulfilledCount = 0
        var availableWeekdaysLeft = 0

        val monthDayMap = mutableMapOf<String, DayType>()
        val logs = mutableListOf<DayLogEntry>()

        for (day in 1..ym.lengthOfMonth()) {
            val d = ym.atDay(day)
            val dStr = d.toString()
            val t = allTypes[dStr] ?: DayType.NONE
            monthDayMap[dStr] = t

            val isWeekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            if (!isWeekend) {
                totalWeekdays++
                if (t == DayType.HOLIDAY) holidayCount++
                if (t == DayType.LEAVE) leaveCount++
                if (!d.isBefore(today) && t != DayType.HOLIDAY && t != DayType.LEAVE && t != DayType.OFFICE) {
                    availableWeekdaysLeft++
                }
            }

            if (t == DayType.OFFICE) {
                officeCount++
                if (autoSet.contains(dStr)) {
                    plannedFulfilledCount++
                }
            }
            if (t == DayType.PLANNED) {
                plannedTotalCount++
                if (!d.isBefore(today)) {
                    futurePlannedCount++
                }
            }

            if (t != DayType.NONE || !notesMap[dStr].isNullOrEmpty() || firstSeen.containsKey(dStr)) {
                val dowShort = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val fTime = firstSeen[dStr]
                val lTime = lastSeen[dStr]
                val src = sourceMap[dStr]
                val note = notesMap[dStr]?.takeIf { it.isNotEmpty() }

                val timePart = when {
                    fTime != null && lTime != null && fTime != lTime -> " [$fTime–$lTime]"
                    fTime != null -> " [$fTime]"
                    else -> ""
                }
                val srcPart = if (!src.isNullOrEmpty()) " • $src" else ""
                val autoTag = if (t == DayType.OFFICE && autoSet.contains(dStr)) " (Auto)" else ""
                val notePart = if (note != null) " — $note" else ""

                val baseDesc = when (t) {
                    DayType.OFFICE -> "Office$autoTag$timePart$srcPart$notePart"
                    DayType.PLANNED -> "Planned Office$notePart"
                    DayType.LEAVE -> "Leave$notePart"
                    DayType.HOLIDAY -> "Holiday$notePart"
                    DayType.NONE -> "Note$timePart$notePart"
                }
                logs.add(DayLogEntry(dStr, dowShort, t, baseDesc))
            }
        }

        val effectiveHolidays = holidayCount + extraSimulatedHolidays
        val effectiveLeaves = leaveCount + extraSimulatedLeaves

        var workingDays = totalWeekdays
        if (exclHolidays) workingDays -= effectiveHolidays
        if (exclLeaves) workingDays -= effectiveLeaves
        workingDays = maxOf(0, workingDays)

        val rawRequired = (workingDays * targetPct) / 100.0
        val requiredDays = if (workingDays == 0) 0 else {
            if (roundUp) ceil(rawRequired).toInt() else rawRequired.roundToInt()
        }

        val remainingDays = maxOf(0, requiredDays - officeCount)
        val bufferDays = availableWeekdaysLeft - remainingDays
        val actualPct = if (workingDays == 0) 0 else ((officeCount * 100.0) / workingDays).roundToInt()

        val projectedAttended = officeCount + futurePlannedCount
        val projectedPct = if (workingDays == 0) 0 else ((projectedAttended * 100.0) / workingDays).roundToInt()

        val runRate = if (availableWeekdaysLeft <= 0) {
            if (remainingDays > 0) remainingDays.toDouble() else 0.0
        } else {
            remainingDays.toDouble() / availableWeekdaysLeft.toDouble()
        }

        val followThroughPct = if (plannedTotalCount == 0) {
            100
        } else {
            ((plannedFulfilledCount.toDouble() / plannedTotalCount.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        }

        val trajectoryLabel = when {
            remainingDays == 0 -> "Target achieved — zero buffer burn risk"
            bufferDays < 0 -> "Behind pace — shortfall of ${remainingDays - availableWeekdaysLeft} day(s)"
            bufferDays <= 1 -> "Tight glide path — only $bufferDays buffer day left"
            else -> "Healthy glide path — $bufferDays buffer days available"
        }

        val recoveryDates = if (remainingDays > 0) {
            val priorities = getPreferredWeekdaysPriority()
            (1..ym.lengthOfMonth()).map { ym.atDay(it) }
                .filter { d ->
                    !d.isBefore(today) &&
                        d.dayOfWeek != DayOfWeek.SATURDAY &&
                        d.dayOfWeek != DayOfWeek.SUNDAY &&
                        (monthDayMap[d.toString()] ?: DayType.NONE) in listOf(DayType.NONE, DayType.PLANNED)
                }
                .sortedWith(
                    compareBy<LocalDate> { d ->
                        val idx = priorities.indexOf(d.dayOfWeek)
                        if (idx >= 0) idx else 99
                    }.thenBy { it }
                )
                .take(remainingDays)
        } else {
            emptyList()
        }

        val monthLabel = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        return MonthStats(
            yearMonth = ym,
            monthLabel = monthLabel,
            targetPercent = targetPct,
            totalWeekdaysInMonth = totalWeekdays,
            holidayDays = holidayCount,
            leaveDays = leaveCount,
            effectiveWorkingDays = workingDays,
            requiredDays = requiredDays,
            attendedDays = officeCount,
            futurePlannedDays = futurePlannedCount,
            projectedAttendedDays = projectedAttended,
            actualPercent = actualPct,
            projectedPercent = projectedPct,
            remainingDays = remainingDays,
            availableWeekdaysLeft = availableWeekdaysLeft,
            bufferDays = bufferDays,
            requiredDailyRunRate = runRate,
            planFollowThroughPercent = followThroughPct,
            plannedTotalCount = plannedTotalCount,
            plannedFulfilledCount = plannedFulfilledCount,
            bufferTrajectoryLabel = trajectoryLabel,
            recoverySuggestedDates = recoveryDates,
            dayTypeMap = monthDayMap,
            dayLogs = logs.reversed()
        )
    }

    fun getWeeklyPaceStats(today: LocalDate = LocalDate.now()): WeeklyPaceStats {
        val monday = today.with(DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        val map = getDayTypesMap()

        var attendedThisWeek = 0
        var weekdaysLeftThisWeek = 0

        var cursor = monday
        while (!cursor.isAfter(sunday)) {
            val isWeekend = cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY
            val t = map[cursor.toString()] ?: DayType.NONE
            if (t == DayType.OFFICE) attendedThisWeek++
            if (!isWeekend && !cursor.isBefore(today) && t != DayType.OFFICE && t != DayType.HOLIDAY && t != DayType.LEAVE) {
                weekdaysLeftThisWeek++
            }
            cursor = cursor.plusDays(1)
        }

        val mStats = getMonthStats(YearMonth.from(today), today)
        val weeklyQuota = if (mStats.requiredDays <= 0) 0 else {
            ceil(mStats.requiredDays / 4.0).toInt().coerceAtMost(5)
        }
        val remainingThisWeek = maxOf(0, weeklyQuota - attendedThisWeek)
        return WeeklyPaceStats(
            attendedThisWeek = attendedThisWeek,
            weeklyQuota = weeklyQuota,
            remainingThisWeek = remainingThisWeek,
            weekdaysLeftThisWeek = weekdaysLeftThisWeek
        )
    }

    fun getQuarterStats(ym: YearMonth, today: LocalDate = LocalDate.now()): QuarterStats {
        val qStartMonth = ((ym.monthValue - 1) / 3) * 3 + 1
        val qNum = ((ym.monthValue - 1) / 3) + 1
        val months = (0..2).map { YearMonth.of(ym.year, qStartMonth + it) }

        var totalWorking = 0
        var totalAttended = 0
        var totalLeaves = 0
        var totalHolidays = 0

        months.forEach { m ->
            val ms = getMonthStats(m, today)
            totalWorking += ms.effectiveWorkingDays
            totalAttended += ms.attendedDays
            totalLeaves += ms.leaveDays
            totalHolidays += ms.holidayDays
        }

        val targetPct = getTargetPercent()
        val rawReq = (totalWorking * targetPct) / 100.0
        val reqDays = if (totalWorking == 0) 0 else {
            if (isRoundUpEnabled()) ceil(rawReq).toInt() else rawReq.roundToInt()
        }
        val actualPct = if (totalWorking == 0) 0 else ((totalAttended * 100.0) / totalWorking).roundToInt()

        val qStartDate = months.first().atDay(1)
        val qEndDate = months.last().atEndOfMonth()
        val totalQuarterDays = maxOf(1, ChronoUnit.DAYS.between(qStartDate, qEndDate) + 1)
        val elapsedQuarterDays = ChronoUnit.DAYS.between(qStartDate, today).coerceIn(0, totalQuarterDays)
        val expectedPctSoFar = (targetPct * (elapsedQuarterDays.toDouble() / totalQuarterDays.toDouble())).roundToInt()
        val earlyDrift = elapsedQuarterDays >= 20 && actualPct + 6 < expectedPctSoFar

        val driftSummary = if (earlyDrift) {
            "⚠️ Early quarter drift: at $actualPct% vs ~$expectedPctSoFar% expected pace"
        } else {
            "✅ Quarter pace aligned with $targetPct% target"
        }

        return QuarterStats(
            quarterLabel = "Q$qNum ${ym.year}",
            effectiveWorkingDays = totalWorking,
            requiredDays = reqDays,
            attendedDays = totalAttended,
            leaveDays = totalLeaves,
            holidayDays = totalHolidays,
            actualPercent = actualPct,
            isEarlyDriftWarning = earlyDrift,
            driftSummary = driftSummary
        )
    }

    fun getMultiMonthTrend(anchorMonth: YearMonth, count: Int = 6): List<MonthlyTrendPoint> {
        val targetPct = getTargetPercent()
        val startOffset = -(count - 1)
        return (startOffset..0).map { offset ->
            val m = anchorMonth.plusMonths(offset.toLong())
            val ms = getMonthStats(m)
            val shortName = m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            MonthlyTrendPoint(
                shortMonth = shortName,
                actualPercent = ms.actualPercent,
                targetPercent = targetPct
            )
        }
    }

    fun getRhythmStats(today: LocalDate = LocalDate.now()): RhythmStats {
        val map = getDayTypesMap()
        val dayCounts = mutableMapOf<DayOfWeek, Int>()

        map.forEach { (dateStr, type) ->
            if (type == DayType.OFFICE) {
                val d = runCatching { LocalDate.parse(dateStr) }.getOrNull()
                if (d != null) {
                    dayCounts[d.dayOfWeek] = (dayCounts[d.dayOfWeek] ?: 0) + 1
                }
            }
        }

        val topDays = dayCounts.entries
            .sortedByDescending { it.value }
            .take(2)
            .joinToString(", ") { it.key.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            .ifEmpty { "None yet" }

        var streak = 0
        var weekMonday = today.with(DayOfWeek.MONDAY)
        while (true) {
            val weekSunday = weekMonday.plusDays(6)
            var hasOffice = false
            var c = weekMonday
            while (!c.isAfter(weekSunday)) {
                if (map[c.toString()] == DayType.OFFICE) {
                    hasOffice = true
                    break
                }
                c = c.plusDays(1)
            }
            if (hasOffice) {
                streak++
                weekMonday = weekMonday.minusWeeks(1)
            } else {
                break
            }
        }

        var consecutiveWfh = 0
        var cursor = today
        while (consecutiveWfh < 20) {
            if (cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY) {
                cursor = cursor.minusDays(1)
                continue
            }
            val t = map[cursor.toString()] ?: DayType.NONE
            if (t == DayType.NONE || t == DayType.PLANNED) {
                consecutiveWfh++
                cursor = cursor.minusDays(1)
            } else {
                break
            }
        }

        val mStats = getMonthStats(YearMonth.from(today), today)
        val wStats = getWeeklyPaceStats(today)
        val (verdict, detail) = when {
            mStats.remainingDays == 0 ->
                "Safe to WFH Today" to "${mStats.targetPercent}% monthly mandate already met (${mStats.attendedDays}/${mStats.requiredDays} days)."
            mStats.bufferDays <= 0 ->
                "Office Recommended Today" to "Zero buffer left — ${mStats.remainingDays} day(s) needed across ${mStats.availableWeekdaysLeft} weekday(s)."
            consecutiveWfh >= 3 ->
                "Office Recommended Today" to "$consecutiveWfh consecutive WFH weekdays — logging office today protects your buffer."
            mStats.bufferDays == 1 || wStats.remainingThisWeek > wStats.weekdaysLeftThisWeek ->
                "Watch Buffer Today" to "Only ${mStats.bufferDays} buffer day left; ${wStats.remainingThisWeek} needed for weekly pace."
            else ->
                "Safe to WFH Today" to "${mStats.bufferDays} buffer day(s) left • ${wStats.remainingThisWeek} needed this week."
        }

        return RhythmStats(
            weeklyStreak = streak,
            topDaysLabel = topDays,
            consecutiveWfhWeekdays = consecutiveWfh,
            learnedArrivalWindowLabel = getLearnedArrivalWindowLabel(),
            safeToWfhVerdict = verdict,
            safeToWfhDetail = detail
        )
    }

    // ==================== REPORTS & CSV / FOLDER BACKUP ====================

    fun buildMonthlySummaryReport(ym: YearMonth): String {
        val s = getMonthStats(ym)
        val uName = getUserName().ifEmpty { "Employee" }
        val recoveryLine = if (s.recoverySuggestedDates.isEmpty()) {
            "None needed"
        } else {
            s.recoverySuggestedDates.joinToString(", ") { it.format(DateTimeFormatter.ofPattern("dd MMM")) }
        }
        return buildString {
            appendLine("WFO Attendance Report — ${s.monthLabel}")
            appendLine("Name: $uName")
            appendLine("Attended: ${s.attendedDays} / ${s.requiredDays} required days (${s.actualPercent}% of ${s.effectiveWorkingDays} working days)")
            appendLine("Target Mandate: ${s.targetPercent}% | Remaining Needed: ${s.remainingDays} | WFH Buffer: ${maxOf(0, s.bufferDays)}")
            appendLine("Run-Rate Needed: ${String.format(Locale.US, "%.2f", s.requiredDailyRunRate)}/weekday | Plan Follow-Through: ${s.planFollowThroughPercent}%")
            appendLine("Leaves: ${s.leaveDays} | Holidays: ${s.holidayDays}")
            appendLine("Suggested Recovery Dates: $recoveryLine")
        }.trim()
    }

    fun buildAnnualSummaryReport(year: Int): String {
        val uName = getUserName().ifEmpty { "Employee" }
        var totalWorking = 0
        var totalAttended = 0
        var totalRequired = 0
        var totalLeaves = 0
        var totalHolidays = 0

        val monthLines = (1..12).map { m ->
            val ms = getMonthStats(YearMonth.of(year, m))
            totalWorking += ms.effectiveWorkingDays
            totalAttended += ms.attendedDays
            totalRequired += ms.requiredDays
            totalLeaves += ms.leaveDays
            totalHolidays += ms.holidayDays
            val shortM = ms.yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "$shortM: ${ms.attendedDays}/${ms.requiredDays} (${ms.actualPercent}%)"
        }

        val overallPct = if (totalWorking == 0) 0 else ((totalAttended * 100.0) / totalWorking).roundToInt()
        return buildString {
            appendLine("WFO Annual Year-in-Review — $year")
            appendLine("Name: $uName")
            appendLine("Overall Attendance: $totalAttended / $totalRequired required days ($overallPct% of $totalWorking working days)")
            appendLine("Total Leaves: $totalLeaves | Total Holidays: $totalHolidays")
            appendLine("---")
            monthLines.forEach { appendLine(it) }
        }.trim()
    }

    fun exportAllCsv(): String {
        val types = getDayTypesMap()
        val notes = getStringMap(KEY_DAY_NOTES)
        val firstSeen = getStringMap(KEY_FIRST_SEEN)
        val lastSeen = getStringMap(KEY_LAST_SEEN)
        val sources = getStringMap(KEY_SOURCE_MAP)
        val autoSet = getAutoLoggedSet()

        val allKeys = (types.keys + notes.keys + firstSeen.keys + lastSeen.keys).toSortedSet()
        return buildString {
            appendLine("date,type,source,first_seen,last_seen,auto_logged,note")
            for (k in allKeys) {
                val t = types[k] ?: DayType.NONE
                val src = csvEscape(sources[k] ?: "")
                val fs = csvEscape(firstSeen[k] ?: "")
                val ls = csvEscape(lastSeen[k] ?: "")
                val auto = if (autoSet.contains(k)) "true" else "false"
                val nt = csvEscape(notes[k] ?: "")
                appendLine("$k,${t.name},$src,$fs,$ls,$auto,$nt")
            }
        }
    }

    fun importFromUri(uri: Uri, autoBackupAfter: Boolean = false): Int {
        val rawText = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: return 0
        return importCsvText(rawText, autoBackupAfter)
    }

    fun importCsvText(csvText: String, autoBackupAfter: Boolean = false): Int {
        val lines = csvText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return 0

        val types = getDayTypesMap().toMutableMap()
        val notes = getStringMap(KEY_DAY_NOTES).toMutableMap()
        val firstSeen = getStringMap(KEY_FIRST_SEEN).toMutableMap()
        val lastSeen = getStringMap(KEY_LAST_SEEN).toMutableMap()
        val sources = getStringMap(KEY_SOURCE_MAP).toMutableMap()
        val autoSet = getAutoLoggedSet().toMutableSet()

        var importedCount = 0
        for ((idx, line) in lines.withIndex()) {
            if (idx == 0 && line.startsWith("date,", ignoreCase = true)) continue
            val cols = parseCsvLine(line)
            if (cols.size < 2) continue
            val dateStr = cols[0].trim()
            if (runCatching { LocalDate.parse(dateStr) }.isFailure) continue

            val type = runCatching { DayType.valueOf(cols[1].trim().uppercase(Locale.US)) }.getOrNull() ?: continue
            if (type == DayType.NONE) {
                types.remove(dateStr)
            } else {
                types[dateStr] = type
            }
            if (cols.size >= 3 && cols[2].isNotBlank()) sources[dateStr] = cols[2].trim()
            if (cols.size >= 4 && cols[3].isNotBlank()) firstSeen[dateStr] = cols[3].trim()
            if (cols.size >= 5 && cols[4].isNotBlank()) lastSeen[dateStr] = cols[4].trim()
            if (cols.size >= 6 && cols[5].equals("true", ignoreCase = true)) autoSet.add(dateStr)
            if (cols.size >= 7 && cols[6].isNotBlank()) notes[dateStr] = cols[6].trim()
            importedCount++
        }

        if (importedCount > 0) {
            saveDayTypesMap(types)
            saveStringMap(KEY_DAY_NOTES, notes)
            saveStringMap(KEY_FIRST_SEEN, firstSeen)
            saveStringMap(KEY_LAST_SEEN, lastSeen)
            saveStringMap(KEY_SOURCE_MAP, sources)
            prefs.edit().putStringSet(KEY_AUTO_LOGGED_DATES, autoSet).apply()
            if (autoBackupAfter) {
                triggerSilentAutoBackup()
            }
        }
        return importedCount
    }

    fun bulkImportHolidays(rawText: String): Map<LocalDate, DayType> {
        val dateRegex = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
        val icsDateRegex = Regex("""DTSTART(?:;[^:]+)?:(\d{4})(\d{2})(\d{2})""")
        val foundDates = mutableSetOf<LocalDate>()

        rawText.lineSequence().forEach { line ->
            dateRegex.findAll(line).forEach { m ->
                runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.let { foundDates.add(it) }
            }
            icsDateRegex.findAll(line).forEach { m ->
                val dStr = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
                runCatching { LocalDate.parse(dStr) }.getOrNull()?.let { foundDates.add(it) }
            }
        }

        if (foundDates.isEmpty()) return emptyMap()
        val map = getDayTypesMap().toMutableMap()
        val modified = mutableMapOf<LocalDate, DayType>()
        foundDates.sorted().forEach { d ->
            val prev = map[d.toString()] ?: DayType.NONE
            if (prev != DayType.HOLIDAY) {
                modified[d] = prev
                map[d.toString()] = DayType.HOLIDAY
            }
        }
        if (modified.isNotEmpty()) {
            saveDayTypesMap(map)
            triggerSilentAutoBackup()
        }
        return modified
    }

    fun setAutoBackupTreeUri(uriStr: String?) {
        prefs.edit().putString(KEY_BACKUP_TREE_URI, uriStr ?: "").apply()
    }

    fun getAutoBackupTreeUri(): String = prefs.getString(KEY_BACKUP_TREE_URI, "") ?: ""

    private fun findDocumentUriInTree(treeUri: Uri, displayName: String): Uri? {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        }.getOrNull() ?: return null

        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val docId = if (idCol >= 0) cursor.getString(idCol) else null
                    if (name == displayName && !docId.isNullOrBlank()) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun restoreExistingBackupFromFolder(treeUri: Uri): Int {
        val existingUri = findDocumentUriInTree(treeUri, AUTO_BACKUP_FILENAME) ?: return 0
        val text = context.contentResolver.openInputStream(existingUri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        } ?: return 0
        return importCsvText(text, autoBackupAfter = false)
    }

    fun triggerSilentAutoBackup(): Boolean {
        val uriStr = getAutoBackupTreeUri()
        if (uriStr.isBlank()) return false
        val ok = runCatching {
            val treeUri = Uri.parse(uriStr)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val targetUri = findDocumentUriInTree(treeUri, AUTO_BACKUP_FILENAME)
                ?: DocumentsContract.createDocument(context.contentResolver, dirUri, "text/csv", AUTO_BACKUP_FILENAME)
                ?: return@runCatching false

            context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                out.write(exportAllCsv().toByteArray(Charsets.UTF_8))
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

        val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))
        prefs.edit()
            .putString(KEY_LAST_BACKUP_TIME, nowStr)
            .putBoolean(KEY_LAST_BACKUP_OK, ok)
            .apply()
        return ok
    }

    fun getBackupHealthInfo(): BackupHealthInfo {
        val uriStr = getAutoBackupTreeUri()
        if (uriStr.isBlank()) {
            return BackupHealthInfo(
                configured = false,
                healthy = false,
                statusText = "Auto-backup folder: Not linked yet"
            )
        }
        val lastTime = prefs.getString(KEY_LAST_BACKUP_TIME, "") ?: ""
        val lastOk = prefs.getBoolean(KEY_LAST_BACKUP_OK, false)
        val hasPerm = runCatching {
            val treeUri = Uri.parse(uriStr)
            context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        }.getOrDefault(false)

        return if (hasPerm && lastOk) {
            BackupHealthInfo(
                configured = true,
                healthy = true,
                statusText = "Auto-backup active ($AUTO_BACKUP_FILENAME) • Last synced $lastTime"
            )
        } else {
            BackupHealthInfo(
                configured = true,
                healthy = false,
                statusText = "Auto-backup needs re-link or write check${if (lastTime.isNotEmpty()) " (last: $lastTime)" else ""}"
            )
        }
    }

    // ==================== INTERNAL MAPS & HELPERS ====================

    private fun getDayTypesMap(): Map<String, DayType> {
        val raw = prefs.getString(KEY_DAY_TYPES, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, DayType>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val t = runCatching { DayType.valueOf(parts[1].trim()) }.getOrNull()
                if (t != null && t != DayType.NONE) {
                    result[parts[0].trim()] = t
                }
            }
        }
        return result
    }

    private fun saveDayTypesMap(map: Map<String, DayType>) {
        val serialized = map.entries
            .filter { it.value != DayType.NONE }
            .joinToString(";") { "${it.key}=${it.value.name}" }
        prefs.edit().putString(KEY_DAY_TYPES, serialized).apply()
    }

    private fun getStringMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        raw.split("||").forEach { entry ->
            val idx = entry.indexOf("==")
            if (idx > 0) {
                val k = entry.substring(0, idx).trim()
                val v = entry.substring(idx + 2).replace("\\n", "\n")
                if (k.isNotEmpty()) result[k] = v
            }
        }
        return result
    }

    private fun saveStringMap(key: String, map: Map<String, String>) {
        val serialized = map.entries.joinToString("||") { (k, v) ->
            val safeVal = v.replace("||", " ").replace("==", " ").replace("\n", "\\n")
            "$k==$safeVal"
        }
        prefs.edit().putString(key, serialized).apply()
    }

    private fun getAutoLoggedSet(): Set<String> {
        return prefs.getStringSet(KEY_AUTO_LOGGED_DATES, emptySet())?.toSet() ?: emptySet()
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}
