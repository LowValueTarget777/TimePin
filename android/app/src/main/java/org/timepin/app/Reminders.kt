package org.timepin.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.room.InvalidationTracker
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean

object Reminders {
    private const val CHANNEL = "calendar-events"
    private const val ALARM = "org.timepin.app.REMIND"

    private fun prefs(c: Context) = c.getSharedPreferences("reminders", 0)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun minutes(c: Context) = prefs(c).getInt("minutes", 15)

    fun muted(c: Context) = prefs(c).getStringSet("muted", emptySet()).orEmpty().toSet()

    @Synchronized
    fun configure(
        c: Context,
        on: Boolean = enabled(c),
        advance: Int = minutes(c),
        excluded: Set<String> = muted(c),
    ) {
        require(advance in 0..10080) { "提前时间为 0–10080 分钟" }
        val n = c.getSystemService(NotificationManager::class.java)
        if (on)
            n.createNotificationChannel(
                NotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        if (!on) n.cancelAll()
        prefs(c)
            .edit()
            .putLong("configVersion", prefs(c).getLong("configVersion", 0L) + 1)
            .putBoolean("enabled", on)
            .putInt("minutes", advance)
            .putStringSet("muted", excluded)
            .putLong("checked", System.currentTimeMillis())
            .apply()
        if (on) watch(c)
        queue(c)
    }

    private val queued = AtomicBoolean(false)

    fun queue(c: Context) {
        val ctx = c.applicationContext
        if (queued.compareAndSet(false, true))
            Store.io.execute {
                queued.set(false)
                reschedule(ctx)
            }
    }

    private val watching = AtomicBoolean(false)

    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val c = context.applicationContext
        Store.io.execute {
            Store.get(c)
                .invalidationTracker
                .addObserver(
                    object : InvalidationTracker.Observer("books", "events") {
                        override fun onInvalidated(tables: Set<String>) {
                            if (enabled(c)) queue(c)
                        }
                    }
                )
        }
        if (enabled(c)) queue(c)
    }

    private fun pending(c: Context) =
        PendingIntent.getBroadcast(
            c,
            0,
            Intent(c, ReminderReceiver::class.java).setAction(ALARM),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun reschedule(c: Context, now: Long = System.currentTimeMillis()) {
        val generation = prefs(c).getLong("configVersion", 0L)
        val alarms = c.getSystemService(AlarmManager::class.java)
        val signal = pending(c)
        if (!enabled(c)) {
            alarms.cancel(signal)
            return
        }
        val manager = c.getSystemService(NotificationManager::class.java)
        if (
            !manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
        ) {
            alarms.cancel(signal)
            return
        }
        val db = Store.get(c)
        val excluded = muted(c)
        val books = db.accountBooks(c).filter { it.role != "none" && it.id !in excluded }
        if (books.isEmpty()) {
            alarms.cancel(signal)
            return
        }
        val zone = ZoneId.systemDefault()
        val lead = minutes(c) * 60000L
        val since = prefs(c).getLong("checked", now).coerceIn(now - 86400000L, now)
        val horizon = now + 7 * 86400000L
        fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        var next = Long.MAX_VALUE
        db.visitAgenda(books, day(since + lead), day(horizon + lead).plusDays(1), zone) { slot ->
            val starts =
                if (slot.span.kind == "date") slot.span.first.atTime(9, 0).atZone(zone).toInstant()
                else OffsetDateTime.parse(slot.span.start).toInstant()
            val due = starts.toEpochMilli() - lead
            if (due > now) {
                next = minOf(next, due)
                return@visitAgenda
            }
            if (due <= since) return@visitAgenda
            val ends =
                if (slot.span.kind == "date") slot.span.last.atStartOfDay(zone).toInstant()
                else OffsetDateTime.parse(slot.span.end).toInstant()
            if (ends.toEpochMilli() <= now) return@visitAgenda
            val date = slot.span.daysIn(zone).first
            val key =
                Uri.Builder()
                    .scheme("timepin")
                    .authority("reminder")
                    .appendPath(slot.cal)
                    .appendPath(slot.id)
                    .appendPath(slot.on)
                    .build()
            val open =
                Intent(c, MainActivity::class.java)
                    .setAction("org.timepin.OPEN")
                    .setData(key)
                    .putExtra("day", date.toString())
                    .putExtra("cal", slot.cal)
                    .putExtra("event", slot.id)
            val body =
                books.first { it.id == slot.cal }.name +
                    " · " +
                    date +
                    " " +
                    slot.span.label(zone).ifEmpty { "全天" }
            val alert =
                Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_calendar)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setWhen(starts.toEpochMilli())
                    .setShowWhen(true)
                    .setContentTitle(slot.title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            c,
                            0,
                            open,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                    .build()
            if (
                Build.VERSION.SDK_INT < 33 ||
                    c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            )
                synchronized(this) {
                    if (prefs(c).getLong("configVersion", 0L) == generation)
                        manager.notify(key.toString(), 1, alert)
                }
        }
        synchronized(this) {
            if (prefs(c).getLong("configVersion", 0L) != generation) return
            prefs(c).edit().putLong("checked", now).apply()
            if (next == Long.MAX_VALUE)
                alarms.setWindow(AlarmManager.RTC, horizon - 3600000L, 3600000L, signal)
            else if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, signal)
                } catch (_: SecurityException) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, signal)
                }
            } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, signal)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Reminders.enabled(context)) return
        val done = goAsync()
        Store.io.execute {
            try {
                Reminders.reschedule(context)
            } finally {
                done.finish()
            }
        }
    }
}
