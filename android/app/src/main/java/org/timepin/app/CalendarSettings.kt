package org.timepin.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.text.InputType
import java.util.Locale

private fun MainActivity.pick(
    title: String,
    values: List<String>,
    selected: Int,
    save: (Int) -> Unit,
) {
    calendarDialog()
        .setTitle(title)
        .setSingleChoiceItems(values.toTypedArray(), selected) { dialog, index ->
            dialog.dismiss()
            save(index)
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun MainActivity.saveDisplay(value: MonthOptions) {
    MonthOptions.save(this, value)
    work(fn = { Widgets.refresh(this) }) { reload() }
}

internal fun MainActivity.monthSettings(card: LinearLayout, item: (String, Int, () -> Unit) -> Unit) {
    val value = MonthOptions.read(this)
    val icon = R.drawable.ic_calendar
    val sunday = value.weekStart == "sunday"
    item("每周第一天 · " + if (sunday) "周日" else "周一", icon) {
        pick("每周第一天", listOf("周日", "周一"), if (sunday) 0 else 1) {
            saveDisplay(value.copy(weekStart = if (it == 0) "sunday" else "monday"))
        }
    }
    val lang = resources.configuration.locales[0]
    fun name(code: String) = Locale("", code).getDisplayCountry(lang).ifEmpty { code }
    val system = "跟随系统地区 · " + name(value.copy(holidayRegion = "system").region(this))
    val region =
        when (value.holidayRegion) {
            "system" -> system
            "off" -> "关闭"
            else -> name(value.holidayRegion)
        }
    item("节假日 · " + region, icon) {
        val codes = listOf("system", "off") + DateNotes.regions(this).sortedBy { name(it) }
        pick(
            "节假日地区",
            listOf(system, "关闭") + codes.drop(2).map(::name),
            codes.indexOf(value.holidayRegion),
        ) {
            saveDisplay(value.copy(holidayRegion = codes[it]))
        }
    }
    val lunarMode = if (!value.lunar) 0 else if (value.lunarFormat == "numeric") 2 else 1
    val lunarRow = row().apply { setPadding(dp(16), dp(6), dp(12), dp(6)); minimumHeight = dp(58) }
    lunarRow.addView(medium("农历", 15), LinearLayout.LayoutParams(0, -2, 1f))
    lunarRow.addView(calendarSpinner().apply {
        contentDescription = "农历显示格式"
        adapter = ArrayAdapter(this@monthSettings, android.R.layout.simple_spinner_dropdown_item,
            listOf("关闭", "中文 · 廿五", "数字 · 11/25"))
        setSelection(lunarMode, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != lunarMode) saveDisplay(value.copy(lunar = position != 0,
                    lunarFormat = if (position == 2) "numeric" else "text"))
            }
        }
    })
    card.addView(lunarRow)
    item("周数 · " + if (value.weekNumbers) "显示" else "隐藏", R.drawable.ic_list) {
        saveDisplay(value.copy(weekNumbers = !value.weekNumbers))
    }
    val sizes = listOf(12, 14, 16, 18)
    val labels = listOf("小", "标准", "大", "特大")
    item("月历字号 · " + labels[sizes.indexOf(value.fontSize)], R.drawable.ic_sliders) {
        pick("月历字号", labels, sizes.indexOf(value.fontSize)) {
            saveDisplay(value.copy(fontSize = sizes[it]))
        }
    }
}

internal fun MainActivity.reminderSettings(item: (String, Int, () -> Unit) -> Unit) {
    val on = Reminders.enabled(this)
    item("日程通知 · " + if (on) "开" else "关", R.drawable.ic_clock) {
        if (
            !on &&
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        else {
            Reminders.configure(this, on = !on)
            reload()
        }
    }
    val manager = getSystemService(android.app.NotificationManager::class.java)
    if (on) {
        val blocked =
            !manager.areNotificationsEnabled() ||
                manager.getNotificationChannel("calendar-events")?.importance ==
                    android.app.NotificationManager.IMPORTANCE_NONE
        item(if (blocked) "系统已关闭通知 · 查看系统设置" else "系统通知设置", R.drawable.ic_sliders) {
            startActivity(
                android.content
                    .Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }
    val minutes = Reminders.minutes(this)
    item("提前时间 · " + (if (minutes == 0) "准时" else "${minutes} 分钟"), R.drawable.ic_clock) {
        val f = padded()
        val input =
            field("提前分钟数", minutes.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        f.addView(text("0 表示准时提醒，最多提前 7 天。全天日程以当天 09:00 为基准。"))
        f.addView(input)
        form("提前通知时间", f, "保存") { d ->
            Reminders.configure(this, advance = input.text.toString().toInt())
            d.dismiss()
            reload()
        }
    }
    item("选择提醒日历", R.drawable.ic_calendar) {
        val available = books.filter { it.role != "none" }
        val excluded = Reminders.muted(this).toMutableSet()
        calendarDialog()
            .setTitle("选择提醒日历")
            .setMultiChoiceItems(
                available.map { it.name }.toTypedArray(),
                BooleanArray(available.size) { available[it].id !in excluded },
            ) { _, i, enabled ->
                if (enabled) excluded.remove(available[i].id) else excluded.add(available[i].id)
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                Reminders.configure(this, excluded = excluded)
                reload()
            }
            .show()
    }
}
