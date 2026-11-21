package org.timepin.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.*

object Widgets {
    private data class RenderSize(val width: Int, val height: Int, val portrait: Boolean, val date: LocalDate)
    private val rendered = java.util.concurrent.ConcurrentHashMap<Int, RenderSize>()

    private fun renderSize(c: Context, id: Int): RenderSize {
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        val portrait = c.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return RenderSize(
            o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
            o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            portrait, LocalDate.now())
    }

    // Some launchers bind the host before delivering the provider callbacks.
    fun reconcile(c: Context) {
        val installed = installed(c)
        rendered.keys.retainAll(installed.keys)
        for ((id, type) in installed) {
            if (rendered[id] != renderSize(c,id)) render(c,id,type == "month")
        }
    }
    const val PINNED = "org.timepin.WIDGET_PINNED"

    fun requestPin(c: Context, provider: ComponentName): Boolean {
        val manager = AppWidgetManager.getInstance(c)
        if (!manager.isRequestPinAppWidgetSupported) return false
        val ready =
            PendingIntent.getBroadcast(
                c,
                0,
                Intent(PINNED).setComponent(provider),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return manager.requestPinAppWidget(provider, null, ready)
    }

    private val cells = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6)

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun selected(c: Context, id: Int) = prefs(c).getStringSet("books-" + id, null)?.toSet()

    fun installed(c: Context): Map<Int, String> {
        val manager = AppWidgetManager.getInstance(c)
        return buildMap {
            for ((type, name) in listOf(MonthWidget::class.java to "month", AgendaWidget::class.java to "agenda")) {
                for (id in manager.getAppWidgetIds(ComponentName(c,type))) put(id,name)
            }
        }
    }

    fun save(c: Context, id: Int, ids: Set<String>) {
        prefs(c).edit().putStringSet("books-" + id, ids.toSet()).apply()
    }

    fun drop(c: Context, id: Int) {
        Backdrops.drop(c,id)
        rendered.remove(id)
        prefs(c).edit().remove("books-" + id).remove("offset-" + id).apply()
    }

    fun move(c: Context, id: Int, step: Int) {
        val p = prefs(c)
        p.edit().putInt("offset-" + id, p.getInt("offset-" + id, 0) + step).apply()
    }

    fun refresh(c: Context, changed: Set<String>? = null): Set<Int> {
        val refreshed = mutableSetOf<Int>()
        val mgr = AppWidgetManager.getInstance(c)
        for (type in listOf(MonthWidget::class.java, AgendaWidget::class.java)) {
            for (id in mgr.getAppWidgetIds(ComponentName(c, type))) {
                val choices = selected(c, id)
                if (changed == null || choices == null || choices.any { it in changed }) {
                    render(c, id, type == MonthWidget::class.java)
                    refreshed.add(id)
                }
            }
        }
        return refreshed
    }

    fun render(c: Context, id: Int, month: Boolean) {
        val layoutSize = renderSize(c,id)
        val db = Store.get(c)
        db.initLocal()
        val books = db.accountBooks(c).filter { it.role != "none" }
        val choices = selected(c, id) ?: books.filter { it.visible }.map { it.id }.toSet()
        val mine = books.filter { it.id in choices }
        val today = LocalDate.now()
        val ym = YearMonth.from(today).plusMonths(prefs(c).getInt("offset-" + id, 0).toLong())
        val display = MonthOptions.read(c)
        val lead = display.lead(ym.atDay(1))
        val first = if (month) ym.atDay(1).minusDays(lead.toLong()) else today
        val weeks = (ym.lengthOfMonth() + lead + 6) / 7
        val end = if (month) first.plusWeeks(weeks.toLong()) else today.plusDays(7)
        val zone = ZoneId.systemDefault()
        val slots = db.agenda(mine, first, end, zone)
        val mgr = AppWidgetManager.getInstance(c)
        fun action(kind: String, day: LocalDate = today, slot: Slot? = null): PendingIntent {
            val i =
                when (kind) {
                    "prev",
                    "next" -> Intent(c, MonthWidget::class.java).setAction("org.timepin.PAGE")
                    "setup" -> Intent(c, WidgetSettings::class.java)
                    else -> Intent(c, MainActivity::class.java).setAction("org.timepin.OPEN")
                }
            i.data =
                Uri.Builder()
                    .scheme("timepin")
                    .authority("widget")
                    .appendPath(id.toString())
                    .appendPath(kind)
                    .appendPath(day.toString())
                    .appendPath(slot?.id ?: "")
                    .build()
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra("step", if (kind == "prev") -1 else 1)
                .putExtra("day", day.toString())
                .putExtra("selectOnly", kind == "select")
                .putExtra("new", kind == "add")
                .putExtra("cal", slot?.cal ?: mine.firstOrNull()?.id)
                .putExtra("event", slot?.id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (kind in listOf("prev", "next")) PendingIntent.getBroadcast(c, 0, i, flags)
            else PendingIntent.getActivity(c, 0, i, flags)
        }
        val width = layoutSize.width.takeIf { it > 0 } ?: 280
        val height = layoutSize.height.takeIf { it > 0 } ?: if (month) 245 else 205
        val narrow = width < 210
        val mini = height < 144
        val layout = if (mini) R.layout.glass_widget_mini else R.layout.glass_widget
        val v = RemoteViews(c.packageName, layout)
        val endDate = today.plusDays(6)
        val spec = Backdrops.resolve(c,id)
        val paper = when (spec.mode) {
            "solid" -> Color.parseColor(spec.color)
            else -> 0xffdce5ef.toInt()
        }
        val preferredInk = if (onColor(paper) == Color.WHITE) 0xfff1f6fc.toInt() else 0xff1d3549.toInt()
        val ink = readableAccent(preferredInk,paper,onColor(paper))
        val muted = readableAccent(blend(ink,paper,.25f),paper,ink)
        val heading = if (month) "${ym.monthValue}月" else "近期日程"
        val subtitle =
            if (month) "${ym.year} 年"
            else
                "${today.monthValue}.${today.dayOfMonth} — ${endDate.monthValue}.${endDate.dayOfMonth}"
        v.setTextViewText(R.id.heading, heading)
        if (!mini) v.setTextViewText(R.id.widget_subtitle, subtitle)
        v.setContentDescription(R.id.widget_open, "$heading，$subtitle")
        v.setOnClickPendingIntent(
            R.id.widget_open,
            action("open", if (month) ym.atDay(1) else today),
        )
        if (!mini) v.setViewVisibility(R.id.widget_subtitle, if (height < 145) View.GONE else View.VISIBLE)
        for ((view, act) in
            listOf(
                R.id.prev to "prev",
                R.id.next to "next",
                R.id.setup to "setup",
                R.id.add to "add",
            )) {
            v.setOnClickPendingIntent(view, action(act))
            v.setInt(view, "setColorFilter", ink)
        }
        if (!month) {
            v.setViewVisibility(R.id.prev, View.GONE)
            v.setViewVisibility(R.id.next, View.GONE)
        }
        v.removeAllViews(R.id.items)
        v.setInt(R.id.widget_surface,"setBackgroundResource",0)
        v.setViewVisibility(R.id.backdrop,View.VISIBLE)
        // Clear the rectangular fill when the launcher reapplies an older RemoteViews.
        v.setInt(R.id.backdrop, "setBackgroundResource", 0)
        if (spec.mode == "solid") {
            v.setImageViewResource(R.id.backdrop, R.drawable.widget_surface)
            v.setInt(R.id.backdrop, "setColorFilter", paper)
        } else {
            val source = if (Backdrops.read(c,id).mode == "follow") 0 else id
            v.setInt(R.id.backdrop, "setColorFilter", Color.TRANSPARENT)
            v.setImageViewBitmap(R.id.backdrop, Backdrops.widgetTexture(c, spec, source, width, height))
        }
        v.setTextColor(R.id.heading,ink)
        if (!mini) v.setTextColor(R.id.widget_subtitle,muted)
        v.setTextColor(R.id.focus_title,ink)
        v.setContentDescription(R.id.setup,"组件设置")
        if (month) {
            val density = c.resources.displayMetrics
            val holidays = DateNotes.holidays(c, display.region(c))
            val padding = AppWidgetHostView.getDefaultPaddingForWidget(c, ComponentName(c, MonthWidget::class.java), null)
            val gridWidthPx = (width - (if (mini) 12 else 22) - (if (display.weekNumbers) 16 else 0)) *
                density.density - padding.left - padding.right
            val cellPx = (gridWidthPx / 7f - 2f).coerceAtLeast(1f)
            val showWeekdays = height >= 128
            val gridHeight = height - (padding.top + padding.bottom) / density.density -
                (if (mini) 29 else 60) - (if (showWeekdays) 16 else 0)
            val rowHeight = gridHeight / weeks
            var daySp = (display.fontSize - if (rowHeight < 18) 5 else 2).toFloat()
            val paint =
                TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, daySp, density)
                }
            if (rowHeight >= 20f) {
                val scale = (rowHeight * density.density / (2 * (paint.fontSpacing + 2))).coerceAtMost(1f)
                daySp = (daySp * scale).coerceAtLeast(8f)
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, daySp, density)
            }
            val notePaint = TextPaint(paint).apply { textSize *= .75f }
            val captionPaint = TextPaint(paint).apply { textSize *= .83f }
            val lineHeight = kotlin.math.ceil(paint.descent() - paint.ascent()) + 1f
            val captionHeight = kotlin.math.ceil(captionPaint.descent() - captionPaint.ascent()) + 1f
            val captions = ((rowHeight * density.density - lineHeight) / captionHeight).toInt().coerceIn(0, 3)
            fun caption(slot: Slot, lines: Int): CharSequence {
                val range = slot.span.label(zone)
                if (range.isEmpty()) return WidgetCaptions.allDay(slot.title, captionPaint, cellPx, lines)
                val compact = range.replace(":00–", "–").removeSuffix(":00")
                return if (captionPaint.measureText(compact) <= cellPx) compact
                else range.substringBefore('–')
            }
            val visible = mutableMapOf<LocalDate, MutableList<Slot>>()
            for (slot in slots) {
                val (start, last) = slot.span.daysIn(zone)
                var day = maxOf(first, start)
                while (day < end && day <= last) {
                    val cell = visible.getOrPut(day) { mutableListOf() }
                    if (cell.size < 2) cell.add(slot)
                    day = day.plusDays(1)
                }
            }
            val focusDay = if (captions == 0) {
                val dates = visible.keys.filter { YearMonth.from(it) == ym }
                dates.filter { it >= today }.minOrNull() ?: dates.maxOrNull()
            } else null
            val dateSp = if (focusDay == null) daySp else
                minOf(daySp, daySp * (gridHeight - 16) * density.density / (weeks * lineHeight)).coerceAtLeast(6f)
            val head = RemoteViews(c.packageName, R.layout.widget_weekdays)
            for ((k, view) in cells.withIndex()) {
                head.setTextViewText(view, display.weekdays[k].toString())
                head.setTextColor(view, muted)
                head.setTextViewTextSize(
                    view,
                    TypedValue.COMPLEX_UNIT_SP,
                    (display.fontSize - 4).toFloat(),
                )
            }
            head.setViewVisibility(
                R.id.week_number,
                if (display.weekNumbers) View.VISIBLE else View.GONE,
            )
            if (showWeekdays) v.addView(R.id.items, head)
            for (w in 0 until weeks) {
                val week = RemoteViews(c.packageName, R.layout.widget_week)
                week.setViewVisibility(
                    R.id.week_number,
                    if (display.weekNumbers) View.VISIBLE else View.GONE,
                )
                week.setTextViewText(
                    R.id.week_number,
                    display.week(first.plusWeeks(w.toLong())).toString(),
                )
                week.setTextColor(R.id.week_number, muted)
                week.setTextViewTextSize(
                    R.id.week_number,
                    TypedValue.COMPLEX_UNIT_SP,
                    (display.fontSize - 5).toFloat(),
                )
                for (d in 0..6) {
                    val day = first.plusDays((w * 7 + d).toLong())
                    val events = visible[day].orEmpty()
                    val note = DateNotes.label(day, holidays, display)
                    // Keep two lines for an all-day shift that wraps on narrow launchers.
                    val noteBelow = note.isNotEmpty() && captions > minOf(2, events.size * 2)
                    val captionLines = captions - if (noteBelow) 1 else 0
                    val span = SpannableStringBuilder().apply {
                        append(day.dayOfMonth.toString())
                        if (note.isNotEmpty()) {
                            val noteStart = length
                            val space = if (noteBelow) cellPx else
                                (cellPx - paint.measureText(day.dayOfMonth.toString() + " "))
                                    .coerceAtLeast(0f)
                            append(
                                (if (noteBelow) "\n" else " ") +
                                    android.text.TextUtils.ellipsize(
                                        note,
                                        notePaint,
                                        space,
                                        android.text.TextUtils.TruncateAt.END,
                                    )
                            )
                            setSpan(RelativeSizeSpan(.75f), noteStart, length, 0)
                            setSpan(ForegroundColorSpan(muted), noteStart, length, 0)
                        }
                        if (captions == 0 && events.isNotEmpty()) append("·")
                        val shown = events.take(minOf(2, captionLines))
                        var available = captionLines
                        shown.forEachIndexed { index, slot ->
                            val label = caption(slot, available - (shown.lastIndex - index))
                            append('\n')
                            val start = length
                            append(label)
                            val color = readableAccent(Color.parseColor(slot.color), paper, ink)
                            setSpan(ForegroundColorSpan(color), start, length, 0)
                            setSpan(RelativeSizeSpan(.83f), start, length, 0)
                            available -= 1 + label.count { it == '\n' }
                        }
                    }
                    week.setTextViewText(cells[d], span)
                    week.setTextViewTextSize(
                        cells[d],
                        TypedValue.COMPLEX_UNIT_SP,
                        dateSp,
                    )
                    week.setTextColor(cells[d], if (day.month != ym.month) muted else ink)
                    if (day == today) {
                        week.setInt(cells[d], "setBackgroundResource", R.drawable.glass_today)
                    }
                    week.setContentDescription(
                        cells[d],
                        (listOf(day.toString(), note).filter { it.isNotEmpty() } +
                                events.map { it.title })
                            .joinToString("，"),
                    )
                    week.setOnClickPendingIntent(
                        cells[d],
                        action(if (events.isEmpty()) "select" else "day", day),
                    )
                }
                v.addView(R.id.items, week)
            }
            v.setViewVisibility(R.id.focus_title, if (focusDay == null) View.GONE else View.VISIBLE)
            if (focusDay != null) {
                val slot = visible.getValue(focusDay).first()
                v.setTextViewText(R.id.focus_title, "${focusDay.monthValue}/${focusDay.dayOfMonth}  ${slot.title}")
                v.setOnClickPendingIntent(R.id.focus_title, action("day", focusDay))
            }
        } else {
            val compact = height < 160
            val titleSp = if (narrow) 13f else 14f
            val metrics = c.resources.displayMetrics
            val titlePaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, titleSp, metrics)
            }
            val titleWidth = ((width - (if (mini) 10 else 18) - 6 - 37 - 11 - 12) *
                metrics.density).coerceAtLeast(1f)
            val capacity =
                ((height - (if (compact) 66 else 78)) / (if (compact) 26 else 41)).coerceIn(1, 20)
            if (slots.isEmpty()) {
                val empty = RemoteViews(c.packageName, R.layout.widget_line)
                for (view in listOf(R.id.agenda_day, R.id.agenda_color, R.id.agenda_time)) empty
                    .setViewVisibility(view, View.GONE)
                empty.setTextViewText(R.id.line, if (mine.isEmpty()) "请选择日历" else "近七天没有日程")
                empty.setTextColor(R.id.line,ink)
                empty.setOnClickPendingIntent(
                    R.id.agenda_row,
                    action(if (mine.isEmpty()) "setup" else "open"),
                )
                v.addView(R.id.items, empty)
            }
            for (s in slots.take(capacity)) {
                val row = RemoteViews(c.packageName, R.layout.widget_line)
                val day = s.span.daysIn(zone).first
                val date =
                    when (day) {
                        today -> "今天"
                        today.plusDays(1) -> "明天"
                        else -> "${day.monthValue}/${day.dayOfMonth}"
                    }
                val time = s.span.label(zone).ifEmpty { "全天" }
                val color = readableAccent(Color.parseColor(s.color), paper, ink)
                row.setTextColor(R.id.line,ink)
                row.setTextColor(R.id.agenda_day,muted)
                row.setTextColor(R.id.agenda_time,muted)
                row.setInt(R.id.agenda_color, "setColorFilter", color)
                row.setTextViewText(R.id.agenda_day, date)
                val shift = if (s.span.kind == "date") WidgetCaptions.shiftTitle(s.title, titlePaint, titleWidth, 1) else null
                val label = shift ?: if (compact) s.title + "  " + time else s.title
                row.setTextViewText(R.id.line, label)
                if (compact) row.setViewVisibility(R.id.agenda_time, View.GONE)
                else row.setTextViewText(R.id.agenda_time, time)
                if (narrow) row.setTextViewTextSize(R.id.line, TypedValue.COMPLEX_UNIT_SP, titleSp)
                row.setContentDescription(R.id.agenda_row, "$date，${s.title}，$time")
                row.setOnClickPendingIntent(R.id.agenda_row, action("event", day, s))
                v.addView(R.id.items, row)
            }
        }
        mgr.updateAppWidget(id, v)
        rendered[id] = layoutSize
    }
}

open class CalendarWidget : AppWidgetProvider() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "org.timepin.PAGE") {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (
                AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider !=
                    ComponentName(c, MonthWidget::class.java)
            )
                return
            Widgets.move(c, id, i.getIntExtra("step", 0).coerceIn(-1, 1))
            update(c, intArrayOf(id))
        } else if (
            i.action in
                setOf(
                    Widgets.PINNED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_LOCALE_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                )
        ) {
            val ids = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, javaClass))
            if (ids.isNotEmpty()) update(c, ids)
        } else super.onReceive(c, i)
    }

    private fun update(c: Context, ids: IntArray) {
        val p = goAsync()
        Store.io.execute {
            try {
                ids.forEach { Widgets.render(c, it, this is MonthWidget) }
            } finally {
                p.finish()
            }
        }
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = update(c, ids)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) =
        update(c, intArrayOf(id))

    override fun onDeleted(c: Context, ids: IntArray) {
        ids.forEach { Widgets.drop(c, it) }
    }
}

class MonthWidget : CalendarWidget()

class AgendaWidget : CalendarWidget()

class WidgetSettings : Activity() {
    override fun onCreate(state: Bundle?) {
        installCalendarTheme()
        theme.applyStyle(R.style.WidgetSettingsOverlay,true)
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info == null || info.provider.packageName != packageName) {
            finish()
            return
        }
        Store.io.execute {
            val db = Store.get(this)
            db.initLocal()
            val books = db.accountBooks(this).filter { it.role != "none" }
            val prior = Widgets.selected(this, id)
            val marks =
                BooleanArray(books.size) { prior?.contains(books[it].id) ?: books[it].visible }
            runOnUiThread {
                val dialog = calendarDialog()
                    .setTitle("此组件显示的日历")
                    .setMultiChoiceItems(books.map { it.name }.toTypedArray(), marks) { _, i, v ->
                        marks[i] = v
                    }
                    .setNegativeButton("取消") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .setPositiveButton("保存") { _, _ ->
                        Widgets.save(
                            this,
                            id,
                            books.filterIndexed { i, _ -> marks[i] }.map { it.id }.toSet(),
                        )
                        Store.io.execute {
                            Widgets.render(
                                this,
                                id,
                                info.provider.className == MonthWidget::class.java.name,
                            )
                            runOnUiThread {
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                                )
                                finish()
                            }
                        }
                    }
                    .apply { setNeutralButton("背景外观", null) }
                    .show()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    startActivity(Intent(this,BackdropActivity::class.java).putExtra("widgetId",id))
                }
            }
        }
    }
}
