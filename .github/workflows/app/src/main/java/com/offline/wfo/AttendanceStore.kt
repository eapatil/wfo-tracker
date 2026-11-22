package com.offline.wfo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class DayType {
    NONE,
    OFFICE,
    PLANNED,
    LEAVE,
    HOLIDAY
}

data class DayLog(
    val dateStr: String,
    val dayOfWeekShort: String,
    val type: DayType,
    val detail: String
)

data class DayInspectorInfo(
    val date: LocalDate,
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
    val roundUp: Boolean,
    val totalCalendarWeekdays: Int,
    val holidayWeekdays: Int,
    val leaveWeekdays: Int,
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
    val dayTypeMap: Map<String, DayType>,
    val dayLogs: List<DayLog>
)

data class QuarterStats(
    val quarterLabel: String,
    val effectiveWorkingDays: Int,
    val requiredDays: Int,
    val attendedDays: Int,
    val remainingDays: Int,
    val actualPercent: Int,
    val holidayDays: Int,
    val leaveDays: Int
)

data class WeeklyPaceStats(
    val weeklyQuota: Int,
    val attendedThisWeek: Int,
    val remainingThisWeek: Int,
    val weekdaysLeftThisWeek: Int
)

data class RhythmStats(
    val weeklyStreak: Int,
    val topDaysLabel: String
)

data class MonthlyTrendPoint(
    val ym: YearMonth,
    val shortMonth: String,
    val actualPercent: Int,
    val targetPercent: Int,
    val attendedDays: Int,
    val requiredDays: Int
)

data class BackupHealthInfo(
    val configured: Boolean,
    val folderLabel: String,
    val healthy: Boolean,
    val statusText: String
)

class AttendanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("wfo_offline_prefs", Context.MODE_PRIVATE)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    // ==================== PROFILE & SECURITY SETTINGS ====================

    fun isSetupComplete(): Boolean = prefs.getBoolean("setup_complete", false)
    fun setSetupComplete(done: Boolean) = prefs.edit().putBoolean("setup_complete", done).apply()

    fun getUserName(): String = prefs.getString("user_name", "") ?: ""
    fun setUserName(name: String) = prefs.edit().putString("user_name", name.trim()).apply()

    fun getProfilePhotoFile(): File = File(context.filesDir, "profile_avatar.jpg")
    fun hasProfilePhoto(): Boolean = getProfilePhotoFile().exists()
    fun deleteProfilePhoto() {
        runCatching { if (getProfilePhotoFile().exists()) getProfilePhotoFile().delete() }
    }

    fun isFullAppLockEnabled(): Boolean = prefs.getBoolean("full_app_lock", false)
    fun setFullAppLockEnabled(enabled: Boolean) = prefs.edit().putBoolean("full_app_lock", enabled).apply()

    // ==================== AUTO-BACKUP FOLDER & HEALTH (SAF) ====================

    fun getAutoBackupTreeUri(): String = prefs.getString("auto_backup_tree_uri", "") ?: ""

    fun setAutoBackupTreeUri(uriStr: String) {
        prefs.edit()
            .putString("auto_backup_tree_uri", uriStr)
            .putBoolean("last_auto_backup_ok", true)
            .apply()
    }

    fun getAutoBackupFolderLabel(): String {
        val raw = getAutoBackupTreeUri()
        if (raw.isEmpty()) return "Not configured"
        return runCatching {
            val uri = Uri.parse(raw)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.substringAfterLast(':').ifEmpty { docId }
        }.getOrDefault("Selected Folder")
    }

    fun getBackupHealthInfo(): BackupHealthInfo {
        val rawUri = getAutoBackupTreeUri()
        if (rawUri.isEmpty()) {
            return BackupHealthInfo(
                configured = false,
                folderLabel = "Not configured",
                healthy = false,
                statusText = "⚪ Silent Auto-Backup: Off (Pick a local or Drive folder)"
            )
        }
        val label = getAutoBackupFolderLabel()
        val lastOk = prefs.getBoolean("last_auto_backup_ok", true)
        val lastMs = prefs.getLong("last_auto_backup_ms", 0L)

        if (!lastOk) {
            return BackupHealthInfo(
                configured = true,
                folderLabel = label,
                healthy = false,
                statusText = "⚠️ Backup Folder Unreachable ($label) — Re-select folder below"
            )
        }

        val timeLabel = if (lastMs > 0L) {
            val zdt = Instant.ofEpochMilli(lastMs).atZone(ZoneId.systemDefault())
            val d = zdt.toLocalDate()
            val t = zdt.toLocalTime().format(timeFmt)
            when (d) {
                LocalDate.now() -> "Today, $t"
                LocalDate.now().minusDays(1) -> "Yesterday, $t"
                else -> "${d.format(DateTimeFormatter.ofPattern("dd MMM"))}, $t"
            }
        } else {
            "Ready"
        }

        return BackupHealthInfo(
            configured = true,
            folderLabel = label,
            healthy = true,
            statusText = "✅ Auto-Backup Active ($label) • Last synced: $timeLabel"
        )
    }

    fun triggerSilentAutoBackup(): Boolean {
        val rawUri = getAutoBackupTreeUri()
        if (rawUri.isEmpty()) return false

        val ok = runCatching {
            val treeUri = Uri.parse(rawUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val fileName = "wfo_auto_backup.csv"

            var targetFileUri: Uri? = null
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
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
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1)
                    if (name == fileName) {
                        targetFileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        break
                    }
                }
            }

            if (targetFileUri == null) {
                targetFileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    "text/csv",
                    fileName
                )
            }

            targetFileUri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(exportAllCsv().toByteArray(Charsets.UTF_8))
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (ok) {
            prefs.edit()
                .putBoolean("last_auto_backup_ok", true)
                .putLong("last_auto_backup_ms", System.currentTimeMillis())
                .apply()
        } else {
            prefs.edit().putBoolean("last_auto_backup_ok", false).apply()
        }
        return ok
    }

    // ==================== POLICY & WI-FI SETTINGS ====================

    fun getTargetPercent(): Int = prefs.getInt("target_percent", 40)
    fun setTargetPercent(pct: Int) = prefs.edit().putInt("target_percent", pct.coerceIn(5, 100)).apply()

    fun isRoundUpEnabled(): Boolean = prefs.getBoolean("round_up", true)
    fun setRoundUpEnabled(roundUp: Boolean) = prefs.edit().putBoolean("round_up", roundUp).apply()

    fun isExcludeHolidaysEnabled(): Boolean = prefs.getBoolean("exclude_holidays", false)
    fun setExcludeHolidaysEnabled(exclude: Boolean) = prefs.edit().putBoolean("exclude_holidays", exclude).apply()

    fun isExcludeLeavesEnabled(): Boolean = prefs.getBoolean("exclude_leaves", false)
    fun setExcludeLeavesEnabled(exclude: Boolean) = prefs.edit().putBoolean("exclude_leaves", exclude).apply()

    fun isPlannedRemindersEnabled(): Boolean = prefs.getBoolean("planned_reminders", true)
    fun setPlannedRemindersEnabled(enabled: Boolean) = prefs.edit().putBoolean("planned_reminders", enabled).apply()

    fun isMorningNudgeEnabled(): Boolean = prefs.getBoolean("morning_nudge", true)
    fun setMorningNudgeEnabled(enabled: Boolean) = prefs.edit().putBoolean("morning_nudge", enabled).apply()

    fun getTargetSsids(): List<String> {
        val raw = prefs.getString("target_ssids", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setTargetSsids(csv: String) {
        val cleaned = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        prefs.edit().putString("target_ssids", cleaned).apply()
    }

    fun addTargetSsid(ssid: String): Boolean {
        val clean = ssid.trim()
        if (clean.isEmpty()) return false
        val current = getTargetSsids().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        current.add(clean)
        setTargetSsids(current.joinToString(", "))
        return true
    }

    fun removeTargetSsid(ssid: String) {
        val updated = getTargetSsids().filterNot { it.equals(ssid, ignoreCase = true) }
        setTargetSsids(updated.joinToString(", "))
    }

    // ==================== PERSISTED WI-FI CONNECTION STATE ====================

    fun wasConnectedToOfficeWifi(): Boolean = prefs.getBoolean("was_on_office_wifi", false)

    fun getActiveOfficeSsid(): String = prefs.getString("active_office_ssid", "") ?: ""

    fun setConnectedToOfficeWifi(connected: Boolean, ssid: String = "") {
        prefs.edit()
            .putBoolean("was_on_office_wifi", connected)
            .putString("active_office_ssid", if (connected) ssid else "")
            .apply()
    }

    // ==================== DATE, NOTES & ATTENDANCE OPERATIONS ====================

    fun getAllMarkedDates(): MutableSet<String> {
        return prefs.getStringSet("all_marked_dates", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun getDayType(date: LocalDate): DayType = getDayType(date.toString())

    fun getDayType(dateStr: String): DayType {
        val raw = prefs.getString("day_type_$dateStr", DayType.NONE.name) ?: DayType.NONE.name
        return runCatching { DayType.valueOf(raw) }.getOrDefault(DayType.NONE)
    }

    fun getDayNote(dateStr: String): String = prefs.getString("note_$dateStr", "") ?: ""

    fun setDayNote(date: LocalDate, note: String) {
        val key = date.toString()
        val clean = note.trim()
        val editor = prefs.edit()
        if (clean.isEmpty()) {
            editor.remove("note_$key")
        } else {
            editor.putString("note_$key", clean)
        }
        editor.apply()
        triggerSilentAutoBackup()
    }

    fun recordOfficePresence(
        ssid: String = "",
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now()
    ): Boolean {
        val key = date.toString()
        val nowStr = time.format(timeFmt)
        val dates = getAllMarkedDates()
        val currentType = getDayType(key)
        val isNewOfficeToday = currentType != DayType.OFFICE

        dates.add(key)
        val editor = prefs.edit()
            .putStringSet("all_marked_dates", dates)
            .putString("day_type_$key", DayType.OFFICE.name)
            .putBoolean("was_on_office_wifi", true)
            .putString("active_office_ssid", ssid)

        if (isNewOfficeToday) {
            editor.putString("times_$key", "$nowStr|$nowStr")
        } else {
            val existing = prefs.getString("times_$key", "") ?: ""
            val firstSeen = if (existing.contains("|")) existing.substringBefore("|") else nowStr
            editor.putString("times_$key", "$firstSeen|$nowStr")
        }

        editor.apply()
        if (isNewOfficeToday) {
            triggerSilentAutoBackup()
        }
        return isNewOfficeToday
    }

    fun recordOfficeDisconnect(
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now()
    ): Boolean {
        val wasConnected = wasConnectedToOfficeWifi()
        setConnectedToOfficeWifi(false, "")

        val key = date.toString()
        if (getDayType(key) != DayType.OFFICE) return false

        val existing = prefs.getString("times_$key", "") ?: ""
        val nowStr = time.format(timeFmt)
        if (existing.contains("|")) {
            val firstSeen = existing.substringBefore("|")
            prefs.edit().putString("times_$key", "$firstSeen|$nowStr").apply()
            triggerSilentAutoBackup()
            return true
        }
        return wasConnected
    }

    fun setExplicitDayType(date: LocalDate, targetType: DayType, customDetail: String? = null) {
        val key = date.toString()
        val dates = getAllMarkedDates()
        if (targetType == DayType.NONE) {
            dates.remove(key)
            prefs.edit()
                .putStringSet("all_marked_dates", dates)
                .remove("day_type_$key")
                .remove("times_$key")
                .apply()
        } else {
            dates.add(key)
            val detail = customDetail ?: when (targetType) {
                DayType.OFFICE -> "Manual Check-In"
                DayType.PLANNED -> "Planned Office"
                DayType.LEAVE -> "Personal / Sick Leave"
                DayType.HOLIDAY -> "Company Holiday"
                DayType.NONE -> ""
            }
            prefs.edit()
                .putStringSet("all_marked_dates", dates)
                .putString("day_type_$key", targetType.name)
                .putString("times_$key", detail)
                .apply()
        }
        triggerSilentAutoBackup()
    }

    fun applyManualDayType(date: LocalDate, targetType: DayType): DayType {
        val current = getDayType(date)
        return if (current == targetType || targetType == DayType.NONE) {
            setExplicitDayType(date, DayType.NONE)
            DayType.NONE
        } else {
            setExplicitDayType(date, targetType)
            targetType
        }
    }

    /**
     * Reverts a batch of dates back to their prior DayTypes (for Undo support).
     */
    fun restoreBatchDates(previousStates: Map<LocalDate, DayType>) {
        if (previousStates.isEmpty()) return
        val dates = getAllMarkedDates()
        val editor = prefs.edit()
        for ((date, prevType) in previousStates) {
            val key = date.toString()
            if (prevType == DayType.NONE) {
                dates.remove(key)
                editor.remove("day_type_$key").remove("times_$key")
            } else {
                dates.add(key)
                editor.putString("day_type_$key", prevType.name)
                editor.putString("times_$key", getFormattedDetail(key, prevType))
            }
        }
        editor.putStringSet("all_marked_dates", dates).apply()
        triggerSilentAutoBackup()
    }

    /**
     * One-tap Auto-Plan Month: returns a map of modified dates -> prior DayTypes so the caller
     * can offer an immediate Undo button.
     */
    fun autoPlanRemainingDaysForMonth(
        ym: YearMonth = YearMonth.now(),
        today: LocalDate = LocalDate.now()
    ): Map<LocalDate, DayType> {
        val stats = getMonthStats(ym, today)
        val neededToPlan = maxOf(0, stats.remainingDays - stats.futurePlannedDays)
        if (neededToPlan == 0) return emptyMap()

        val currentYm = YearMonth.from(today)
        if (ym.isBefore(currentYm)) return emptyMap()

        val officeDates = getAllMarkedDates()
            .filter { getDayType(it) == DayType.OFFICE }
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }

        val preferredDays: List<DayOfWeek> = if (officeDates.isNotEmpty()) {
            officeDates.groupingBy { it.dayOfWeek }
                .eachCount()
                .entries
                .sortedWith(compareBy({ -it.value }, { it.key.value }))
                .map { it.key }
        } else {
            listOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        }

        val candidates = mutableListOf<LocalDate>()
        for (day in 1..ym.lengthOfMonth()) {
            val d = ym.atDay(day)
            val isFutureOrToday = if (ym == currentYm) !d.isBefore(today) else true
            val isWeekday = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            if (isFutureOrToday && isWeekday && getDayType(d) == DayType.NONE) {
                candidates.add(d)
            }
        }

        if (candidates.isEmpty()) return emptyMap()

        val sortedCandidates = candidates.sortedWith(
            compareBy(
                { d ->
                    val idx = preferredDays.indexOf(d.dayOfWeek)
                    if (idx >= 0) idx else 99
                },
                { it.dayOfMonth }
            )
        )

        val toMark = sortedCandidates.take(neededToPlan)
        val previousStates = mutableMapOf<LocalDate, DayType>()
        val dates = getAllMarkedDates()
        val editor = prefs.edit()

        for (d in toMark) {
            previousStates[d] = getDayType(d)
            val key = d.toString()
            dates.add(key)
            editor.putString("day_type_$key", DayType.PLANNED.name)
            editor.putString("times_$key", "Auto-Planned Office")
        }
        editor.putStringSet("all_marked_dates", dates).apply()

        if (toMark.isNotEmpty()) {
            triggerSilentAutoBackup()
        }
        return previousStates
    }

    /**
     * Bulk imports holidays from either an `.ics` calendar file (DTSTART entries + SUMMARY)
     * or a CSV file (`YYYY-MM-DD,Holiday Name`).
     * Never overwrites already confirmed OFFICE days.
     * Returns a map of modified dates -> prior DayTypes for Undo support.
     */
    fun bulkImportHolidays(rawContent: String): Map<LocalDate, DayType> {
        val parsedHolidays = mutableListOf<Pair<LocalDate, String>>()
        val trimmedAll = rawContent.trim()

        if (trimmedAll.contains("BEGIN:VCALENDAR", ignoreCase = true) ||
            trimmedAll.contains("DTSTART", ignoreCase = true)
        ) {
            var currentDate: LocalDate? = null
            var currentSummary = "Company Holiday"
            val basicDateFmt = DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd

            trimmedAll.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("BEGIN:VEVENT", ignoreCase = true) -> {
                        currentDate = null
                        currentSummary = "Company Holiday"
                    }
                    line.startsWith("DTSTART", ignoreCase = true) -> {
                        val valuePart = line.substringAfter(':').trim()
                        val digits = valuePart.take(8)
                        currentDate = runCatching { LocalDate.parse(digits, basicDateFmt) }.getOrNull()
                    }
                    line.startsWith("SUMMARY", ignoreCase = true) -> {
                        val sum = line.substringAfter(':').trim()
                        if (sum.isNotEmpty()) currentSummary = sum
                    }
                    line.startsWith("END:VEVENT", ignoreCase = true) -> {
                        currentDate?.let { d ->
                            parsedHolidays.add(d to currentSummary)
                        }
                        currentDate = null
                    }
                }
            }
        } else {
            // CSV or plain date list format: YYYY-MM-DD[,Holiday Name]
            trimmedAll.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("Date", ignoreCase = true)) return@forEach
                val parts = line.split(",")
                val dateToken = parts[0].trim()
                val parsed = runCatching { LocalDate.parse(dateToken) }.getOrNull() ?: return@forEach
                val label = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { "Company Holiday" }
                parsedHolidays.add(parsed to label)
            }
        }

        if (parsedHolidays.isEmpty()) return emptyMap()

        val previousStates = mutableMapOf<LocalDate, DayType>()
        val dates = getAllMarkedDates()
        val editor = prefs.edit()

        for ((date, label) in parsedHolidays) {
            val existing = getDayType(date)
            if (existing == DayType.OFFICE || existing == DayType.HOLIDAY) continue
            previousStates[date] = existing
            val key = date.toString()
            dates.add(key)
            editor.putString("day_type_$key", DayType.HOLIDAY.name)
            editor.putString("times_$key", label.replace(",", " "))
        }

        editor.putStringSet("all_marked_dates", dates).apply()
        if (previousStates.isNotEmpty()) {
            triggerSilentAutoBackup()
        }
        return previousStates
    }

    fun getFormattedDetail(dateStr: String, type: DayType): String {
        val raw = prefs.getString("times_$dateStr", "") ?: ""
        val note = getDayNote(dateStr)
        val isToday = dateStr == LocalDate.now().toString()
        val stillConnected = isToday && wasConnectedToOfficeWifi()

        val base = when {
            raw.contains("|") -> {
                val parts = raw.split("|")
                val first = parts[0]
                val last = parts[1]
                when {
                    stillConnected -> "Connected $first (active now)"
                    first == last -> "Connected $first"
                    else -> {
                        val dur = formatSpanLabel(first, last)
                        "$first – $last ($dur)"
                    }
                }
            }
            raw.isNotEmpty() -> raw
            else -> when (type) {
                DayType.OFFICE -> "Office"
                DayType.PLANNED -> "Planned"
                DayType.LEAVE -> "Leave"
                DayType.HOLIDAY -> "Holiday"
                DayType.NONE -> ""
            }
        }
        return if (note.isNotEmpty()) "$base • 📝 $note" else base
    }

    fun getDayInspectorInfo(date: LocalDate): DayInspectorInfo {
        val key = date.toString()
        val type = getDayType(key)
        val raw = prefs.getString("times_$key", "") ?: ""
        val note = getDayNote(key)
        val isToday = date == LocalDate.now()
        val stillConnected = isToday && wasConnectedToOfficeWifi()

        val statusLabel = when {
            type == DayType.OFFICE && stillConnected -> "🏢 In Office (Wi-Fi Connected)"
            type == DayType.OFFICE -> "🏢 Confirmed Office"
            type == DayType.PLANNED -> "📅 Planned Office Day"
            type == DayType.LEAVE -> "🌴 Leave"
            type == DayType.HOLIDAY -> "🎉 Holiday"
            date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> "Weekend"
            else -> "Unmarked / WFH"
        }

        val (windowLabel, durationLabel) = when {
            raw.contains("|") -> {
                val parts = raw.split("|")
                val first = parts[0]
                val last = parts[1]
                if (stillConnected) {
                    val nowStr = LocalTime.now().format(timeFmt)
                    val span = formatSpanLabel(first, nowStr)
                    "Connected: $first  •  Status: Still Connected" to "Elapsed So Far: $span"
                } else {
                    val span = formatSpanLabel(first, last)
                    "Connected: $first  •  Disconnected: $last" to "Recorded Span: $span"
                }
            }
            raw.isNotEmpty() -> raw to "Manual Entry"
            else -> "No Wi-Fi timestamps recorded" to "—"
        }

        return DayInspectorInfo(
            date = date,
            type = type,
            statusLabel = statusLabel,
            timeWindowLabel = windowLabel,
            durationLabel = durationLabel,
            note = note
        )
    }

    private fun formatSpanLabel(startStr: String, endStr: String): String {
        return runCatching {
            val t1 = LocalTime.parse(startStr, timeFmt)
            val t2 = LocalTime.parse(endStr, timeFmt)
            val mins = maxOf(0L, Duration.between(t1, t2).toMinutes())
            val h = mins / 60
            val m = mins % 60
            when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }.getOrDefault("0m")
    }

    // ==================== NOTIFICATION DEDUPLICATION ====================

    fun claimOnceFlag(flagKey: String): Boolean {
        if (prefs.getBoolean(flagKey, false)) return false
        prefs.edit().putBoolean(flagKey, true).apply()
        return true
    }

    // ==================== ANALYTICS, TRENDS & SIMULATOR ====================

    fun getMonthStats(
        ym: YearMonth = YearMonth.now(),
        today: LocalDate = LocalDate.now(),
        extraSimulatedLeaves: Int = 0,
        extraSimulatedHolidays: Int = 0
    ): MonthStats {
        val prefix = ym.toString()
        val currentYm = YearMonth.from(today)

        var totalCalendarWeekdays = 0
        var holidayWeekdays = 0
        var leaveWeekdays = 0
        var availableWeekdaysLeft = 0
        var futurePlannedDays = 0

        val typeMap = mutableMapOf<String, DayType>()

        for (day in 1..ym.lengthOfMonth()) {
            val d = ym.atDay(day)
            val key = d.toString()
            val dtype = getDayType(key)
            if (dtype != DayType.NONE) {
                typeMap[key] = dtype
            }

            val isWeekday = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            if (isWeekday) {
                totalCalendarWeekdays++
                if (dtype == DayType.HOLIDAY) holidayWeekdays++
                if (dtype == DayType.LEAVE) leaveWeekdays++

                val isFutureDay = when {
                    ym.isBefore(currentYm) -> false
                    ym.isAfter(currentYm) -> true
                    else -> d.isAfter(today)
                }
                if (isFutureDay && dtype != DayType.HOLIDAY && dtype != DayType.LEAVE) {
                    availableWeekdaysLeft++
                }
            }

            if (dtype == DayType.PLANNED && !d.isBefore(today)) {
                futurePlannedDays++
            }
        }

        val totalHolidays = holidayWeekdays + maxOf(0, extraSimulatedHolidays)
        val totalLeaves = leaveWeekdays + maxOf(0, extraSimulatedLeaves)
        val simExtraDaysOut = maxOf(0, extraSimulatedHolidays) + maxOf(0, extraSimulatedLeaves)
        availableWeekdaysLeft = maxOf(0, availableWeekdaysLeft - simExtraDaysOut)

        val exclHolidays = isExcludeHolidaysEnabled()
        val exclLeaves = isExcludeLeavesEnabled()
        val effectiveWorkingDays = maxOf(
            0,
            totalCalendarWeekdays -
                (if (exclHolidays) totalHolidays else 0) -
                (if (exclLeaves) totalLeaves else 0)
        )

        val targetPct = getTargetPercent()
        val roundUp = isRoundUpEnabled()
        val rawReq = effectiveWorkingDays * (targetPct / 100.0)
        val requiredDays = if (roundUp) ceil(rawReq).toInt() else rawReq.roundToInt()

        val monthKeys = getAllMarkedDates().filter { it.startsWith(prefix) }.sortedDescending()
        val attendedDays = monthKeys.count { getDayType(it) == DayType.OFFICE }
        val projectedAttendedDays = attendedDays + futurePlannedDays

        val actualPercent = if (effectiveWorkingDays > 0) (attendedDays * 100) / effectiveWorkingDays else 100
        val projectedPercent = if (effectiveWorkingDays > 0) (projectedAttendedDays * 100) / effectiveWorkingDays else 100

        val remainingDays = maxOf(0, requiredDays - attendedDays)
        val bufferDays = availableWeekdaysLeft - remainingDays
        val label = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

        val logs = monthKeys.mapNotNull { dateStr ->
            val t = getDayType(dateStr)
            if (t == DayType.NONE) null
            else {
                val d = LocalDate.parse(dateStr)
                val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                DayLog(dateStr, dow, t, getFormattedDetail(dateStr, t))
            }
        }

        return MonthStats(
            yearMonth = ym,
            monthLabel = label,
            targetPercent = targetPct,
            roundUp = roundUp,
            totalCalendarWeekdays = totalCalendarWeekdays,
            holidayWeekdays = totalHolidays,
            leaveWeekdays = totalLeaves,
            effectiveWorkingDays = effectiveWorkingDays,
            requiredDays = requiredDays,
            attendedDays = attendedDays,
            futurePlannedDays = futurePlannedDays,
            projectedAttendedDays = projectedAttendedDays,
            actualPercent = actualPercent,
            projectedPercent = projectedPercent,
            remainingDays = remainingDays,
            availableWeekdaysLeft = availableWeekdaysLeft,
            bufferDays = bufferDays,
            dayTypeMap = typeMap,
            dayLogs = logs
        )
    }

    fun getQuarterStats(
        ym: YearMonth = YearMonth.now(),
        today: LocalDate = LocalDate.now()
    ): QuarterStats {
        val qIndex = (ym.monthValue - 1) / 3 // 0..3
        val startMonth = qIndex * 3 + 1
        val months = listOf(
            YearMonth.of(ym.year, startMonth),
            YearMonth.of(ym.year, startMonth + 1),
            YearMonth.of(ym.year, startMonth + 2)
        )
        val qNames = listOf("Jan–Mar", "Apr–Jun", "Jul–Sep", "Oct–Dec")
        val label = "Q${qIndex + 1} ${ym.year} (${qNames[qIndex]})"

        var totalEff = 0
        var totalAttended = 0
        var totalHolidays = 0
        var totalLeaves = 0
        for (m in months) {
            val ms = getMonthStats(m, today)
            totalEff += ms.effectiveWorkingDays
            totalAttended += ms.attendedDays
            totalHolidays += ms.holidayWeekdays
            totalLeaves += ms.leaveWeekdays
        }

        val targetPct = getTargetPercent()
        val rawReq = totalEff * (targetPct / 100.0)
        val reqDays = if (isRoundUpEnabled()) ceil(rawReq).toInt() else rawReq.roundToInt()
        val remDays = maxOf(0, reqDays - totalAttended)
        val pct = if (totalEff > 0) (totalAttended * 100) / totalEff else 100

        return QuarterStats(
            quarterLabel = label,
            effectiveWorkingDays = totalEff,
            requiredDays = reqDays,
            attendedDays = totalAttended,
            remainingDays = remDays,
            actualPercent = pct,
            holidayDays = totalHolidays,
            leaveDays = totalLeaves
        )
    }

    fun getMultiMonthTrend(
        anchorYm: YearMonth = YearMonth.now(),
        monthsCount: Int = 6,
        today: LocalDate = LocalDate.now()
    ): List<MonthlyTrendPoint> {
        val targetPct = getTargetPercent()
        return (monthsCount - 1 downTo 0).map { offset ->
            val m = anchorYm.minusMonths(offset.toLong())
            val ms = getMonthStats(m, today)
            val shortName = m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            MonthlyTrendPoint(
                ym = m,
                shortMonth = shortName,
                actualPercent = ms.actualPercent,
                targetPercent = targetPct,
                attendedDays = ms.attendedDays,
                requiredDays = ms.requiredDays
            )
        }
    }

    fun getWeeklyPaceStats(today: LocalDate = LocalDate.now()): WeeklyPaceStats {
        val targetPct = getTargetPercent()
        val rawWeeklyQuota = 5.0 * (targetPct / 100.0)
        val weeklyQuota = maxOf(
            1,
            if (isRoundUpEnabled()) ceil(rawWeeklyQuota).toInt() else rawWeeklyQuota.roundToInt()
        )

        val monday = today.with(DayOfWeek.MONDAY)
        var attendedThisWeek = 0
        var weekdaysLeft = 0

        for (i in 0..4) {
            val d = monday.plusDays(i.toLong())
            val t = getDayType(d)
            if (t == DayType.OFFICE) {
                attendedThisWeek++
            } else if (!d.isBefore(today) && t != DayType.HOLIDAY && t != DayType.LEAVE) {
                weekdaysLeft++
            }
        }

        return WeeklyPaceStats(
            weeklyQuota = weeklyQuota,
            attendedThisWeek = attendedThisWeek,
            remainingThisWeek = maxOf(0, weeklyQuota - attendedThisWeek),
            weekdaysLeftThisWeek = weekdaysLeft
        )
    }

    fun getRhythmStats(today: LocalDate = LocalDate.now()): RhythmStats {
        val allDates = getAllMarkedDates()
        val officeDates = allDates.filter { getDayType(it) == DayType.OFFICE }
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }

        val topDaysLabel = if (officeDates.isEmpty()) {
            "No history yet"
        } else {
            officeDates.groupingBy { it.dayOfWeek }
                .eachCount()
                .entries
                .sortedWith(compareBy({ -it.value }, { it.key.value }))
                .take(3)
                .joinToString(", ") { it.key.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }

        val targetPct = getTargetPercent()
        val rawWeeklyQuota = 5.0 * (targetPct / 100.0)
        val weeklyQuota = maxOf(1, if (isRoundUpEnabled()) ceil(rawWeeklyQuota).toInt() else rawWeeklyQuota.roundToInt())

        val wf = WeekFields.ISO
        val officeByWeek = officeDates.groupBy { "${it.get(wf.weekBasedYear())}-W${it.get(wf.weekOfWeekBasedYear())}" }

        var streak = 0
        var cursor = today
        val thisWeekKey = "${cursor.get(wf.weekBasedYear())}-W${cursor.get(wf.weekOfWeekBasedYear())}"
        if ((officeByWeek[thisWeekKey]?.size ?: 0) >= weeklyQuota) {
            streak++
        }
        cursor = cursor.minusWeeks(1)
        while (true) {
            val wkKey = "${cursor.get(wf.weekBasedYear())}-W${cursor.get(wf.weekOfWeekBasedYear())}"
            if ((officeByWeek[wkKey]?.size ?: 0) >= weeklyQuota) {
                streak++
                cursor = cursor.minusWeeks(1)
            } else {
                break
            }
        }

        return RhythmStats(weeklyStreak = streak, topDaysLabel = topDaysLabel)
    }

    // ==================== CSV EXPORT, RESTORE & REPORTS ====================

    fun exportAllCsv(): String {
        val sb = StringBuilder("Date,Day,Status,Connected_Time,Disconnected_Time,Notes\n")
        val sortedDates = getAllMarkedDates().sorted()
        for (dateStr in sortedDates) {
            val dtype = getDayType(dateStr)
            if (dtype == DayType.NONE) continue
            val d = LocalDate.parse(dateStr)
            val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val raw = prefs.getString("times_$dateStr", "") ?: ""
            val customNote = getDayNote(dateStr).replace(",", " ")
            if (raw.contains("|")) {
                val parts = raw.split("|")
                val noteOut = if (customNote.isNotEmpty()) "Auto-WiFi ($customNote)" else "Auto-WiFi"
                sb.append("$dateStr,$dow,${dtype.name},${parts[0]},${parts[1]},$noteOut\n")
            } else {
                val noteOut = if (customNote.isNotEmpty()) customNote else raw.replace(",", " ")
                sb.append("$dateStr,$dow,${dtype.name},,,$noteOut\n")
            }
        }
        return sb.toString()
    }

    fun importFromCsv(csvText: String): Int {
        val dates = getAllMarkedDates()
        val editor = prefs.edit()
        var count = 0

        csvText.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Date", ignoreCase = true)) return@forEach
            val parts = trimmed.split(",")
            val dateStr = parts[0].trim()
            val parsed = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
            val key = parsed.toString()

            val rawStatus = parts.getOrNull(2)?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val dtype = runCatching { DayType.valueOf(rawStatus) }.getOrDefault(DayType.OFFICE)
            if (dtype == DayType.NONE) return@forEach

            dates.add(key)
            editor.putString("day_type_$key", dtype.name)

            val firstIn = parts.getOrNull(3)?.trim().orEmpty()
            val lastSeen = parts.getOrNull(4)?.trim().orEmpty()
            val notes = parts.getOrNull(5)?.trim().orEmpty()

            if (firstIn.isNotEmpty() && lastSeen.isNotEmpty()) {
                editor.putString("times_$key", "$firstIn|$lastSeen")
                if (notes.isNotEmpty() && !notes.equals("Auto-WiFi", ignoreCase = true)) {
                    editor.putString("note_$key", notes)
                }
            } else if (notes.isNotEmpty()) {
                editor.putString("times_$key", notes)
            } else {
                editor.putString("times_$key", getFormattedDetail(key, dtype))
            }
            count++
        }

        editor.putStringSet("all_marked_dates", dates).apply()
        triggerSilentAutoBackup()
        return count
    }

    fun buildMonthlySummaryReport(ym: YearMonth = YearMonth.now()): String {
        val s = getMonthStats(ym)
        val q = getQuarterStats(ym)
        val nameHeader = if (getUserName().isNotEmpty()) " (${getUserName()})" else ""

        val sb = StringBuilder()
        sb.appendLine("WFO Attendance Summary$nameHeader — ${s.monthLabel}")
        sb.appendLine("----------------------------------------")
        sb.appendLine("• Monthly Attendance: ${s.attendedDays} / ${s.requiredDays} days (${s.actualPercent}% of ${s.effectiveWorkingDays} working days)")
        sb.appendLine("• Target Mandate: ${s.targetPercent}% (${if (s.roundUp) "Round Up" else "Nearest"})")
        if (s.futurePlannedDays > 0) {
            sb.appendLine("• Planned Future Days: ${s.futurePlannedDays} (Projected: ${s.projectedAttendedDays}/${s.requiredDays} days, ${s.projectedPercent}%)")
        }
        sb.appendLine("• ${q.quarterLabel}: ${q.attendedDays} / ${q.requiredDays} days (${q.actualPercent}%)")
        sb.appendLine()

        val officeLogs = s.dayLogs.filter { it.type == DayType.OFFICE }.reversed()
        if (officeLogs.isNotEmpty()) {
            sb.appendLine("Confirmed Office Dates:")
            officeLogs.forEach { sb.appendLine("  - ${it.dateStr} (${it.dayOfWeekShort}): ${it.detail}") }
        }

        val leaveLogs = s.dayLogs.filter { it.type == DayType.LEAVE }.reversed()
        if (leaveLogs.isNotEmpty()) {
            sb.appendLine("\nMarked Leaves (${leaveLogs.size}): " + leaveLogs.joinToString(", ") { it.dateStr })
        }

        val holidayLogs = s.dayLogs.filter { it.type == DayType.HOLIDAY }.reversed()
        if (holidayLogs.isNotEmpty()) {
            sb.appendLine("Marked Holidays (${holidayLogs.size}): " + holidayLogs.joinToString(", ") { it.dateStr })
        }

        return sb.toString().trim()
    }

    fun buildAnnualSummaryReport(year: Int = LocalDate.now().year): String {
        val nameHeader = if (getUserName().isNotEmpty()) " (${getUserName()})" else ""
        val r = getRhythmStats()

        var totalEff = 0
        var totalAttended = 0
        var totalReq = 0
        var totalLeaves = 0
        var totalHolidays = 0

        val monthlyLines = mutableListOf<String>()
        for (m in 1..12) {
            val ym = YearMonth.of(year, m)
            val ms = getMonthStats(ym)
            totalEff += ms.effectiveWorkingDays
            totalAttended += ms.attendedDays
            totalReq += ms.requiredDays
            totalLeaves += ms.leaveWeekdays
            totalHolidays += ms.holidayWeekdays
            if (ms.attendedDays > 0 || ms.leaveWeekdays > 0 || ms.holidayWeekdays > 0) {
                monthlyLines.add(
                    "  • ${ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}: ${ms.attendedDays}/${ms.requiredDays} days (${ms.actualPercent}%)"
                )
            }
        }

        val annualPct = if (totalEff > 0) (totalAttended * 100) / totalEff else 0
        val sb = StringBuilder()
        sb.appendLine("WFO Annual Year-in-Review$nameHeader — $year")
        sb.appendLine("========================================")
        sb.appendLine("• Total Office Days: $totalAttended / $totalReq required ($annualPct% of $totalEff working days)")
        sb.appendLine("• Current Weekly Streak: ${r.weeklyStreak} week(s)  |  Top Days: ${r.topDaysLabel}")
        sb.appendLine("• Total Leaves: $totalLeaves  |  Total Holidays: $totalHolidays")
        if (monthlyLines.isNotEmpty()) {
            sb.appendLine("\nMonthly Breakdown:")
            monthlyLines.forEach { sb.appendLine(it) }
        }
        return sb.toString().trim()
    }
}
