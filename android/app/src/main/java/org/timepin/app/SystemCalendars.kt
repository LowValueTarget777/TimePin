package org.timepin.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import org.json.JSONArray

internal data class PhoneCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val type: String,
    val color: String,
    val localId: String,
)

internal object SystemCalendars {
    private fun sourceId(value: String) = "system-" + UUID.nameUUIDFromBytes(value.toByteArray())

    private fun existingCopy(rows: Rows, original: String): String {
        var id = original
        while (true) {
            val next = sourceId("local-copy:" + id)
            if (rows.book(next) == null && !rows.wasDeleted(next)) return id
            id = next
        }
    }

    private fun importId(rows: Rows, device: String, key: String): String {
        val legacy = sourceId(key)
        val copy = existingCopy(rows, legacy)
        if (copy != legacy) return copy
        val old = rows.book(legacy)
        val known = old != null && (old.origin.isEmpty() || old.role == "none" || rows.hasSystemObservations(legacy))
        return if (known || rows.wasDeleted(legacy)) legacy else sourceId(device + "\u0000" + key)
    }

    fun accessAllowed(c: Context): Boolean {
        val ops = c.getSystemService(android.app.AppOpsManager::class.java)
        val mode =
            ops.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_READ_CALENDAR,
                android.os.Process.myUid(),
                c.packageName,
            )
        return mode != android.app.AppOpsManager.MODE_IGNORED && mode != android.app.AppOpsManager.MODE_ERRORED
    }

    fun list(c: Context): List<PhoneCalendar> {
        require(accessAllowed(c)) {
            "系统已限制读取日历，请在手机应用权限设置中允许 TimePin 读取日历"
        }
        val fields =
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_COLOR,
            )
        val sql = Store.get(c).rows()
        val device = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        return c.contentResolver
            .query(
                Calendars.CONTENT_URI,
                fields,
                Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                arrayOf(Calendars.CAL_ACCESS_READ.toString()),
                null,
            )
            ?.use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val key = rows.getString(3).orEmpty() + "\u0000" + rows.getString(2).orEmpty() + "\u0000" + rows.getLong(0)
                        add(
                            PhoneCalendar(
                                rows.getLong(0),
                                rows.getString(1).orEmpty().ifBlank { "系统日历" },
                                rows.getString(2).orEmpty(),
                                rows.getString(3).orEmpty(),
                                "#%06X".format(rows.getInt(4) and 0xffffff),
                                importId(sql, device, key),
                            )
                        )
                    }
                }
            } ?: error("系统日历暂时不可用")
    }

    // Provider event keys are numeric, optionally followed by an occurrence timestamp.
    private const val SOURCE_MARKER = "local-provider"
    fun markSource(db: Store, id: String) {
        db.rows().observe(listOf(SystemObservation(id, SOURCE_MARKER, byteArrayOf(1))))
    }
    fun refreshLocal(c: Context, source: PhoneCalendar, from: LocalDate, until: LocalDate): Pair<String, Map<String, Int>> {
        val db = Store.get(c)
        return db.atomic {
            val rows = db.rows()
            val id = existingCopy(rows, source.localId)
            val current = rows.book(id)
            if (current != null && current.origin.isNotEmpty()) {
                val account = Account.read(c)
                if (current.origin != account?.origin || current.user != account.user ||
                    current.role !in setOf("manager", "editor", "contributor") ||
                    !rows.hasSystemObservations(id)) return@atomic id to emptyMap()
            }
            val counts = if (rows.book(id) == null && rows.wasDeleted(id)) emptyMap()
                else copyTo(c, source, from, until, updateShared = true, automaticTarget = id)
            id to counts
        }
    }

    fun copy(
        c: Context,
        source: PhoneCalendar,
        from: LocalDate,
        until: LocalDate,
        updateShared: Boolean = false,
    ): Map<String, Int> = copyTo(c, source, from, until, updateShared, null)

    private fun copyTo(
        c: Context, source: PhoneCalendar, from: LocalDate, until: LocalDate,
        updateShared: Boolean, automaticTarget: String?,
    ): Map<String, Int> {
        require(until > from && from.year >= 1 && until <= LocalDate.of(10000, 1, 1)) {
            "请输入有效日期范围，截止日期须晚于开始日期"
        }
        val zone = ZoneId.systemDefault()
        val db = Store.get(c)
        val summary = mutableMapOf<String, Int>()
        val resolver = c.contentResolver
        val cols =
            arrayOf(
                Instances.EVENT_ID,
                Instances.TITLE,
                Instances.BEGIN,
                Instances.END,
                Instances.ALL_DAY,
                Instances.EVENT_TIMEZONE,
                Instances.RRULE,
                Instances.RDATE,
                Instances.ORIGINAL_ID,
                Instances.ORIGINAL_INSTANCE_TIME,
                Instances.DISPLAY_COLOR,
            )
        val automatic = automaticTarget != null
        val stableSource = "system-calendar"
        val seen = mutableSetOf<String>()
        db.atomic {
            val target = automaticTarget ?: source.localId
            val displayName = if (target == source.localId) source.name.take(80) else source.name.take(76) + "（本机）"
            val book = db.rows().book(target) ?: Book(target, displayName, source.color).also {
                db.rows().save(it)
                if (automatic) summary["newCalendar"] = 1
            }
            require(automatic || book.publication.isEmpty()) { "请在日历发布完成后重新导入" }
            if (book.origin.isNotEmpty())
                require(
                    updateShared &&
                        db.accountBooks(c).any {
                            it.id == book.id && it.role in setOf("editor", "manager", "contributor")
                        }
                ) {
                    "更新已发布的日历需要确认共享目标及编辑权限"
                }
            val batch = JSONArray()
            val digest = if (automatic) MessageDigest.getInstance("SHA-256") else null
            fun flush() {
                if (batch.length() == 0) return
                val rows = obj("source" to stableSource, "rows" to batch)
                val command = obj("v" to 1, "op" to "import.commit", "calendarId" to book.id,
                    "operationId" to uid(), "batch" to rows)
                val commands = Commands(db)
                if (automatic) {
                    val items = batch.objects()
                    val previous = db.rows().observations(book.id, items.map { it.getString("externalId") })
                        .associateBy { it.externalId }
                    val observed = mutableListOf<SystemObservation>()
                    val candidates = JSONArray()
                    for (item in items) {
                        val id = item.getString("externalId")
                        val signature = digest!!.digest(item.getJSONObject("event").toString().toByteArray())
                        if (previous[id]?.fingerprint?.contentEquals(signature) == true) {
                            summary["cached"] = (summary["cached"] ?: 0) + 1
                        } else {
                            observed.add(SystemObservation(book.id, id, signature))
                            candidates.put(item)
                        }
                    }
                    if (observed.isEmpty()) {
                        while (batch.length() > 0) batch.remove(batch.length() - 1)
                        return
                    }
                    // Observations commit with their import. A later local edit or undo
                    // stays intact until the corresponding phone event actually changes.
                    db.rows().observe(observed)
                    rows.put("rows", candidates)
                    command.put("op", "import.preview")
                    val preview = requireOk(commands.run(book, command))
                    val changes = JSONArray()
                    for (row in preview.getJSONArray("rows").objects()) {
                        val outcome = row.getString("outcome")
                        if (outcome == "updated" || (outcome == "created" && row.getLong("baseVersion") == 0L))
                            changes.put(candidates.getJSONObject(row.getInt("index")))
                        else if (outcome == "created") summary["conflict"] = (summary["conflict"] ?: 0) + 1
                        else summary[outcome] = 1 + (summary[outcome] ?: 0)
                    }
                    rows.put("rows", changes)
                    command.put("op", "import.commit")
                }
                if (rows.getJSONArray("rows").length() > 0) {
                    val reply = requireOk(commands.run(book, command))
                    for (row in reply.getJSONArray("rows").objects()) {
                        val outcome = row.getString("outcome")
                        summary[outcome] = 1 + (summary[outcome] ?: 0)
                    }
                }
                while (batch.length() > 0) batch.remove(batch.length() - 1)
            }
            var first = from
            while (first < until) {
                val last = minOf(until, first.plusMonths(3))
                val uri = Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(
                    uri,
                    first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                ContentUris.appendId(
                    uri,
                    last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                val cursor =
                    resolver.query(
                        uri.build(),
                        cols,
                        Instances.CALENDAR_ID +
                            "=? AND (" +
                            Instances.STATUS +
                            " IS NULL OR " +
                            Instances.STATUS +
                            "!=?)",
                        arrayOf(source.id.toString(), Instances.STATUS_CANCELED.toString()),
                        null,
                    ) ?: error("无法读取系统日程")
                cursor.use { rows ->
                    while (rows.moveToNext()) {
                        val begin = Instant.ofEpochMilli(rows.getLong(2))
                        val end = Instant.ofEpochMilli(rows.getLong(3))
                        val allDay = rows.getInt(4) != 0
                        val viewZone = if (allDay) ZoneOffset.UTC else zone
                        val startDay = begin.atZone(viewZone).toLocalDate()
                        if (
                            startDay >= last ||
                                maxOf(end, begin.plusSeconds(1)) <=
                                    first.atStartOfDay(viewZone).toInstant()
                        )
                            continue
                        val original = if (rows.isNull(8)) rows.getLong(0) else rows.getLong(8)
                        val recurrent =
                            !rows.getString(6).isNullOrEmpty() ||
                                !rows.getString(7).isNullOrEmpty() ||
                                !rows.isNull(9)
                        val key =
                            if (recurrent)
                                original.toString() +
                                    "-" +
                                    (if (rows.isNull(9)) rows.getLong(2) else rows.getLong(9))
                            else original.toString()
                        if (!seen.add(key)) continue
                        val span =
                            if (allDay)
                                Span(
                                    "date",
                                    startDay.toString(),
                                    maxOf(startDay.plusDays(1), end.atZone(viewZone).toLocalDate())
                                        .toString(),
                                )
                            else {
                                val eventZone =
                                    runCatching { ZoneId.of(rows.getString(5)) }.getOrDefault(zone)
                                val finish = if (end > begin) end else begin.plusSeconds(60)
                                Span(
                                    "timed",
                                    stamp(begin.atZone(eventZone)),
                                    stamp(finish.atZone(eventZone)),
                                    eventZone.id,
                                )
                            }
                        val event =
                            obj(
                                "title" to
                                    rows.getString(1).orEmpty().ifBlank { "无标题日程" }.take(512),
                                "span" to span.json(),
                            )
                        if (!rows.isNull(10))
                            event.put("color", "#%06X".format(rows.getInt(10) and 0xffffff))
                        batch.put(obj("externalId" to key, "event" to event))
                        if (batch.length() >= 250) flush()
                    }
                }
                first = last
            }
            flush()
        }
        return summary
    }
}

fun MainActivity.detectCalendars() {
    if (
        checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
    ) {
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 41)
        return
    }
    work(fn = { SystemCalendars.list(this) }) { sources ->
        if (sources.isEmpty()) {
            message("手机上未发现可读取的系统日历")
            return@work
        }
        val checked = BooleanArray(sources.size)
        calendarDialog()
            .setTitle("选择系统日历")
            .setMultiChoiceItems(
                sources.map { it.name + " · " + it.account }.toTypedArray(),
                checked,
            ) { _, i, on ->
                checked[i] = on
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ ->
                val chosen = sources.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty()) {
                    message("请先选择要导入的日历")
                    return@setPositiveButton
                }
                val year = LocalDate.now().year
                val box = padded()
                val first = field("开始年份", (year - 1).toString())
                val last = field("结束年份", (year + 1).toString())
                val shared =
                    books.filter {
                        it.origin.isNotEmpty() && chosen.any { source -> source.localId == it.id }
                    }
                box.addView(text("重复日程按所选年份展开。首次导入会创建本机日历。"))
                if (shared.isNotEmpty())
                    box.addView(text("以下日历的变更会同步到实例：" + shared.joinToString("、") { it.name }))
                box.addView(first)
                box.addView(last)
                form("导入日历", box, "导入") { d ->
                    val start = LocalDate.of(first.text.toString().toInt(), 1, 1)
                    val end = LocalDate.of(last.text.toString().toInt(), 12, 31).plusDays(1)
                    require(end > start && start.year >= 1 && end.year <= 10000) { "请输入有效年份范围" }
                    d.dismiss()
                    work(
                        fn = {
                            val totals = mutableMapOf<String, Int>()
                            val changed = mutableSetOf<String>()
                            val failures = mutableListOf<String>()
                            for (source in chosen) {
                                runCatching {
                                        SystemCalendars.copy(
                                            this,
                                            source,
                                            start,
                                            end,
                                            shared.any { it.id == source.localId },
                                        )
                                    }
                                    .onSuccess { counts ->
                                        for ((k, v) in counts) totals[k] = (totals[k] ?: 0) + v
                                        changed.add(source.localId)
                                    }
                                    .onFailure {
                                        failures.add(source.name + "：" + it.localizedMessage)
                                    }
                            }
                            if (changed.isNotEmpty()) Widgets.refresh(this, changed)
                            Triple(totals, changed, failures)
                        }
                    ) { (counts, changed, failures) ->
                        changed.firstOrNull()?.let { active = it }
                        if (failures.isNotEmpty())
                            calendarDialog()
                                .setTitle("部分日历未导入")
                                .setMessage(failures.joinToString("\n"))
                                .setPositiveButton("好", null)
                                .show()
                        if (shared.any { it.id in changed }) autoSync()
                        message(
                            "新增 ${counts["created"]?:0} · 更新 ${counts["updated"]?:0} · 保留 ${counts["unchanged"]?:0} · 冲突 ${counts["conflict"]?:0}" +
                                (counts["forbidden"]?.let { " · 无权限 $it" } ?: "")
                        )
                        reload()
                    }
                }
            }
            .show()
    }
}
