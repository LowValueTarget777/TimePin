package org.timepin.app

import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ScaleXSpan

internal object WidgetCaptions {
    private val shift = Regex("""\s*\d{1,2}[:：]\d{2}\s*([-–—~～至])\s*\d{1,2}[:：]\d{2}\s*""")

    fun allDay(title: String, paint: TextPaint, width: Float, lines: Int): CharSequence {
        shiftTitle(title, paint, width, lines)?.let { return it }
        if (lines == 1) return TextUtils.ellipsize(title.replace('\n', ' '), paint, width, TextUtils.TruncateAt.END)
        if ('\n' !in title && paint.measureText(title) <= width) return title
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, width.toInt().coerceAtLeast(1))
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        return (0 until layout.lineCount).joinToString("\n") { line ->
            val start = layout.getLineStart(line)
            if (layout.getEllipsisCount(line) > 0) title.substring(start, start + layout.getEllipsisStart(line)) + "…"
            else title.substring(start, layout.getLineEnd(line)).trimEnd()
        }
    }

    /** Keep both clock times, including leading zeros and the user's separator. */
    fun shiftTitle(title: String, paint: TextPaint, width: Float, lines: Int): CharSequence? {
        val match = shift.matchEntire(title) ?: return null
        val text = title.trim()
        // RemoteViews hosts can round the scaled advance of each clock glyph to a pixel.
        // Leave half a pixel per glyph so the last minute digit stays on its intended line.
        fun usable(line: String) = (width - line.length * .5f).coerceAtLeast(1f)
        if (paint.measureText(text) <= usable(text)) return text
        val parts = if (lines > 1) {
            val split = match.groups[1]!!.range.last + 1
            listOf(title.substring(0, split).trim(), title.substring(split).trim())
        } else listOf(text)
        val scale = parts.minOf { usable(it) / paint.measureText(it) }
        return SpannableString(parts.joinToString("\n")).apply {
            if (scale < 1f) setSpan(ScaleXSpan(scale), 0, length, 0)
        }
    }
}
