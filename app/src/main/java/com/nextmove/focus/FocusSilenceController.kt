package com.nextmove.focus

import android.app.NotificationManager
import android.content.Context

/** Owns the device-wide quiet period only while a user-started focus session is active. */
class FocusSilenceController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("next_move_silence", Context.MODE_PRIVATE)
    private val notifications = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Android's total-silence DND mode has no per-app media exception. When the
     * user deliberately allows a music app, keep DND off so its audio survives.
     */
    fun activate(keepMusicPlaying: Boolean = false) {
        if (keepMusicPlaying) {
            prefs.edit().clear().apply()
            return
        }
        if (!notifications.isNotificationPolicyAccessGranted) return
        prefs.edit()
            .putBoolean(KEY_CHANGED_DND, true)
            .putInt(KEY_PREVIOUS_FILTER, notifications.currentInterruptionFilter)
            .apply()
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    }

    fun restore() {
        if (!prefs.getBoolean(KEY_CHANGED_DND, false)) return
        val previous = prefs.getInt(KEY_PREVIOUS_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        if (notifications.isNotificationPolicyAccessGranted) notifications.setInterruptionFilter(previous)
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_CHANGED_DND = "changed_dnd"
        private const val KEY_PREVIOUS_FILTER = "previous_filter"

        // The first beta supports the two music services shown in the allow-app picker.
        private val MUSIC_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music"
        )

        fun hasAllowedMusicApp(packages: Set<String>): Boolean = packages.any { it in MUSIC_PACKAGES }
    }
}
