package com.nextmove.focus

import android.app.AlarmManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Reads Android's actual Screen Pinning / lock-task state; it is not UI state. */
object FocusLockMonitor {
    fun isActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }
}

/** Wakes the app at a session end so quiet mode is restored even if the app is not open. */
object FocusEndScheduler {
    private const val REQUEST_CODE = 843

    fun schedule(context: Context, endAt: Long) {
        cancel(context)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(context))
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, FocusEndReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = FocusSessionStore(context)
        // If the user escaped Screen Pinning while the app was away, never let a
        // background alarm turn that interrupted round into a completed one.
        if (store.focusLockWasVerified() && !FocusLockMonitor.isActive(context)) {
            store.interruptForFocusLockExit()
        } else {
            // Calling activeSession records a natural completion when the time has elapsed.
            store.activeSession()
        }
    }
}
