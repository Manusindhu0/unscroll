package com.nextmove.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ReminderScheduler {
    private const val FIRST_REMINDER = 941
    private const val SECOND_REMINDER = 942

    fun schedule(context: Context, firstReminderAt: Long) {
        cancel(context)
        if (firstReminderAt <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstReminderAt, reminderIntent(context, FIRST_REMINDER))
        // A second, gentler prompt. This is intentionally the final nudge for the session.
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            firstReminderAt + 15 * 60_000L,
            reminderIntent(context, SECOND_REMINDER)
        )
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(reminderIntent(context, FIRST_REMINDER))
        alarms.cancel(reminderIntent(context, SECOND_REMINDER))
    }

    private fun reminderIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, BreakReminderReceiver::class.java).setAction("com.nextmove.REMIND.$requestCode")
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
