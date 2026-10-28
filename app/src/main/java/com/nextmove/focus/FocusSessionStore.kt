package com.nextmove.focus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class FocusSession(
    val task: String,
    val startedAt: Long,
    val durationMillis: Long,
    val remoteSessionId: String? = null,
    val craft: String = "Practice",
    val isRescue: Boolean = false
) {
    val endsAt: Long get() = startedAt + durationMillis
    fun progress(now: Long = System.currentTimeMillis()): Float =
        ((now - startedAt).toFloat() / durationMillis).coerceIn(0f, 1f)
}

data class FocusHistoryEntry(
    val task: String,
    val startedAt: Long,
    val durationMillis: Long,
    val completed: Boolean,
    val endedAt: Long,
    val craft: String = "Practice",
    val isRescue: Boolean = false,
    /** True when Android's Focus Lock was exited before the round finished. */
    val interrupted: Boolean = false
)

data class RescueOffer(val task: String, val craft: String)

data class WeeklyFocusDay(
    val label: String,
    val minutes: Int,
    val isToday: Boolean
)

/** Local beta data. The same session model can be synced to the social backend later. */
class FocusSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("next_move_state", Context.MODE_PRIVATE)
    @Volatile private var cachedHistoryRaw: String? = null
    @Volatile private var cachedHistory: List<FocusHistoryEntry> = emptyList()

    fun start(
        task: String,
        durationMinutes: Int,
        remoteSessionId: String? = null,
        startedAtMillis: Long = System.currentTimeMillis(),
        craft: String = currentCraft(),
        isRescue: Boolean = false
    ) {
        val startAt = startedAtMillis
        val duration = durationMinutes * 60_000L
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TASK, task.trim())
            .putLong(KEY_STARTED_AT, startAt)
            .putLong(KEY_DURATION, duration)
            .putString(KEY_ACTIVE_REMOTE_SESSION_ID, remoteSessionId)
            .putString(KEY_ACTIVE_CRAFT, cleanCraft(craft))
            .putBoolean(KEY_ACTIVE_IS_RESCUE, isRescue)
            .putBoolean(KEY_FOCUS_LOCK_VERIFIED, false)
            .putBoolean(KEY_COMPLETION_PENDING, false)
            .putBoolean(KEY_FOCUS_LOCK_INTERRUPTED_PENDING, false)
            .apply()
        FocusEndScheduler.schedule(appContext, startAt + duration)
    }

    fun startRescue(task: String, craft: String) {
        clearRescueOffer()
        start(task, RESCUE_DURATION_MINUTES, craft = craft, isRescue = true)
    }

    /**
     * A focus round begins locally before its optional League write returns. This
     * attaches the verified session afterwards without delaying the hard lock.
     */
    fun attachRemoteSession(sessionId: String) {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        prefs.edit().putString(KEY_ACTIVE_REMOTE_SESSION_ID, sessionId).apply()
    }

    fun activeSession(): FocusSession? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val session = storedSession() ?: run {
            completeNaturally(null)
            return null
        }
        if (System.currentTimeMillis() >= session.endsAt) {
            completeNaturally(session)
            return null
        }
        return session
    }

    /** Lets the UI validate Focus Lock before an overdue timer can complete. */
    fun hasActiveSessionRecord(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun endEarly() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val session = storedSession()
        session?.let { addHistory(it, completed = false) }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_COMPLETION_PENDING, false)
            .remove(KEY_ACTIVE_REMOTE_SESSION_ID)
            .apply {
                if (session != null && !session.isRescue) {
                    putString(KEY_RESCUE_TASK, session.task)
                    putString(KEY_RESCUE_CRAFT, session.craft)
                    putLong(KEY_RESCUE_OFFERED_AT, System.currentTimeMillis())
                }
            }
            .apply {
                session?.remoteSessionId?.let { putString(KEY_PENDING_EARLY_END_SESSION_ID, it) }
            }
            .apply()
        FocusEndScheduler.cancel(appContext)
        FocusSilenceController(appContext).restore()
    }

    /**
     * Ends an in-progress round without granting any XP, history completion, or
     * League score when Android's Screen Pinning has been exited. This is
     * deliberately separate from a voluntary early end: it never offers a
     * rescue round.
     */
    @Synchronized
    fun interruptForFocusLockExit(): Boolean {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
        val session = storedSession()
        session?.let { addHistory(it, completed = false, interrupted = true) }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_COMPLETION_PENDING, false)
            .putBoolean(KEY_FOCUS_LOCK_VERIFIED, false)
            .putBoolean(KEY_FOCUS_LOCK_INTERRUPTED_PENDING, true)
            .remove(KEY_ACTIVE_REMOTE_SESSION_ID)
            .apply {
                session?.remoteSessionId?.let { putString(KEY_PENDING_EARLY_END_SESSION_ID, it) }
            }
            .apply()
        FocusEndScheduler.cancel(appContext)
        FocusSilenceController(appContext).restore()
        return true
    }

    fun markFocusLockVerified() {
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            prefs.edit().putBoolean(KEY_FOCUS_LOCK_VERIFIED, true).apply()
        }
    }

    fun focusLockWasVerified(): Boolean = prefs.getBoolean(KEY_FOCUS_LOCK_VERIFIED, false)

    private fun completeNaturally(session: FocusSession?) {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        session?.let { addHistory(it, completed = true) }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_COMPLETION_PENDING, true)
            .remove(KEY_ACTIVE_REMOTE_SESSION_ID)
            .apply {
                session?.remoteSessionId?.let { putString(KEY_PENDING_COMPLETION_SESSION_ID, it) }
            }
            .apply()
        FocusEndScheduler.cancel(appContext)
        FocusSilenceController(appContext).restore()
    }

    private fun storedSession(): FocusSession? {
        val duration = prefs.getLong(KEY_DURATION, 0L)
        if (duration <= 0L) return null
        return FocusSession(
            task = prefs.getString(KEY_TASK, "Focus") ?: "Focus",
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            durationMillis = duration,
            remoteSessionId = prefs.getString(KEY_ACTIVE_REMOTE_SESSION_ID, null),
            craft = prefs.getString(KEY_ACTIVE_CRAFT, currentCraft()) ?: currentCraft(),
            isRescue = prefs.getBoolean(KEY_ACTIVE_IS_RESCUE, false)
        )
    }

    @Synchronized
    fun consumeCompletion(): Boolean {
        val pending = prefs.getBoolean(KEY_COMPLETION_PENDING, false)
        if (!pending) return false
        // Make the acknowledgement durable before a second lifecycle render can
        // read the old value and show the completion screen again.
        return prefs.edit().putBoolean(KEY_COMPLETION_PENDING, false).commit()
    }

    @Synchronized
    fun consumeFocusLockInterruption(): Boolean {
        val pending = prefs.getBoolean(KEY_FOCUS_LOCK_INTERRUPTED_PENDING, false)
        if (!pending) return false
        return prefs.edit().putBoolean(KEY_FOCUS_LOCK_INTERRUPTED_PENDING, false).commit()
    }

    /** Server updates are kept until acknowledged, so a completed round survives a bad connection. */
    fun pendingRemoteCompletionId(): String? = prefs.getString(KEY_PENDING_COMPLETION_SESSION_ID, null)
    fun pendingRemoteEarlyEndId(): String? = prefs.getString(KEY_PENDING_EARLY_END_SESSION_ID, null)
    fun clearPendingRemoteCompletion() = prefs.edit().remove(KEY_PENDING_COMPLETION_SESSION_ID).apply()
    fun clearPendingRemoteEarlyEnd() = prefs.edit().remove(KEY_PENDING_EARLY_END_SESSION_ID).apply()

    fun saveNextTask(task: String, reminderAt: Long?) {
        prefs.edit()
            .putString(KEY_NEXT_TASK, task.trim())
            .putLong(KEY_NEXT_REMINDER, reminderAt ?: 0L)
            .apply()
    }

    fun nextTask(): String? = prefs.getString(KEY_NEXT_TASK, null)?.takeIf { it.isNotBlank() }
    fun nextReminderAt(): Long = prefs.getLong(KEY_NEXT_REMINDER, 0L)

    fun clearNextTask() {
        prefs.edit().remove(KEY_NEXT_TASK).remove(KEY_NEXT_REMINDER).apply()
    }

    fun rescueOffer(): RescueOffer? {
        val offeredAt = prefs.getLong(KEY_RESCUE_OFFERED_AT, 0L)
        if (offeredAt <= 0L || !isToday(offeredAt)) {
            clearRescueOffer()
            return null
        }
        val task = prefs.getString(KEY_RESCUE_TASK, null)?.takeIf { it.isNotBlank() } ?: return null
        val craft = prefs.getString(KEY_RESCUE_CRAFT, currentCraft()) ?: currentCraft()
        return RescueOffer(task, craft)
    }

    fun clearRescueOffer() {
        prefs.edit()
            .remove(KEY_RESCUE_TASK)
            .remove(KEY_RESCUE_CRAFT)
            .remove(KEY_RESCUE_OFFERED_AT)
            .apply()
    }

    /** Every launchable app is blocked during focus unless it appears in this allowlist. */
    fun allowedPackages(): Set<String> = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()

    fun setAllowedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages).putBoolean(KEY_ALLOWLIST_CONFIGURED, true).apply()
    }

    fun ensureDefaultAllowedPackages(defaultPackages: Set<String>) {
        if (!prefs.getBoolean(KEY_ALLOWLIST_CONFIGURED, false)) setAllowedPackages(defaultPackages)
    }

    /**
     * Existing beta users may have enabled the old always-on blocker. On the first
     * build with Banking-safe Focus Guard, deliberately make hard app control an
     * explicit choice again instead of silently carrying that setting forward.
     */
    fun ensureBankingSafeDefault() {
        if (prefs.getBoolean(KEY_BANKING_SAFE_DEFAULT_APPLIED, false)) return
        prefs.edit()
            .putBoolean(KEY_HARD_SHIELD_OPTED_IN, false)
            .putBoolean(KEY_BANKING_SAFE_DEFAULT_APPLIED, true)
            .apply()
    }

    fun hardShieldOptedIn(): Boolean = prefs.getBoolean(KEY_HARD_SHIELD_OPTED_IN, false)
    fun setHardShieldOptedIn(enabled: Boolean) = prefs.edit().putBoolean(KEY_HARD_SHIELD_OPTED_IN, enabled).apply()

    fun currentCraft(): String = prefs.getString(KEY_CURRENT_CRAFT, "Practice") ?: "Practice"
    fun setCurrentCraft(value: String) = prefs.edit().putString(KEY_CURRENT_CRAFT, cleanCraft(value)).apply()

    fun craftMinutes(craft: String, entries: List<FocusHistoryEntry> = history()): Int = entries
        .filter { it.completed && it.craft.equals(craft, ignoreCase = true) }
        .sumOf { (it.durationMillis / 60_000L).toInt() }

    fun mainCraft(entries: List<FocusHistoryEntry> = history()): String = entries
        .filter { it.completed }
        .groupBy { it.craft }
        .maxByOrNull { (_, values) -> values.sumOf { it.durationMillis } }
        ?.key ?: currentCraft()

    fun username(): String? = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }
    fun setUsername(value: String) = prefs.edit().putString(KEY_USERNAME, value.trim().removePrefix("@")).apply()

    fun history(): List<FocusHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        if (raw == cachedHistoryRaw) return cachedHistory
        val parsed = try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        FocusHistoryEntry(
                            task = item.optString("task", "Focus"),
                            startedAt = item.optLong("startedAt"),
                            durationMillis = item.optLong("durationMillis"),
                            completed = item.optBoolean("completed"),
                            endedAt = item.optLong("endedAt"),
                            craft = item.optString("craft", "Practice"),
                            isRescue = item.optBoolean("isRescue", false),
                            interrupted = item.optBoolean("interrupted", false)
                        )
                    )
                }
            }.sortedByDescending { it.startedAt }
        } catch (_: Exception) {
            emptyList()
        }
        cachedHistoryRaw = raw
        cachedHistory = parsed
        return parsed
    }

    fun todayScore(): Int = history()
        .filter { it.completed && isToday(it.endedAt) }
        .sumOf { (it.durationMillis / 60_000L).toInt() }

    fun completedCount(): Int = history().count { it.completed }
    fun startedCount(): Int = history().size
    fun totalFocusMinutes(): Int = history()
        .filter { it.completed }
        .sumOf { (it.durationMillis / 60_000L).toInt() }

    fun bestFocusedDayMinutes(): Int = history()
        .filter { it.completed }
        .groupBy { dayKey(it.endedAt) }
        .values
        .maxOfOrNull { entries -> entries.sumOf { (it.durationMillis / 60_000L).toInt() } }
        ?: 0

    /** A streak stays visible through today until a full day is missed. */
    fun currentStreakDays(entries: List<FocusHistoryEntry> = history()): Int {
        val focusedDays = entries
            .filter { it.completed }
            .map { dayKey(it.endedAt) }
            .toSet()
        var days = 0
        val cursor = Calendar.getInstance()
        if (!focusedDays.contains(dayKey(cursor.timeInMillis))) {
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        while (focusedDays.contains(dayKey(cursor.timeInMillis))) {
            days += 1
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return days
    }

    fun weeklyFocus(entries: List<FocusHistoryEntry> = history()): List<WeeklyFocusDay> {
        val completed = entries.filter { it.completed }
        val today = Calendar.getInstance()
        return (-6..0).map { offset ->
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val key = dayKey(day.timeInMillis)
            WeeklyFocusDay(
                label = day.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
                    ?.take(1)
                    ?: "·",
                minutes = completed
                    .filter { dayKey(it.endedAt) == key }
                    .sumOf { (it.durationMillis / 60_000L).toInt() },
                isToday = offset == 0
            )
        }
    }

    private fun addHistory(session: FocusSession, completed: Boolean, interrupted: Boolean = false) {
        val entries = history().toMutableList()
        entries.add(
            FocusHistoryEntry(
                task = session.task,
                startedAt = session.startedAt,
                durationMillis = session.durationMillis,
                completed = completed,
                endedAt = System.currentTimeMillis(),
                craft = session.craft,
                isRescue = session.isRescue,
                interrupted = interrupted
            )
        )
        val array = JSONArray()
        entries.sortedByDescending { it.startedAt }.take(MAX_HISTORY).forEach { entry ->
            array.put(
                JSONObject()
                    .put("task", entry.task)
                    .put("startedAt", entry.startedAt)
                    .put("durationMillis", entry.durationMillis)
                    .put("completed", entry.completed)
                    .put("endedAt", entry.endedAt)
                    .put("craft", entry.craft)
                    .put("isRescue", entry.isRescue)
                    .put("interrupted", entry.interrupted)
            )
        }
        val serialized = array.toString()
        cachedHistoryRaw = serialized
        cachedHistory = entries.sortedByDescending { it.startedAt }.take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, serialized).apply()
    }

    private fun isToday(timestamp: Long): Boolean {
        val current = Calendar.getInstance()
        val other = Calendar.getInstance().apply { timeInMillis = timestamp }
        return current.get(Calendar.ERA) == other.get(Calendar.ERA) &&
            current.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            current.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    }

    private fun dayKey(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return "${calendar.get(Calendar.ERA)}-${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun cleanCraft(value: String): String = value.trim().take(32).ifBlank { "Practice" }

    companion object {
        private const val MAX_HISTORY = 500
        private const val KEY_ACTIVE = "active"
        private const val KEY_TASK = "task"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_DURATION = "duration"
        private const val KEY_ACTIVE_REMOTE_SESSION_ID = "active_remote_session_id"
        private const val KEY_ACTIVE_CRAFT = "active_craft"
        private const val KEY_ACTIVE_IS_RESCUE = "active_is_rescue"
        private const val KEY_FOCUS_LOCK_VERIFIED = "focus_lock_verified"
        private const val KEY_COMPLETION_PENDING = "completion_pending"
        private const val KEY_FOCUS_LOCK_INTERRUPTED_PENDING = "focus_lock_interrupted_pending"
        private const val KEY_PENDING_COMPLETION_SESSION_ID = "pending_completion_session_id"
        private const val KEY_PENDING_EARLY_END_SESSION_ID = "pending_early_end_session_id"
        private const val KEY_NEXT_TASK = "next_task"
        private const val KEY_NEXT_REMINDER = "next_reminder"
        private const val KEY_RESCUE_TASK = "rescue_task"
        private const val KEY_RESCUE_CRAFT = "rescue_craft"
        private const val KEY_RESCUE_OFFERED_AT = "rescue_offered_at"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_ALLOWLIST_CONFIGURED = "allowlist_configured"
        private const val KEY_HARD_SHIELD_OPTED_IN = "hard_shield_opted_in"
        private const val KEY_BANKING_SAFE_DEFAULT_APPLIED = "banking_safe_default_applied"
        private const val KEY_USERNAME = "username"
        private const val KEY_CURRENT_CRAFT = "current_craft"
        private const val KEY_HISTORY = "history"
        private const val RESCUE_DURATION_MINUTES = 5
    }
}
