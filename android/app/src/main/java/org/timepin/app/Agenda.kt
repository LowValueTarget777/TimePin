package org.timepin.app

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
val colorPattern = Regex("#[0-9a-fA-F]{6}")
private val clockFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

fun stamp(value: ZonedDateTime): String = value.format(clockFormat)

private fun offset(text: String, zone: ZoneId): ZonedDateTime {
    val x = OffsetDateTime.parse(text)
    require(zone.rules.getValidOffsets(x.toLocalDateTime()).contains(x.offset)) { "时间与时区不匹配" }
    return x.atZoneSameInstant(zone)
}

data class Span(val kind: String, val start: String, val end: String, val zone: String = "") {
    val first: LocalDate
        get() = LocalDate.parse(start.take(10))

    val last: LocalDate
        get() = LocalDate.parse(end.take(10))

    fun json() =
        obj("kind" to kind, "start" to start, "end" to end, "zone" to zone.ifEmpty { null })

    fun on(day: LocalDate): Span? {
        val finish = day.plusDays(ChronoUnit.DAYS.between(first, last))
        if (kind == "date") return Span(kind, day.toString(), finish.toString())
        val z = ZoneId.of(zone)
        fun wall(text: String, d: LocalDate): ZonedDateTime? {
            val base = offset(text, z)
            val local = d.atTime(base.toLocalTime())
            val valid = z.rules.getValidOffsets(local)
            return if (valid.isEmpty()) null else ZonedDateTime.ofLocal(local, z, base.offset)
        }
        val a = wall(start, day) ?: return null
        val b = wall(end, finish) ?: return null
        return if (b.isAfter(a)) Span(kind, stamp(a), stamp(b), zone) else null
    }

    fun daysIn(view: ZoneId): Pair<LocalDate, LocalDate> {
        if (kind == "date") return first to last.minusDays(1)
        return OffsetDateTime.parse(start).atZoneSameInstant(view).toLocalDate() to
            OffsetDateTime.parse(end).toInstant().minusNanos(1).atZone(view).toLocalDate()
    }

    fun label(zone: ZoneId): String =
        if (kind == "date") ""
        else {
            val f = DateTimeFormatter.ofPattern("H:mm")
            OffsetDateTime.parse(start).atZoneSameInstant(zone).format(f) +
                "–" +
                OffsetDateTime.parse(end).atZoneSameInstant(zone).format(f)
        }

    companion object {
        fun read(s: JSONObject): Span {
            val k = s.getString("kind")
            val a = s.getString("start")
            val b = s.getString("end")
            if (k == "date") {
                require(LocalDate.parse(a).isBefore(LocalDate.parse(b)) && !s.has("zone")) {
                    "结束日期须晚于开始日期"
                }
                return Span(k, a, b)
            }
            require(k == "timed")
            val z = ZoneId.of(s.getString("zone"))
            val x = offset(a, z)
            val y = offset(b, z)
            require(y.isAfter(x)) { "结束时间须晚于开始时间" }
            return Span(k, stamp(x), stamp(y), z.id)
        }
    }
}

private class Series(val first: LocalDate, rule: JSONObject) {
    val freq = rule.getString("frequency")
    val step = rule.getInt("interval").toLong()
    val until = rule.optString("until").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }

    init {
        require(
            step in 1..999 &&
                freq in setOf("daily", "weekly", "monthly", "yearly") &&
                (until == null || until >= first)
        )
    }

    fun index(day: LocalDate): Long {
        val diff =
            when (freq) {
                "daily" -> ChronoUnit.DAYS.between(first, day)
                "weekly" -> ChronoUnit.DAYS.between(first, day) / 7
                "monthly" -> ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(day))
                else -> (day.year - first.year).toLong()
            }
        return (diff / step).coerceAtLeast(0)
    }

    fun slot(i: Long): LocalDate {
        val n = i * step
        return when (freq) {
            "daily" -> first.plusDays(n)
            "weekly" -> first.plusWeeks(n)
            "monthly" -> first.plusMonths(n)
            else -> first.plusYears(n)
        }
    }

    fun valid(day: LocalDate) =
        (until == null || day <= until) &&
            (freq !in setOf("monthly", "yearly") || day.dayOfMonth == first.dayOfMonth)
}

fun normalize(raw: JSONObject): JSONObject {
    val e = raw.copyJson()
    require(
        e.keys().asSequence().all {
            it in setOf("title", "span", "color", "participants", "repeat", "exceptions")
        }
    )
    require(
        e.getString("title").isNotBlank() &&
            e.getString("title").codePointCount(0, e.getString("title").length) <= 512
    ) {
        "请填写事件标题（最多 512 字）"
    }
    val span = Span.read(e.getJSONObject("span"))
    e.put("span", span.json())
    if (e.has("color")) {
        val c = e.getString("color")
        require(colorPattern.matches(c))
        e.put("color", c.uppercase())
    }
    if (e.has("participants")) {
        val p = e.getJSONArray("participants").strings()
        require(p.size <= 512 && p.all(idPattern::matches) && p.distinct().size == p.size)
        if (p.isEmpty()) e.remove("participants") else e.put("participants", JSONArray(p.sorted()))
    }
    val series = e.optJSONObject("repeat")?.let { Series(span.first, it) }
    val exceptions = e.array("exceptions").objects().sortedBy { it.getString("on") }
    require(
        exceptions.size <= 366 &&
            exceptions.distinctBy { it.getString("on") }.size == exceptions.size
    )
    for (ex in exceptions) {
        val d = LocalDate.parse(ex.getString("on"))
        require(
            series != null &&
                series.slot(series.index(d)) == d &&
                series.valid(d) &&
                span.on(d) != null
        )
        require(ex.optBoolean("cancel") xor ex.has("move"))
        ex.optJSONObject("move")?.let { ex.put("move", Span.read(it).json()) }
    }
    if (exceptions.isEmpty()) e.remove("exceptions") else e.put("exceptions", JSONArray(exceptions))
    return e
}

data class Slot(
    val cal: String,
    val id: String,
    val on: String,
    val title: String,
    val color: String,
    val span: Span,
    val version: Long,
) {
    fun json() =
        obj(
            "eventId" to id,
            "on" to on,
            "title" to title,
            "color" to color,
            "span" to span.json(),
            "version" to version,
        )
}

fun Store.agenda(
    books: List<Book>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
    participant: String = "",
): List<Slot> {
    val slots = mutableListOf<Slot>()
    visitAgenda(books, from, to, zone, participant) { slot ->
        slots.add(slot)
        require(slots.size <= 10000) { "请缩小日期范围" }
    }
    return slots.sortedWith(
        compareBy(
            { it.span.daysIn(zone).first },
            { it.span.kind },
            {
                if (it.span.kind == "timed") OffsetDateTime.parse(it.span.start).toEpochSecond()
                else 0L
            },
            { it.id },
            { it.on },
        )
    )
}

fun Store.visitAgenda(
    books: List<Book>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
    participant: String = "",
    visit: (Slot) -> Unit,
) {
    require(to.toEpochDay() - from.toEpochDay() in 1..366)
    val colors = books.associate { it.id to it.color }
    for (row in
        rows()
            .window(
                colors.keys.toList(),
                from.minusDays(2).toString(),
                to.plusDays(2).toString(),
            )) {
        val rec = JSONObject(row.json)
        val e = rec.getJSONObject("event")
        if (participant.isNotEmpty() && participant !in e.array("participants").strings()) continue
        val base = Span.read(e.getJSONObject("span"))
        fun append(day: LocalDate, s: Span) {
            val (a, b) = s.daysIn(zone)
            if (a < to && b >= from) {
                visit(
                    Slot(
                        row.cal,
                        row.id,
                        day.toString(),
                        e.getString("title"),
                        e.optString("color", colors.getValue(row.cal)),
                        s,
                        rec.getLong("version"),
                    )
                )
            }
        }
        val repeat = e.optJSONObject("repeat")
        if (repeat == null) {
            append(base.first, base)
            continue
        }
        val series = Series(base.first, repeat)
        val exceptions =
            e.array("exceptions").objects().associateBy { LocalDate.parse(it.getString("on")) }
        for ((day, ex) in exceptions) ex.optJSONObject("move")?.let { append(day, Span.read(it)) }
        var i = series.index(from.minusDays(2 + ChronoUnit.DAYS.between(base.first, base.last)))
        while (true) {
            val d = series.slot(i++)
            if (d > to.plusDays(2) || (series.until != null && d > series.until)) break
            if (series.valid(d) && d !in exceptions) base.on(d)?.let { append(d, it) }
        }
    }
}
