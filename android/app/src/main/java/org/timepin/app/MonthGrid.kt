package org.timepin.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal class MonthGrid(
    private val app: MainActivity,
    val options: MonthOptions,
    private val openDay: (LocalDate, List<Slot>) -> Unit,
) : LinearLayout(app) {
    var month: YearMonth? = null
        private set

    private val holidays = DateNotes.holidays(app, options.region(app))
    private var today: LocalDate? = null
    private var zone: ZoneId? = null
    private var displayed = emptyList<Slot>()
    private val weeks = mutableListOf<Week>()
    private var selected: DayCell? = null
    private val highlight = app.shape(blend(app.base, app.green, .09f), 9)
    private val frostLabel by lazy { Typeface.create("sans-serif-condensed-medium", Typeface.NORMAL) }

    init {
        orientation = VERTICAL
        if (app.frosted) {
            background = app.glass.pane(22)
            setPadding(app.dp(4), app.dp(6), app.dp(4), app.dp(6))
            clipToOutline = true
        }
        val weekdays = app.row().apply { isBaselineAligned = false }
        if (options.weekNumbers) weekdays.addView(View(app), app.dp(20), -1)
        for (name in options.weekdays) weekdays.addView(
            app.text("周" + name, options.fontSize - 2, app.muted).apply {
                gravity = Gravity.CENTER
            },
            app.weighted(),
        )
        addView(weekdays, -1, app.dp(26))
    }

    fun bind(target: YearMonth, day: LocalDate, slots: List<Slot>) {
        val localZone = ZoneId.systemDefault()
        val now = LocalDate.now(localZone)
        if (target != month || today != now || zone != localZone || displayed != slots) {
            val first = target.atDay(1)
            val lead = options.lead(first)
            val start = first.minusDays(lead.toLong())
            val count = (lead + target.lengthOfMonth() + 6) / 7
            val last = start.plusDays(count * 7L - 1)
            val byDay = mutableMapOf<LocalDate, MutableList<Slot>>()
            for (slot in slots) {
                val (from, to) = slot.span.daysIn(localZone)
                var date = maxOf(from, start)
                val until = minOf(to, last)
                while (date <= until) {
                    byDay.getOrPut(date) { mutableListOf() }.add(slot)
                    date = date.plusDays(1)
                }
            }
            while (weeks.size > count) {
                weeks.removeAt(weeks.lastIndex)
                removeViews(childCount - 2, 2)
            }
            while (weeks.size < count) weeks.add(Week())
            for ((index, week) in weeks.withIndex()) {
                val rowStart = start.plusWeeks(index.toLong())
                week.number.text = options.week(rowStart).toString()
                for ((weekday, cell) in week.days.withIndex()) {
                    val date = rowStart.plusDays(weekday.toLong())
                    cell.bind(date, target, now, byDay[date].orEmpty(), localZone)
                }
            }
            month = target
            today = now
            zone = localZone
            displayed = slots
        }
        select(weeks.firstNotNullOfOrNull { week -> week.days.find { it.date == day } })
    }

    private fun select(cell: DayCell?) {
        if (selected === cell) return
        selected?.apply {
            background = null
            isSelected = false
        }
        cell?.apply {
            background = highlight
            isSelected = true
        }
        selected = cell
    }

    private inner class Week {
        val number =
            app.text("", options.fontSize - 4, app.muted).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, app.dp(5), 0, 0)
            }
        val days = List(7) { DayCell() }

        init {
            val line =
                View(app).apply {
                    setBackgroundColor(if (app.frosted) 0x38628099 else if (app.dark) 0xff282c28.toInt() else 0xffd9e2da.toInt())
                }
            this@MonthGrid.addView(line, -1, 1)
            val row =
                app.row().apply {
                    gravity = Gravity.TOP
                    isBaselineAligned = false
                }
            if (options.weekNumbers) row.addView(number, app.dp(20), -1)
            for (cell in days) row.addView(cell, app.weighted())
            this@MonthGrid.addView(row, app.weighted(false))
        }
    }

    private inner class DayCell : LinearLayout(app) {
        lateinit var date: LocalDate
        private var events = emptyList<Slot>()
        private val number = app.text("", options.fontSize).apply { gravity = Gravity.CENTER }
        private val note =
            app.text("", (options.fontSize - 5).coerceAtLeast(9), app.muted).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }

        init {
            orientation = VERTICAL
            setPadding(1, app.dp(3), 1, 0)
            addView(
                number,
                LayoutParams(app.dp(31), app.dp(options.fontSize + 9)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            addView(note, -1, -2)
            setOnClickListener {
                choose()
                openDay(date, events)
            }
        }

        private fun choose() {
            app.day = date
            select(this)
        }

        fun bind(
            d: LocalDate,
            target: YearMonth,
            now: LocalDate,
            items: List<Slot>,
            localZone: ZoneId,
        ) {
            date = d
            number.text = d.dayOfMonth.toString()
            number.contentDescription = d.toString()
            note.text = DateNotes.label(d, holidays, options)
            note.visibility = if (note.text.isEmpty()) GONE else VISIBLE
            number.background = if (d == now) app.shape(app.green, 12) else null
            number.setTextColor(
                when {
                    d == now -> onColor(app.green)
                    d.month != target.month -> app.muted
                    d.dayOfWeek.value == 7 -> 0xffca5555.toInt()
                    else -> app.ink
                }
            )
            if (items == events && localZone == zone) return
            events = items
            if (childCount > 2) removeViews(2, childCount - 2)
            for (slot in items.take(3)) {
                val color = Color.parseColor(slot.color)
                val surface = if (app.frosted) blend(app.panel, color, .33f) else color
                val labelColor = if (app.frosted) readableAccent(color, surface, app.ink) else onColor(color)
                val time = slot.span.label(localZone)
                val chip =
                    app.text(time.ifEmpty { slot.title }, options.fontSize - 3, labelColor)
                        .apply {
                            gravity = Gravity.CENTER
                            maxLines = 1
                            includeFontPadding = false
                            setAutoSizeTextTypeUniformWithConfiguration(
                                options.fontSize - 6,
                                options.fontSize - 3,
                                1,
                                TypedValue.COMPLEX_UNIT_SP,
                            )
                            ellipsize = TextUtils.TruncateAt.END
                            if (app.frosted) typeface = frostLabel
                            background = app.shape(surface, if (app.frosted) 5 else 3)
                            contentDescription = slot.title + " " + time
                            setOnClickListener {
                                choose()
                                app.editEvent(slot.cal, slot.id)
                            }
                        }
                addView(
                    chip,
                    LayoutParams(-1, app.dp(options.fontSize + 4)).apply {
                        bottomMargin = app.dp(1)
                        if (app.frosted) setMargins(app.dp(1), 0, app.dp(1), app.dp(2))
                    },
                )
            }
            if (items.size > 3)
                addView(app.text("+${items.size-3}", options.fontSize - 4, app.muted))
        }
    }
}
