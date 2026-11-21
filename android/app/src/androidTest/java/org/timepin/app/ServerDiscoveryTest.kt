package org.timepin.app

import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner

class ServerDiscoveryTest : InstrumentationTestCase() {
    fun testLegacyReplicaCollision() {
        val ctx = instrumentation.targetContext
        check(ctx.packageName.endsWith(".qa") && Account.read(ctx) == null)
        val args = (instrumentation as InstrumentationTestRunner).arguments
        val db = Store.get(ctx)
        val sql = db.rows()
        val a = Account.register(ctx, args.getString("aliasUrl")!!, "legacy" + uid().take(8), "qa-legacy-test", "")
        val id = a.api("state").getJSONArray("calendars").getJSONObject(0).getString("id")
        val old = Book(id, "旧副本", "#60766A", origin = "http://127.0.0.1:1", user = a.user, role = "none")
        val draft = Pending(id, "{}", "signed_out")
        val seq = sql.enqueue(draft)
        sql.save(old)
        SystemCalendars.markSource(db, old.id)
        fun create(cal: String, eid: String) = obj("op" to "event.create", "v" to 1, "calendarId" to cal,
            "eventId" to eid, "operationId" to uid(), "event" to obj("title" to eid,
                "span" to obj("kind" to "date", "start" to "2026-11-20", "end" to "2026-11-21")))
        try {
            requireOk(a.api("commands", create(id, "from-web")))
            val sync = Sync(ctx, db, a)
            sync.all()
            val replica = db.accountBooks(ctx).single { it.serverId == id }
            assertFalse(id == replica.id)
            assertTrue(sql.entry(replica.id, "from-web")!!.live)
            requireOk(Commands(db).run(replica, create(replica.id, "from-phone")))
            sync.all()
            val result = a.api("commands", obj("op" to "event.get", "v" to 1, "calendarId" to id, "eventId" to "from-phone"))
            assertEquals("ok", result.getString("code"))
            assertEquals(0, sql.queueCount(replica.id))
            assertEquals(replica.id, db.accountBooks(ctx).single { it.serverId == id }.id)
            assertEquals(old, sql.book(id))
            assertEquals("signed_out", sql.head(id)!!.state)
            assertFalse(sql.hasSystemObservations(replica.id))
            assertTrue(sql.hasSystemObservations(id))
        } finally {
            Sync.logout(ctx, db, a)
            sql.dequeue(seq)
            sql.clearObservations(id)
            sql.removeBook(id)
        }
    }

    fun testLogoutThenLoginAtAlias() {
        val ctx = instrumentation.targetContext
        check(ctx.packageName.endsWith(".qa") && Account.read(ctx) == null)
        val args = (instrumentation as InstrumentationTestRunner).arguments
        val home = args.getString("serverUrl")!!
        val alias = args.getString("aliasUrl")!!
        val user = "relogin" + uid().take(8)
        val password = "qa-relogin-test"
        val db = Store.get(ctx)
        val account = Account.register(ctx, home, user, password, "")
        val id = account.api("state").getJSONArray("calendars").getJSONObject(0).getString("id")
        try {
            val event = obj("title" to "班次", "span" to obj("kind" to "date",
                "start" to "2026-11-20", "end" to "2026-11-21"))
            val cmd = obj("op" to "event.create", "operationId" to uid(), "v" to 1,
                "calendarId" to id, "eventId" to "shift", "event" to event)
            requireOk(account.api("commands", cmd))
            Sync(ctx, db, account).all()
            assertTrue(db.rows().entry(id, "shift")!!.live)
            val draft = cmd.copyJson().put("op", "event.replace").put("baseVersion", 1).put("operationId", uid())
            draft.getJSONObject("event").put("title", "待核对草稿")
            requireOk(Commands(db).run(db.rows().book(id)!!, draft))
            Sync.logout(ctx, db, account)
            assertNull(db.rows().entry(id, "shift"))
            val again = Account.login(ctx, alias, user, password)
            assertEquals("ok", again.api("commands", obj("op" to "event.get", "v" to 1,
                "calendarId" to id, "eventId" to "shift")).getString("code"))
            Sync(ctx, db, again).all()
            assertTrue("原日历编号须重新出现在手机", db.accountBooks(ctx).any { it.id == id && it.role != "none" })
            assertTrue("重新登录应下载服务器日程", db.rows().entry(id, "shift")!!.live)
            assertEquals("班次", org.json.JSONObject(db.rows().entry(id, "shift")!!.json)
                .getJSONObject("event").getString("title"))
            assertEquals("signed_out", db.rows().head(id)!!.state)
            assertTrue(db.rows().head(id)!!.command.contains("待核对草稿"))
            val day = java.time.LocalDate.parse("2026-11-20")
            assertEquals(1, db.agenda(db.accountBooks(ctx).filter { it.visible && it.role != "none" },
                day, day.plusDays(1), java.time.ZoneId.of("Asia/Shanghai")).count { it.cal == id })
            Sync(ctx, db, again).all()
            assertEquals(1, db.rows().live(id).size)
        } finally {
            Account.read(ctx)?.let { Sync.logout(ctx, db, it) }
        }
    }

    fun testMemberAddressSwitch() {
        val ctx = instrumentation.targetContext
        check(ctx.packageName.endsWith(".qa") && Account.read(ctx) == null)
        val args = (instrumentation as InstrumentationTestRunner).arguments
        val home = args.getString("serverUrl")!!
        val alias = args.getString("aliasUrl")!!
        val other = args.getString("otherUrl")!!
        val account = Account.register(ctx, home, "alias" + uid().take(8), "qa-address-test", "")
        try {
            Sync(ctx, Store.get(ctx), account).all()
            val books = Store.get(ctx).accountBooks(ctx).map { it.id }
            assertTrue(books.isNotEmpty())
            Account.connect(ctx, alias)
            val moved = Account.read(ctx)!!
            assertEquals(account.origin, moved.origin)
            assertEquals(alias, moved.endpoint)
            assertEquals(account.user, moved.api("state").getJSONObject("user").getString("id"))
            assertEquals(books, Store.get(ctx).accountBooks(ctx).map { it.id })
            assertTrue(runCatching { Account.connect(ctx, other) }.isFailure)
            assertEquals(moved, Account.read(ctx))
            Account.connect(ctx, home)
            assertEquals(account, Account.read(ctx))
            assertEquals("http://public.example", baseUrl("http://public.example", calendarServer = true))
            assertTrue(runCatching { baseUrl("http://public.example") }.isFailure)
        } finally {
            Sync.logout(ctx, Store.get(ctx), Account.read(ctx)!!)
        }
    }

    fun testConnectWithoutAccount() {
        val c = instrumentation.targetContext
        check(c.packageName.endsWith(".qa") && Account.read(c) == null)
        val server = (instrumentation as InstrumentationTestRunner).arguments.getString("serverUrl")
            ?: error("需要独立测试实例")
        val prefs = c.getSharedPreferences("display", 0)
        val old = prefs.getString("last-url", null)
        try {
            val commands = DeviceCommands(c)
            val remote = commands.run("server.connect", obj("url" to "$server/"))
            assertEquals(server, remote.getString("url"))
            assertFalse(remote.getBoolean("signedIn"))
            assertNull(Account.read(c))
            assertTrue(runCatching { Account.connect(c, "$server/invalid") }.isFailure)
            assertEquals(server, Account.selectedServer(c))
        } finally {
            prefs.edit().putString("last-url", old).commit()
        }
    }

    fun testFindSelectedServer() {
        val expected = (instrumentation as InstrumentationTestRunner).arguments.getString("server")
                ?: error("需要传入验收服务器的局域网地址")
        val servers = discoverCalendarServers()
        assertTrue("局域网广播未发现指定服务器", expected in servers)
        assertEquals("timepin/1", request(expected, "/api/v1/instance").body.getString("service"))
    }
}
