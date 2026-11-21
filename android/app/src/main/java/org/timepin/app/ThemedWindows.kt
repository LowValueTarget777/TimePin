package org.timepin.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.ImageButton
import android.os.Build
import android.view.Window
import android.view.WindowManager
import android.widget.Spinner
import kotlin.math.roundToInt

internal fun Context.calendarMode(): String = getSharedPreferences("display",Context.MODE_PRIVATE)
    .getString("theme","frost") ?: "frost"

internal fun Context.calendarDark() = when (calendarMode()) {
    "frost", "light" -> false
    "dark" -> true
    else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

internal fun Activity.installCalendarTheme() = setTheme(when {
    calendarMode() == "frost" -> R.style.CalendarFrost
    calendarDark() -> R.style.CalendarDark
    else -> R.style.CalendarLight
})

private fun Activity.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId == 0) value.data else getColor(value.resourceId)
}

internal fun Activity.pressEffect(radius: Int, tint: Int = themeColor(android.R.attr.colorAccent)): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = radius * resources.displayMetrics.density
    }
    return RippleDrawable(ColorStateList.valueOf((tint and 0xffffff) or 0x30000000), null, mask)
}

internal fun Activity.calendarBack(label: String, goBack: () -> Unit) = ImageButton(this).apply {
    contentDescription = label
    setImageResource(R.drawable.ic_prev)
    imageTintList = ColorStateList.valueOf(themeColor(android.R.attr.textColorPrimary))
    scaleType = android.widget.ImageView.ScaleType.CENTER
    backgroundTintList = null
    background = windowSurface(16)
    foreground = pressEffect(16)
    stateListAnimator = null
    setOnClickListener { goBack() }
}

internal fun Activity.calendarButton(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    stateListAnimator = null
    backgroundTintList = null
    val accent = themeColor(android.R.attr.colorAccent)
    background = if (primary) GradientDrawable().apply {
        cornerRadius = 14 * resources.displayMetrics.density
        setColor(accent)
    } else windowSurface(14)
    setTextColor(if (primary) onColor(accent) else themeColor(android.R.attr.textColorPrimary))
    foreground = pressEffect(14, if (primary) onColor(accent) else accent)
    setOnClickListener { click() }
}

internal fun Activity.windowSurface(radius: Int): GradientDrawable {
    val frost = calendarMode() == "frost"
    val density = resources.displayMetrics.density
    val dark = calendarDark()
    val surface = if (frost) {
        val backdrop = Backdrops.read(this)
        val tint = when (backdrop.mode) {
            "solid" -> Color.parseColor(backdrop.color)
            "photo" -> Backdrops.art(this,backdrop,1,1,true).getPixel(0,0)
            else -> 0xffa9bfda.toInt()
        }
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(
            0xf7ffffff.toInt(),blend(Color.WHITE,tint,.20f) and 0xffffff or 0xed000000.toInt(),
            blend(Color.WHITE,tint,.38f) and 0xffffff or 0xec000000.toInt()))
    } else GradientDrawable().apply { setColor(if (dark) 0xff202b29.toInt() else 0xfffffefa.toInt()) }
    surface.cornerRadius = radius*density
    surface.setStroke(density.roundToInt().coerceAtLeast(1), when {
        frost -> 0xdcffffff.toInt()
        dark -> 0xff43544b.toInt()
        else -> 0xffdbe2d9.toInt()
    })
    return surface
}

internal fun Activity.dimWindow(win: Window) {
    val frost = calendarMode() == "frost"
    win.setDimAmount(if (frost) .27f else .45f)
    if (frost && Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
        win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        win.attributes = win.attributes.apply { blurBehindRadius = (12 * resources.displayMetrics.density).roundToInt() }
    }
}

private fun Activity.styleDialog(dialog: AlertDialog) {
    val win = dialog.window ?: return
    val margin = (12 * resources.displayMetrics.density).roundToInt()
    win.setBackgroundDrawable(InsetDrawable(windowSurface(22),margin))
    win.decorView.clipToOutline = true
    dimWindow(win)
}

internal fun Activity.calendarDialog(): AlertDialog.Builder = object : AlertDialog.Builder(this) {
    override fun create() = super.create().also { styleDialog(it) }
}

internal fun calendarDateDialog(host: Activity, listener: DatePickerDialog.OnDateSetListener,
                                year: Int, month: Int, day: Int) =
    DatePickerDialog(host,listener,year,month,day).also { host.styleDialog(it) }

internal fun calendarTimeDialog(host: Activity, listener: TimePickerDialog.OnTimeSetListener,
                                hour: Int, minute: Int, clock24: Boolean) =
    TimePickerDialog(host,listener,hour,minute,clock24).also { host.styleDialog(it) }

internal fun Activity.calendarSpinner() = Spinner(this,Spinner.MODE_DROPDOWN).apply {
    setPopupBackgroundDrawable(windowSurface(16))
}
