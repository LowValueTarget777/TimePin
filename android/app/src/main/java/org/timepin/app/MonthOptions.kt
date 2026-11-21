package org.timepin.app

import android.content.Context
import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class MonthOptions(
    val weekStart: String = "sunday",
    val weekNumbers: Boolean = true,
    val lunar: Boolean = false,
    val holidayRegion: String = "system",
    val fontSize: Int = 14,
    val lunarFormat: String = "text",
) {
    val weekdays
        get() = if (weekStart == "sunday") "日一二三四五六" else "一二三四五六日"

    fun lead(day: LocalDate) = (day.dayOfWeek.value + if (weekStart == "monday") 6 else 0) % 7

    fun week(rowStart: LocalDate) =
        rowStart
            .plusDays(if (weekStart == "monday") 3 else 4)
            .get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    fun json() =
        obj(
            "weekStart" to weekStart,
            "weekNumbers" to weekNumbers,
            "lunar" to lunar,
            "lunarFormat" to lunarFormat,
            "holidayRegion" to holidayRegion,
            "fontSize" to fontSize,
        )

    fun region(c: Context): String {
        if (holidayRegion != "system") return holidayRegion
        val locale = c.resources.configuration.locales[0]
        return locale.getUnicodeLocaleType("rg")?.take(2)?.uppercase(Locale.ROOT)
            ?: locale.country.ifEmpty {
                ULocale.addLikelySubtags(ULocale.forLocale(locale)).country
            }
    }

    companion object {
        fun read(c: Context): MonthOptions {
            val j = JSONObject(c.getSharedPreferences("display", 0).getString("month", "{}")!!)
            return MonthOptions(
                j.optString("weekStart", "sunday"),
                j.optBoolean("weekNumbers", true),
                j.optBoolean("lunar"),
                j.optString("holidayRegion", "system"),
                j.optInt("fontSize", 14),
                j.optString("lunarFormat", "text"),
            )
        }

        fun save(c: Context, value: MonthOptions) {
            require(
                value.weekStart in listOf("monday", "sunday") &&
                    value.fontSize in listOf(12, 14, 16, 18) && value.lunarFormat in listOf("text","numeric")
            )
            require(
                value.holidayRegion in listOf("off", "system") ||
                    value.holidayRegion in DateNotes.regions(c)
            )
            c.getSharedPreferences("display", 0)
                .edit()
                .putString("month", value.json().toString())
                .apply()
        }
    }
}

object DateNotes {
    private var codes: List<String>? = null
    private var loaded = ""
    private var holidays = JSONObject()

    @Synchronized
    fun regions(c: Context): List<String> {
        codes?.let {
            return it
        }
        return c.assets
            .open("holidays/regions.json")
            .bufferedReader()
            .use { JSONArray(it.readText()).strings() }
            .also { codes = it }
    }

    @Synchronized
    fun holidays(c: Context, region: String): JSONObject {
        if (loaded != region) {
            holidays =
                if (region == "off" || region !in regions(c)) JSONObject()
                else
                    c.assets.open("holidays/" + region + ".json").bufferedReader().use {
                        JSONObject(it.readText())
                    }
            loaded = region
        }
        return holidays
    }

    private val chinese =
        ThreadLocal.withInitial {
            ChineseCalendar(TimeZone.getTimeZone("Asia/Shanghai"), ULocale.CHINA)
        }

    fun lunar(date: LocalDate, format: String = "text"): String {
        val cal = chinese.get()!!
        cal.timeInMillis = date.atTime(12, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        val n = cal.get(Calendar.DAY_OF_MONTH)
        if (format == "numeric") return (if (cal.get(Calendar.IS_LEAP_MONTH) == 1) "闰" else "") +
            (cal.get(Calendar.MONTH) + 1) + "/" + n
        if (n == 1)
            return (if (cal.get(Calendar.IS_LEAP_MONTH) == 1) "闰" else "") +
                listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")[
                    cal.get(Calendar.MONTH)] +
                "月"
        return when (n) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> listOf("初", "十", "廿")[n / 10] + "〇一二三四五六七八九"[n % 10]
        }
    }

    fun label(date: LocalDate, holidays: JSONObject, options: MonthOptions) =
        listOfNotNull(
                holidays.optString(date.toString()).takeIf { it.isNotEmpty() },
                if (options.lunar) lunar(date, options.lunarFormat) else null,
            )
            .joinToString(" · ")
}
