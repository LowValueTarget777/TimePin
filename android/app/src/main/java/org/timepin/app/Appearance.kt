package org.timepin.app

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.time.LocalDate

fun blend(a: Int, b: Int, amount: Float): Int {
    fun channel(left: Int, right: Int) = (left + (right - left) * amount).toInt()
    return Color.rgb(
        channel(Color.red(a), Color.red(b)),
        channel(Color.green(a), Color.green(b)),
        channel(Color.blue(a), Color.blue(b)),
    )
}

fun onColor(color: Int): Int = if (Color.luminance(color) > .179) Color.BLACK else Color.WHITE

fun readableAccent(color: Int, surface: Int, ink: Int): Int {
    val background = Color.luminance(surface)
    var accent = color
    for (step in 1..10) {
        val l = Color.luminance(accent)
        if ((maxOf(l, background) + .05) / (minOf(l, background) + .05) >= 4.5) break
        accent = blend(color, ink, step / 10f)
    }
    return accent
}

fun MainActivity.icon(id: Int, color: Int, padding: Int = 0) =
    ImageView(this).apply {
        setImageResource(id)
        setColorFilter(color)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

fun MainActivity.press(view: View, label: String, radius: Int, action: () -> Unit) {
    view.contentDescription = label
    view.isFocusable = true
    view.foreground = pressEffect(radius)
    view.setOnClickListener { action() }
}

fun MainActivity.iconButton(id: Int, label: String, color: Int, action: () -> Unit): View =
    icon(id, color, 11).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        press(this, label, 16, action)
    }

fun MainActivity.medium(label: String, size: Int, color: Int = ink) =
    text(label, size, color).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

fun MainActivity.navigation(): View {
    val nav = row().apply { if (frosted) background = glass.pane(23) else setBackgroundColor(panel) }
    val tabs =
        listOf(
            "月历" to R.drawable.ic_calendar,
            "日程" to R.drawable.ic_list,
            "日历助手" to R.drawable.ic_spark,
            "设置" to R.drawable.ic_sliders,
        )
    for ((label, symbol) in tabs) {
        val selected = label == page
        val tile = column().apply { gravity = Gravity.CENTER }
        val glyph = icon(symbol, if (selected) green else muted, 5)
        if (selected) glyph.background = shape(blend(panel, green, .14f), 15)
        tile.addView(glyph, dp(50), dp(30))
        tile.addView(
            medium(label, 11, if (selected) green else muted).apply { gravity = Gravity.CENTER },
            -1,
            dp(21),
        )
        tile.isSelected = selected
        press(tile, label, 18) {
            if (label != page || label == "设置") settingsGroup = null
            if (label == "日历助手" && page != label) refreshChatBooks = true
            page = label
            reload()
        }
        nav.addView(tile, weighted())
    }
    return nav
}

enum class SettingGroup(val title: String, val detail: String, val symbol: Int, val tint: Int) {
    CALENDARS("我的日历", "", R.drawable.ic_calendar, 0xff28775F.toInt()),
    IMPORT("导入日历", "识别手机系统日历", R.drawable.ic_upload, 0xff26775C.toInt()),
    CONNECTION("账号与同步", "NAS、共享与冲突", R.drawable.ic_sync, 0xff476BA6.toInt()),
    MODEL("日历助手", "个人模型与接口", R.drawable.ic_spark, 0xff8260A6.toInt()),
    WIDGETS("桌面组件", "月历与近期日程", R.drawable.ic_widgets, 0xffAD5B39.toInt()),
    DISPLAY("月历显示", "周起始、节假日与字号", R.drawable.ic_calendar, 0xff28775F.toInt()),
    REMINDERS("日程通知", "提前时间与提醒日历", R.drawable.ic_clock, 0xff866031.toInt()),
    ABOUT("关于", "版本与更新", R.drawable.ic_server, 0xff476BA6.toInt()),
    THEME("外观", "主题", R.drawable.ic_sun, 0xff986E24.toInt()),
}

fun MainActivity.groupInk(group: SettingGroup) =
    if (dark) blend(group.tint, Color.WHITE, .50f) else group.tint

fun MainActivity.settingRow(
    label: String,
    symbol: Int,
    tint: Int,
    value: String = "",
    showNext: Boolean = true,
    action: () -> Unit,
): View {
    val line =
        row().apply {
            setPadding(dp(14), dp(7), dp(12), dp(7))
            minimumHeight = dp(58)
        }
    line.addView(
        icon(symbol, tint, 7).apply { background = shape(blend(panel, tint, .11f), 11) },
        dp(34),
        dp(34),
    )
    val name = medium(label, 15).apply { setPadding(dp(13), 0, dp(6), 0) }
    line.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    if (value.isNotEmpty()) line.addView(text(value, 13, muted))
    if (showNext) line.addView(icon(R.drawable.ic_next, muted, 4), dp(20), dp(20))
    press(line, listOf(label, value).filter(String::isNotEmpty).joinToString("，"), 16, action)
    return line
}

fun MainActivity.settingsHome(): View {
    val layout = column().apply { setPadding(dp(18), dp(2), dp(18), dp(18)) }
    val current = books.firstOrNull { it.id == active }
    val banner =
        row().apply {
            background = if (frosted) glaze(24, 0xf53a647b, 0xef3f647d, 0xf15c6594) else shape(0xff184C45.toInt(), 24)
            setPadding(dp(20), dp(16), dp(17), dp(16))
            minimumHeight = dp(if (frosted) 93 else 119)
            if (frosted) setPadding(dp(18), dp(8), dp(14), dp(8))
        }
    val info = column()
    info.addView(text("当前日历", 12, 0xffC1E1D4.toInt()))
    info.addView(
        medium(current?.name ?: "我的日历", 24, Color.WHITE).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    )
    info.addView(
        text("管理日历  ·  " + books.size + " 本", 12, 0xffC1E1D4.toInt()).apply {
            setPadding(0, dp(5), 0, 0)
        }
    )
    banner.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    banner.addView(calendarLeaf(), dp(85), dp(80))
    press(banner, "管理日历，" + (current?.name ?: "我的日历"), 24) { showSettings(SettingGroup.CALENDARS) }
    layout.addView(banner, -1, -2)

    val cardGroups =
        listOf(
            SettingGroup.IMPORT,
            SettingGroup.CONNECTION,
            SettingGroup.MODEL,
            SettingGroup.WIDGETS,
        )
    for (pair in cardGroups.chunked(2)) {
        val band =
            row().apply {
                isBaselineAligned = false
                gravity = Gravity.TOP
            }
        layout.addView(band, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (frosted) 9 else 12) })
        for ((index, group) in pair.withIndex()) {
            val tint = groupInk(group)
            val card =
                column().apply {
                    background = shape(panel, 19)
                    val inset = dp(if (frosted) 10 else 15)
                    setPadding(dp(15), inset, dp(12), inset)
                }
            val top = row()
            top.addView(
                icon(group.symbol, tint, 8).apply {
                    background = shape(blend(panel, tint, .14f), 12)
                },
                dp(if (frosted) 32 else 38),
                dp(if (frosted) 32 else 38),
            )
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(icon(R.drawable.ic_next, muted, 3), dp(16), dp(16))
            card.addView(top)
            card.addView(medium(group.title, 17).apply { setPadding(0, dp(if (frosted) 7 else 12), 0, dp(3)) })
            val detail = if (group == SettingGroup.CONNECTION) {
                Account.read(this)?.let { AccountProfiles.read(this,it).optString("displayName") }
                    ?: if (Account.selectedServer(this).isEmpty()) "连接日历服务器" else "服务器已选择 · 未登录"
            } else group.detail
            card.addView(text(detail, 12, muted))
            press(card, group.title, 19) { showSettings(group) }
            band.addView(
                card,
                LinearLayout.LayoutParams(0, -1, 1f).apply { if (index == 1) marginStart = dp(12) },
            )
        }
    }
    val teams = settingRow("我的团组", R.drawable.ic_people, groupInk(SettingGroup.CALENDARS)) {
        openCollaboration()
    }
    teams.background = shape(panel, 18)
    layout.addView(teams, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    for (group in listOf(SettingGroup.THEME, SettingGroup.REMINDERS, SettingGroup.ABOUT)) {
        val entry = settingRow(group.title,group.symbol,groupInk(group),
            if (group == SettingGroup.THEME) themeName() else "") { showSettings(group) }
        entry.background = shape(panel,18)
        layout.addView(entry, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
    }
    return layout
}

internal fun MainActivity.themeName() = when (themeMode) {
    "frost" -> "雾光 · 毛玻璃"
    "dark" -> "深色"
    "light" -> "浅色"
    else -> "跟随系统"
}

internal fun MainActivity.appearanceSettings(): View {
    val body = column().apply { setPadding(dp(18),0,dp(18),dp(16)) }
    fun section(name: String) = column().apply {
        body.addView(medium(name,13,muted).apply { setPadding(dp(4),dp(9),0,dp(7)) })
        background = shape(panel,18)
        body.addView(this)
    }
    fun changeBackground(id: Int) = startActivity(android.content.Intent(this,BackdropActivity::class.java)
        .putExtra("widgetId",id))
    fun summary(bg: Backdrop) = when (bg.mode) {
        "follow" -> "跟随应用"
        "solid" -> Backdrops.colorNames.getOrNull(Backdrops.colors.indexOf(bg.color)) ?: "自定义纯色"
        "photo" -> "自选图片"
        else -> "雾光"
    }
    val app = section("应用主题")
    app.addView(settingRow("主题样式",R.drawable.ic_sun,groupInk(SettingGroup.THEME),themeName()) { chooseTheme() })
    app.addView(settingRow("雾光主题背景",R.drawable.ic_photo,green,summary(Backdrops.read(this))) { changeBackground(0) })
    val date = MonthOptions.read(this)
    section("月历显示").addView(settingRow("日期与字号",R.drawable.ic_calendar,groupInk(SettingGroup.DISPLAY),
        (if (date.weekStart == "sunday") "周日开始" else "周一开始") + " · " +
            when (date.fontSize) { 12 -> "小字"; 14 -> "标准字"; 16 -> "大字"; else -> "特大字" }) { showSettings(SettingGroup.DISPLAY) })
    val widgets = section("桌面小组件")
    val installed = Widgets.installed(this)
    for ((kind,label) in listOf("month" to "月历", "agenda" to "近期日程")) {
        val ids = installed.filterValues { it == kind }.keys.sorted()
        val icon = if (kind == "month") R.drawable.ic_calendar else R.drawable.ic_list
        if (ids.isEmpty()) widgets.addView(settingRow("添加" + label + "组件",icon,green) { pinCalendarWidget(kind == "month") })
        for ((index,id) in ids.withIndex()) {
            val title = label + "背景" + if (ids.size > 1) " · " + (index + 1) else ""
            widgets.addView(settingRow(title,icon,green,summary(Backdrops.read(this,id))) { changeBackground(id) })
        }
    }
    return body
}

private fun MainActivity.calendarLeaf(): View {
    val frame = FrameLayout(this)
    fun place(view: View, width: Int, height: Int, left: Int, top: Int) {
        frame.addView(
            view,
            FrameLayout.LayoutParams(dp(width), dp(height)).apply {
                leftMargin = dp(left)
                topMargin = dp(top)
            },
        )
    }
    place(
        View(this).apply {
            background = if (frosted) glaze(11, 0xbcbfd6fc, 0x6d93bade) else shape(0xff77AD93.toInt(), 11)
            rotation = 11f
        },
        58,
        60,
        19,
        12,
    )
    val page =
        column().apply {
            gravity = Gravity.CENTER
            background = if (frosted) glaze(10, 0xfff2faff, 0xe5c5dcf0) else shape(0xffEDF2CC.toInt(), 10)
            rotation = -7f
        }
    val binding = row().apply { gravity = Gravity.CENTER }
    repeat(3) {
        binding.addView(
            View(this).apply { background = shape(if (frosted) green else 0xff779A7E.toInt(), 3) },
            LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            },
        )
    }
    page.addView(binding, -1, dp(13))
    page.addView(
        medium(LocalDate.now().dayOfMonth.toString(), 26, if (frosted) green else 0xff235244.toInt()).apply {
            gravity = Gravity.CENTER
        },
        -1,
        dp(34),
    )
    place(page, 58, 60, 10, 8)
    frame.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    return frame
}
