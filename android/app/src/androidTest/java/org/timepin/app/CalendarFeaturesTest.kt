package org.timepin.app

import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.test.InstrumentationTestCase
import java.time.*
import java.util.Locale

class CalendarFeaturesTest : InstrumentationTestCase() {
    fun testSystemSourceRoundTrip() = withSource { id ->
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val base = args.getString("serverUrl") ?: return@withSource
        require(base.startsWith("http://127.0.0.1:"))
        val name = "Feed" + uid().take(8)
        val password = "qa-source-password"
        val original = Vault(ctx).get("account")
        request(base, "/api/v1/register", obj("name" to name, "password" to password))
        val db = Store.get(ctx)
        val source = SystemCalendars.list(ctx).single { it.id == id }
        val date = LocalDate.now()
        val time = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val uri = ctx.contentResolver.insert(Events.CONTENT_URI, ContentValues().apply {
            put(Events.CALENDAR_ID, id); put(Events.TITLE, "早班"); put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, time); put(Events.DTEND, time + 3600000)
        })!!
        try {
            Vault(ctx).set("account", null)
            fun scan() = SystemCalendars.refreshLocal(ctx, source, date.minusDays(1), date.plusDays(2))
            val cal = scan().first
            val eid = db.rows().live(cal).single().id
            val account = Account.login(ctx, base, name, password)
            val sync = Sync(ctx, db, account)
            fun remote() = account.api("commands", obj("v" to 1, "op" to "event.get",
                "calendarId" to cal, "eventId" to eid)).getJSONObject("record")
            sync.all(false)
            assertEquals("早班", remote().getJSONObject("event").getString("title"))
            ctx.contentResolver.update(uri, ContentValues().apply { put(Events.TITLE, "中班") }, null, null)
            assertEquals(cal, scan().first)
            sync.all(false)
            val record = remote()
            assertEquals("中班", record.getJSONObject("event").getString("title"))
            account.api("commands", obj("v" to 1, "op" to "event.replace", "calendarId" to cal,
                "eventId" to eid, "operationId" to uid(), "baseVersion" to record.getLong("version"),
                "event" to record.getJSONObject("event").put("title", "Web 改班")))
            sync.all(false)
            assertEquals(1, scan().second["cached"])
            assertEquals("Web 改班", org.json.JSONObject(db.rows().entry(cal,eid)!!.json).getJSONObject("event").getString("title"))
            assertEquals(0, db.rows().queueCount(cal))
        } finally {
            Account.read(ctx)?.let { Sync.logout(ctx, db, it) }
            Vault(ctx).set("account", original)
        }
    }

    private val ctx
        get() = instrumentation.targetContext

    private fun withSource(body: (Long) -> Unit) {
        check(ctx.packageName.endsWith(".qa"))
        val account = "TimePin-QA-" + uid()
        val resolver = ctx.contentResolver
        val uri =
            Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
        val fixture =
            resolver.insert(
                uri,
                ContentValues().apply {
                    put(Calendars.ACCOUNT_NAME, account)
                    put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Calendars.NAME, account)
                    put(Calendars.CALENDAR_DISPLAY_NAME, account)
                    put(Calendars.OWNER_ACCOUNT, account)
                    put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                    put(Calendars.CALENDAR_COLOR, 0xff246655.toInt())
                    put(Calendars.VISIBLE, 1)
                    put(Calendars.SYNC_EVENTS, 1)
                },
            )!!
        val id = ContentUris.parseId(fixture)
        try {
            body(id)
        } finally {
            resolver.delete(ContentUris.withAppendedId(uri, id), null, null)
        }
    }

    fun testSystemImport() = withSource { id ->
        val resolver = ctx.contentResolver
        val from = LocalDate.now().withDayOfYear(1)
        val start = from.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun values(title: String) =
            ContentValues().apply {
                put(Events.CALENDAR_ID, id)
                put(Events.TITLE, title)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DTSTART, start)
                put(Events.ALL_DAY, 1)
            }
        val first =
            resolver.insert(
                Events.CONTENT_URI,
                values("跨日").apply { put(Events.DTEND, start + 2 * 86400000L) },
            )!!
        resolver.insert(
            Events.CONTENT_URI,
            values("重复").apply {
                put(Events.RRULE, "FREQ=WEEKLY;COUNT=3")
                put(Events.DURATION, "P1D")
            },
        )!!
        val source = SystemCalendars.list(ctx).single { it.id == id }
        val until = from.plusYears(1)
        assertEquals(4, SystemCalendars.copy(ctx, source, from, until)["created"])
        assertEquals(4, SystemCalendars.copy(ctx, source, from, until)["unchanged"])
        resolver.update(first, ContentValues().apply { put(Events.TITLE, "新标题") }, null, null)
        val result = SystemCalendars.copy(ctx, source, from, until)
        assertEquals(1, result["updated"])
        assertEquals(3, result["unchanged"])
        val records = Store.get(ctx).rows().live(source.localId)
        assertEquals(4, records.size)
        val span =
            org.json
                .JSONObject(records.single { it.json.contains("新标题") }.json)
                .getJSONObject("event")
                .getJSONObject("span")
        assertEquals(from.plusDays(7).toString(), span.getString("start"))
        assertEquals(from.plusDays(9).toString(), span.getString("end"))
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        repeat(120) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail(description)
    }

    fun testAutomaticSystemImport() {
        check(ctx.packageName.endsWith(".qa"))
        val command = DeviceCommands(ctx)
        val wasEnabled = command.run("system.autoImport.detail", obj()).getBoolean("enabled")
        command.run("system.autoImport.update", obj("enabled" to true))
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val start = instrumentation.uiAutomation.executeShellCommand(
            "am start -n " + ctx.packageName + "/org.timepin.app.MainActivity"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(start).use { it.readBytes() }
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 6000) as? MainActivity
            ?: error("QA 月历未打开")
        val sql = Store.get(ctx).rows()
        try {
            withSource { id ->
                val source = SystemCalendars.list(ctx).single { it.id == id }
                waitFor("自动识别新增的空日历") { sql.book(source.localId) != null }
                val now = System.currentTimeMillis()
                val event = ctx.contentResolver.insert(Events.CONTENT_URI, ContentValues().apply {
                    put(Events.CALENDAR_ID, id)
                    put(Events.TITLE, "自动首班")
                    put(Events.EVENT_TIMEZONE, "UTC")
                    put(Events.DTSTART, now)
                    put(Events.DTEND, now + 3600000L)
                })!!
                fun title() = sql.live(source.localId).singleOrNull()?.let {
                    org.json.JSONObject(it.json).getJSONObject("event").getString("title")
                }
                waitFor("系统通知触发新增") { title() == "自动首班" }
                val from = LocalDate.now().withDayOfYear(1).minusYears(1)
                val before = sql.batches(source.localId).size
                val cache = SystemCalendars.refreshLocal(ctx, source, from, from.plusYears(3)).second
                assertEquals(1, cache["cached"])
                assertEquals(before, sql.batches(source.localId).size)
                command.run("system.autoImport.update", obj("enabled" to false))
                instrumentation.waitForIdleSync()
                ctx.contentResolver.update(event, ContentValues().apply { put(Events.TITLE, "自动晚班") }, null, null)
                Thread.sleep(1000)
                assertEquals("自动首班", title())
                command.run("system.autoImport.update", obj("enabled" to true))
                waitFor("恢复后自动补入系统改动") { title() == "自动晚班" }
                assertEquals(before + 1, sql.batches(source.localId).size)
                assertTrue(sql.queue(source.localId).isEmpty())
                val record = org.json.JSONObject(sql.live(source.localId).single().json)
                requireOk(Commands(Store.get(ctx)).run(sql.book(source.localId)!!, obj("op" to "event.replace",
                    "v" to 1, "operationId" to uid(), "calendarId" to source.localId,
                    "eventId" to record.getString("id"), "baseVersion" to record.getLong("version"),
                    "event" to record.getJSONObject("event").put("title", "手工改班"))))
                val keys = listOf(ContentUris.parseId(event).toString())
                val prior = sql.observations(source.localId, keys).single().fingerprint
                ctx.contentResolver.update(event, ContentValues().apply { put(Events.TITLE, "来源再改") }, null, null)
                waitFor("已核对来源改动") {
                    !sql.observations(source.localId, keys).single().fingerprint.contentEquals(prior)
                }
                assertEquals("手工改班", title())
                command.run("calendar.delete", obj("calendarId" to source.localId, "operationId" to uid()))
                SystemCalendars.refreshLocal(ctx, source, from, from.plusYears(3)).second
                assertNull(sql.book(source.localId))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            command.run("system.autoImport.update", obj("enabled" to wasEnabled))
        }
    }

    fun testAutomaticImportWork() = withSource { id ->
        val source = SystemCalendars.list(ctx).single { it.id == id }
        val db = Store.get(ctx)
        val sql = db.rows()
        val original = Book(source.localId, "共享班表", "#447755", origin = "https://example.invalid", user = "qa", role = "none")
        sql.save(Receipt(original.id, "delete:qa-instance", uid(), "calendar.delete", "[]", "{}"))
        val from = LocalDate.now().withDayOfYear(1)
        val until = from.plusYears(1)
        val events = (0 until 30).map { index ->
            val time = from.plusDays(index.toLong()).atTime(9, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
            ctx.contentResolver.insert(Events.CONTENT_URI, ContentValues().apply {
                put(Events.CALENDAR_ID, id)
                put(Events.TITLE, "固定班次 " + index)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DTSTART, time)
                put(Events.DTEND, time + 3600000)
            })!!
        }
        fun scan(label: String): Pair<String, Map<String, Int>> {
            val start = android.os.SystemClock.elapsedRealtime()
            val result = SystemCalendars.refreshLocal(ctx, source, from, until)
            android.util.Log.i("TimePinImportQA", label + " ms=" + (android.os.SystemClock.elapsedRealtime()-start) + " " + result.second)
            return result
        }
        assertTrue(scan("retired").second.isEmpty())
        val legacyCopy = "system-" + java.util.UUID.nameUUIDFromBytes(("local-copy:" + original.id).toByteArray())
        sql.save(Book(legacyCopy, "既有本机副本", "#447755"))
        val (local, first) = scan("first")
        assertFalse(local == original.id)
        assertNull(sql.book(original.id))
        assertEquals("", sql.book(local)!!.origin)
        assertEquals(30, first["created"])
        sql.save(original)
        assertEquals(30, scan("repeat").second["cached"])
        assertEquals(1, sql.batches(local).size)
        ctx.contentResolver.update(events[5], ContentValues().apply { put(Events.TITLE, "修订班次") }, null, null)
        val (again, updated) = scan("one-edit")
        assertEquals(local, again)
        assertEquals(1, updated["updated"])
        assertEquals(29, updated["cached"])
        assertEquals(2, sql.batches(local).size)
        assertEquals(original, sql.book(original.id))
        assertTrue(sql.queue(original.id).isEmpty() && sql.queue(local).isEmpty())
        requireOk(Commands(db).run(sql.book(local)!!, obj("v" to 1, "op" to "import.undo", "calendarId" to local,
            "operationId" to uid(), "batchId" to sql.batches(local).first().id)))
        assertEquals(30, scan("after-undo").second["cached"])
        assertFalse(sql.live(local).any { it.json.contains("修订班次") })
    }

    fun testSystemImportMigration() {
        val name = "qa-import-migration-" + uid()
        val book = Book("system-upgrade", "升级日历", "#118844")
        fun open() = androidx.room.Room.databaseBuilder(ctx, Store::class.java, name)
            .addMigrations(Store.systemImportMigration, Store.replicaMigration).build()
        try {
            val db = open()
            try {
                db.rows().save(book)
                requireOk(Commands(db).run(book, obj("v" to 1,"op" to "event.create","operationId" to uid(),
                    "calendarId" to book.id,"eventId" to "shift","event" to obj("title" to "旧班次",
                    "span" to obj("kind" to "date","start" to "2026-11-20","end" to "2026-11-21")))))
            } finally { db.close() }
            android.database.sqlite.SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, 0).use {
                it.execSQL("DROP TABLE system_observations")
                it.execSQL("ALTER TABLE books RENAME TO books_new")
                it.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, visible INTEGER NOT NULL, origin TEXT NOT NULL, user TEXT NOT NULL, role TEXT NOT NULL, cursor TEXT NOT NULL, publication TEXT NOT NULL)")
                it.execSQL("INSERT INTO books SELECT id,name,color,visible,origin,user,role,cursor,publication FROM books_new")
                it.execSQL("DROP TABLE books_new")
                it.version = 1
            }
            val upgraded = open()
            try {
                assertEquals(book,upgraded.rows().book(book.id))
                assertNotNull(upgraded.rows().entry(book.id,"shift"))
                assertTrue(upgraded.rows().observations(book.id,listOf("shift")).isEmpty())
                assertEquals(3,upgraded.openHelper.readableDatabase.version)
            } finally { upgraded.close() }
        } finally { ctx.deleteDatabase(name) }
    }

    fun testRegionalDates() {
        val opts = MonthOptions()
        assertEquals("system", MonthOptions.read(ctx).holidayRegion)
        assertEquals("春节", DateNotes.holidays(ctx, "CN").getString("2027-02-05"))
        assertEquals("调休上班", DateNotes.holidays(ctx, "CN").getString("2027-02-14"))
        assertTrue(DateNotes.holidays(ctx, "US").has("2026-07-04"))
        assertEquals("9/23", DateNotes.lunar(LocalDate.of(2026, 11, 1), "numeric"))
        assertEquals("10/1", DateNotes.lunar(LocalDate.of(2026, 11, 9), "numeric"))
        assertEquals("闰6/1", DateNotes.lunar(LocalDate.of(2025, 7, 25), "numeric"))
        assertEquals("正月", DateNotes.lunar(LocalDate.of(2026, 2, 17)))
        assertEquals("闰六月", DateNotes.lunar(LocalDate.of(2025, 7, 25)))
        assertEquals(1, opts.week(LocalDate.of(2025, 12, 28)))
        assertEquals(1, opts.copy(weekStart = "monday").week(LocalDate.of(2025, 12, 29)))
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(Locale.US)
        assertEquals("US", opts.region(ctx.createConfigurationContext(config)))
        config.setLocale(Locale.CHINA)
        assertEquals("CN", opts.region(ctx.createConfigurationContext(config)))
    }

    fun testReminderDelivery() {
        val db = Store.get(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val included =
            Book(
                "mobile-" + uid(),
                "项目例会 " +
                    LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                "#248365",
                false,
            )
        val excluded = included.copy(id = "mobile-" + uid(), name = "静音验收")
        val start =
            ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(1).plusSeconds(8).withNano(0)
        Reminders.configure(ctx, on = false)
        val skip = db.accountBooks(ctx).map { it.id }.toSet() + excluded.id - included.id
        try {
            for (book in listOf(included, excluded)) {
                db.rows().save(book)
                requireOk(
                    Commands(db)
                        .run(
                            book,
                            obj(
                                "v" to 1,
                                "op" to "event.create",
                                "operationId" to uid(),
                                "eventId" to uid(),
                                "calendarId" to book.id,
                                "event" to
                                    obj(
                                        "title" to book.name,
                                        "span" to
                                            Span(
                                                    "timed",
                                                    stamp(start),
                                                    stamp(start.plusHours(1)),
                                                    start.zone.id,
                                                )
                                                .json(),
                                    ),
                            ),
                        )
                )
            }
            Reminders.configure(ctx, on = true, advance = 1, excluded = skip)
            val deadline = System.currentTimeMillis() + 19000
            while (
                manager.activeNotifications.none {
                    it.notification.extras.getString("android.title") == included.name
                } && System.currentTimeMillis() < deadline
            ) Thread.sleep(200)
            assertTrue(
                "没有收到提前一分钟的系统通知",
                manager.activeNotifications.any {
                    it.notification.extras.getString("android.title") == included.name
                },
            )
            assertFalse(
                manager.activeNotifications.any {
                    it.notification.extras.getString("android.title") == excluded.name
                }
            )
            assertTrue(System.currentTimeMillis() < start.toInstant().toEpochMilli())
        } finally {
            Reminders.configure(ctx, on = false)
            for (book in listOf(included, excluded)) {
                DeviceCommands(ctx).run("calendar.delete", obj("calendarId" to book.id, "operationId" to uid()))
            }
        }
        val cancelDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (
            manager.activeNotifications.isNotEmpty() &&
                android.os.SystemClock.elapsedRealtime() < cancelDeadline
        ) Thread.sleep(100)
        assertTrue("关闭提醒后仍有日程通知", manager.activeNotifications.isEmpty())
    }
}
