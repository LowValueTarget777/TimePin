package org.timepin.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import java.time.LocalDate

internal class SystemCalendarWatcher(private val activity: MainActivity) {
    companion object {
        private const val ENABLED = "automatic"
        private const val ASKED = "permissionAsked"
        private fun preferences(c: Context) = c.getSharedPreferences("system-calendars", Context.MODE_PRIVATE)
        fun enabled(c: Context) = preferences(c).getBoolean(ENABLED, true)
        fun setEnabled(c: Context, enabled: Boolean) = preferences(c).edit().putBoolean(ENABLED, enabled).apply()
        private fun runtimeGrant(c: Context) =
            c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        fun hasPermission(c: Context) = runtimeGrant(c) && SystemCalendars.accessAllowed(c)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    @Volatile private var observing = false
    private var asking = false
    private var running = false
    private var pending = false
    private val scan = Runnable { startScan() }
    private val changes = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (observing) schedule()
        }
    }
    private val options = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ENABLED && active) configure()
    }

    fun resume() {
        if (!active) preferences(activity).registerOnSharedPreferenceChangeListener(options)
        active = true
        configure()
        if (enabled(activity) && !runtimeGrant(activity) && !preferences(activity).getBoolean(ASKED, false))
            requestAccess()
    }

    fun requestAccess() {
        if (asking || hasPermission(activity)) return
        if (runtimeGrant(activity) || (preferences(activity).getBoolean(ASKED, false) &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR))) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
            return
        }
        preferences(activity).edit().putBoolean(ASKED, true).apply()
        asking = true
        activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 43)
    }

    fun permissionResult() {
        asking = false
        if (active) configure()
    }

    private fun configure() {
        if (!enabled(activity) || !hasPermission(activity)) {
            detach()
            return
        }
        if (observing) return
        try {
            activity.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, changes)
            observing = true
            schedule()
        } catch (_: SecurityException) {
            activity.message("请在系统应用权限中允许 TimePin 读取日历")
        }
    }

    private fun schedule() {
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, 500)
    }

    private fun startScan() {
        if (!observing) return
        if (running) {
            pending = true
            return
        }
        running = true
        val c = activity.applicationContext
        Store.io.execute {
            val changed = mutableSetOf<String>()
            var failure: String? = null
            try {
                val from = LocalDate.now().withDayOfYear(1).minusYears(1)
                for (source in SystemCalendars.list(c)) {
                    if (!observing || !enabled(c) || !hasPermission(c)) break
                    try {
                        val (id, counts) = SystemCalendars.refreshLocal(c, source, from, from.plusYears(3))
                        if (listOf("newCalendar", "created", "updated").any { (counts[it] ?: 0) > 0 })
                            changed.add(id)
                    } catch (e: Exception) {
                        failure = e.localizedMessage ?: "系统日历暂时不可用"
                    }
                }
                if (changed.isNotEmpty()) Widgets.refresh(c, changed)
            } catch (e: Exception) {
                failure = e.localizedMessage ?: "系统日历暂时不可用"
            } finally {
                handler.post {
                    running = false
                    if (observing && !activity.isDestroyed) {
                        if (changed.isNotEmpty()) {
                            activity.reload()
                            activity.autoSync()
                        }
                        failure?.let { activity.message("自动导入日历：" + it) }
                        if (pending) schedule()
                    }
                    pending = false
                }
            }
        }
    }

    private fun detach() {
        if (observing) activity.contentResolver.unregisterContentObserver(changes)
        observing = false
        pending = false
        handler.removeCallbacks(scan)
    }

    fun stop() {
        active = false
        preferences(activity).unregisterOnSharedPreferenceChangeListener(options)
        detach()
    }
}
