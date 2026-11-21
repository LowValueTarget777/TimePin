package org.timepin.app

import android.appwidget.*
import android.content.*
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.room.Room
import java.time.*
import java.util.concurrent.atomic.AtomicReference
import org.json.*

class DeviceTest : InstrumentationTestCase() {
    private val ctx
        get() = instrumentation.targetContext

    private val db
        get() = Store.get(ctx)

    private val qa
        get() = ctx.getSharedPreferences("phase5-tests", 0)

    private val args
        get() = (instrumentation as InstrumentationTestRunner).arguments

    private val primary
        get() = qa.getString("primary", null) ?: error("run offline acceptance first")

    private val secondary
        get() = qa.getString("secondary", null)!!

    private fun today() = LocalDate.now().toString()

    fun testCalendarSettingsTarget() {
        check(ctx.packageName.endsWith(".qa"))
        val books = listOf(Book("mobile-"+uid(),"默认日历","#278B77"), Book("mobile-"+uid(),"另一本","#476BA6"))
        val commands = DeviceCommands(ctx)
        try {
            books.forEach { db.rows().save(it) }
            val other = books.last()
            val edit = obj("calendarId" to other.id,"operationId" to uid(),"name" to "夜班","color" to "#884466")
            commands.run("calendar.update",edit)
            val visibility = obj("calendarId" to other.id,"operationId" to uid(),"enabled" to false)
            commands.run("calendar.visibility",visibility)
            commands.run("calendar.visibility",obj("calendarId" to other.id,"operationId" to uid(),"enabled" to true))
            commands.run("calendar.visibility",visibility)
            assertTrue(db.rows().book(other.id)!!.visible)
            assertEquals("夜班",db.rows().book(other.id)!!.name)
            assertEquals("#884466",db.rows().book(other.id)!!.color)
            commands.run("calendar.delete",obj("calendarId" to other.id,"operationId" to uid()))
            assertNull(db.rows().book(other.id))
            assertEquals(books.first(),db.rows().book(books.first().id))
        } finally {
            books.filter { db.rows().book(it.id)!=null }.forEach {
                commands.run("calendar.delete",obj("calendarId" to it.id,"operationId" to uid()))
            }
        }
    }

    private fun timed(title: String, hour: Int = 9) =
        obj(
            "title" to title,
            "span" to
                obj(
                    "kind" to "timed",
                    "zone" to "Asia/Shanghai",
                    "start" to today() + "T%02d:00:00+08:00".format(hour),
                    "end" to today() + "T%02d:00:00+08:00".format(hour + 6),
                ),
        )

    private fun subset(w: Any, a: Any, path: String) {
        when (w) {
            is JSONObject -> {
                assertTrue(path, a is JSONObject)
                for (k in w.keys()) {
                    assertTrue(path + "." + k, (a as JSONObject).has(k))
                    subset(w.get(k), a.get(k), path + "." + k)
                }
            }
            is JSONArray -> {
                assertTrue(path, a is JSONArray)
                assertEquals(path, w.length(), (a as JSONArray).length())
                for (i in 0 until w.length()) subset(w.get(i), a.get(i), path + "[" + i + "]")
            }
            else -> assertEquals(path, w.toString(), a.toString())
        }
    }

    fun testConnectedAutoSync() {
        check(ctx.packageName.endsWith(".qa"))
        val base = args.getString("serverUrl")!!
        val gate = args.getString("gateUrl")
        require(base.startsWith("http://127.0.0.1:") && (gate == null || gate.startsWith("http://127.0.0.1:")))
        val oldAccount = Vault(ctx).get("account")
        val user = "Sync" + uid().take(8)
        val password = "qa-sync-test-password"
        val other = request(base, "/api/v1/register", obj("name" to "Owner" + uid().take(8), "password" to password))
        val owner = Account(base, other.body.getString("id"), other.cookie)
        val mine = request(base, "/api/v1/register", obj("name" to user, "password" to password))
        val web = Account(base, mine.body.getString("id"), mine.cookie)
        val local = Book("mobile-" + uid(), "QA 离线日历", "#226688")
        val phoneBook = Book("mobile-" + uid(), "QA 账号日历", "#338855")
        val own = web.api("state").getJSONArray("calendars").getJSONObject(0).getString("id")
        val shared = owner.api("state").getJSONArray("calendars").getJSONObject(0).getString("id")
        val scheduler = ctx.getSystemService(android.app.job.JobScheduler::class.java)
        var activity: MainActivity? = null
        var account: Account? = null
        fun create(cal: String, id: String) = obj("v" to 1, "op" to "event.create", "calendarId" to cal,
            "eventId" to id, "operationId" to uid(), "event" to timed(id))
        fun grant(role: String) = owner.api("manage", obj("op" to "calendar.grant", "target" to shared,
            "subjectKind" to "user", "subject" to user, "role" to role, "operationId" to uid()))
        try {
            Vault(ctx).set("account", null)
            db.rows().save(local)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            val screen = try {
                startFromHost("MainActivity", "-f 0x10008000")
                instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
                    ?: error("需要解锁 QA 真机，打开日历前台")
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            activity = screen
            main { screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            web.api("commands", create(own, "remote-initial"))
            account = Account.login(ctx, base, user, password)
            waitFor("登录后自动接收服务器日历", 12_000) { db.rows().entry(own, "remote-initial")?.live == true }
            assertTrue(scheduler.getPendingJob(501)?.isPersisted == true)
            if (gate != null) {
                val before = request(gate, "/stats").body
                assertFalse(Sync(ctx, db, account).all(false))
                val after = request(gate, "/stats").body
                assertTrue(after.getInt("checks") > before.getInt("checks"))
                assertEquals(before.getInt("pulls"), after.getInt("pulls"))
                assertEquals(before.getInt("directories"), after.getInt("directories"))
                val bytes = (after.getInt("revisionBytes") - before.getInt("revisionBytes")) /
                    (after.getInt("checks") - before.getInt("checks"))
                assertTrue(bytes in 1..128)
                android.util.Log.i("TimePinSyncQA", "idle summary bytes=$bytes, event pulls=0")
            }
            Sync(ctx, db, account).createCalendar(phoneBook)
            val readEvent = obj("v" to 1, "op" to "event.get", "calendarId" to phoneBook.id, "eventId" to "phone-event")
            requireOk(Commands(db).run(db.rows().book(phoneBook.id)!!, create(phoneBook.id, "phone-event")))
            main { screen.autoSync() }
            waitFor("手机新建的账号日历和事件上传") {
                db.rows().book(phoneBook.id)?.publication == "" && db.rows().queueCount(phoneBook.id) == 0
            }
            assertEquals("phone-event", web.api("commands", readEvent).getJSONObject("record").getJSONObject("event").getString("title"))
            val edit = create(phoneBook.id, "phone-event").put("op", "event.replace").put("baseVersion", 1)
            requireOk(web.api("commands", edit.put("event", timed("web-edit", 12))))
            grant("reader")
            owner.api("commands", create(shared, "new-share"))
            web.api("manage", obj("op" to "calendar.visible", "target" to shared, "enabled" to false, "operationId" to uid()))
            waitFor("停留前台时收到新授权", 42_000) { db.rows().entry(shared, "new-share")?.live == true }
            assertEquals("reader", db.rows().book(shared)?.role)
            waitFor("Web 改班回到手机") {
                db.rows().entry(phoneBook.id, "phone-event")?.let { JSONObject(it.json).getJSONObject("event").getString("title") } == "web-edit"
            }
            requireOk(Commands(db).run(db.rows().book(phoneBook.id)!!, readEvent.copyJson()
                .put("op", "event.delete").put("baseVersion", 2).put("operationId", uid())))
            main { screen.autoSync() }
            waitFor("手机删除回到 Web") { db.rows().queueCount(phoneBook.id) == 0 }
            assertTrue(web.api("commands", readEvent).getJSONObject("record").getBoolean("deleted"))
            owner.api("commands", create(shared, "web-hidden"))
            waitFor("Web 隐藏日历也能自动接收事件", 42_000) { db.rows().entry(shared, "web-hidden")?.live == true }
            grant("remove")
            gate?.let { request(it, "/down", obj()) }
            requireOk(Commands(db).run(db.rows().book(own)!!, create(own, "from-phone")))
            main { screen.autoSync() }
            if (gate != null) {
                waitFor("NAS 不可达时登记一次联网重试") { scheduler.getPendingJob(502) != null }
                assertEquals(1, db.rows().queueCount(own))
                main { screen.finish() }
                instrumentation.waitForIdleSync()
                request(gate, "/up", obj())
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                    .executeShellCommand("cmd jobscheduler run -f " + ctx.packageName + " 502")).use { it.readBytes() }
            }
            waitFor("自动提交发件队列并接收撤权", 42_000) {
                db.rows().queueCount(own) == 0 && db.rows().book(shared)?.role == "none"
            }
            assertNull(db.rows().entry(shared, "new-share"))
            val uploaded = web.api("commands", obj("v" to 1, "op" to "event.get", "calendarId" to own, "eventId" to "from-phone"))
            assertEquals("from-phone", uploaded.getJSONObject("record").getJSONObject("event").getString("title"))
            assertEquals(base, db.rows().book(local.id)!!.origin)
            assertTrue(web.api("state").getJSONArray("calendars").objects().any { it.getString("id") == local.id })
            Sync.logout(ctx, db, account)
            assertNull(scheduler.getPendingJob(501))
            assertNull(scheduler.getPendingJob(502))
        } finally {
            gate?.let { request(it, "/up", obj()) }
            activity?.let { main { it.finish() } }
            if (account != null && Account.read(ctx) == account) Sync.logout(ctx, db, account)
            Vault(ctx).set("account", oldAccount)
            if (oldAccount != null) Sync.schedule(ctx)
            for (id in listOf(own, shared, local.id, phoneBook.id)) db.rows().removeBook(id)
        }
    }

    fun testAccountAndDeviceActions() {
        check(ctx.packageName.endsWith(".qa"))
        val base = args.getString("serverUrl")!!
        val model = args.getString("modelUrl")!!
        require(base.startsWith("http://127.0.0.1:") && model.startsWith("http://127.0.0.1:"))
        val pwd = "qa-stream-only-credentials"
        val name = "Actions" + uid().take(8)
        val vault = Vault(ctx)
        val previousAccount = vault.get("account")
        val previousModel = vault.get("model")
        val host = AppWidgetHost(ctx, 8034)
        val book = Book("mobile-" + uid(),"QA 本机排班","#118844")
        val other = Book("mobile-" + uid(),"QA 私人","#335577")
        try {
            request(base,"/api/v1/register",obj("name" to name,"password" to pwd))
            vault.set("account",null)
            val a = Account.login(ctx,base,name,pwd)
            vault.set("model",obj("name" to "fixture","url" to model))
            val console=request(base,"/api/v1/admin/login",obj("name" to "tester","password" to pwd)).cookie
            fun grant(on: Boolean) {
                request(base,"/api/v1/admin/manage",obj("op" to "model.grant","subject" to name,"enabled" to on,"operationId" to uid()),cookie=console)
            }
            grant(true)
            db.rows().save(book); db.rows().save(other)
            val ids=setOf(book.id,other.id)
            val assistant=ActionAssistant(ctx,db)
            fun plan(op:String, fields:JSONObject, personal:Boolean=false, scope:String="device"):Turn {
                return assistant.chat(scope,if(scope=="device") ids else emptySet(),"操作验收 "+fields.put("op",op),
                    JSONArray(),personal,ChatStream(),uid())
            }
            val group=plan("group.create",obj("name" to "排班组"),scope="account")
            grant(false)
            assertTrue(runCatching { assistant.confirm(group) }.isFailure)
            grant(true)
            assistant.confirm(group); assistant.confirm(group)
            val team=a.api("state").array("groups").objects().single().getString("id")
            assistant.confirm(plan("calendar.create",obj("name" to "团队排班","team" to team,"color" to "#336655"),true,"account"))
            assertTrue(a.api("state").array("calendars").objects().any { it.getString("name")=="团队排班" })
            val inviter = request(base,"/api/v1/register",obj("name" to "Invite"+uid().take(8),"password" to pwd))
            val invited = request(base,"/api/v1/state",cookie=inviter.cookie).body.array("calendars").objects().single().getString("id")
            val invite = request(base,"/api/v1/manage",obj("op" to "calendar.invite","target" to invited,"role" to "reader","operationId" to uid()),cookie=inviter.cookie).body
            val join = assistant.chat("account",emptySet(),"接受测试邀请 "+base+"/#invite="+invite.getString("token"),
                JSONArray(),true,ChatStream(),uid())
            assistant.confirm(join); assistant.confirm(join)
            assertTrue(a.api("state").array("calendars").objects().any { it.getString("id")==invited && it.getString("role")=="reader" })

            val month=bind(host,true)
            val agenda=bind(host,false)
            for ((b,label) in listOf(book to "休息",other to "家庭专属")) {
                val marker=obj("title" to label,"span" to obj("kind" to "date","start" to today(),"end" to LocalDate.now().plusDays(1).toString()))
                requireOk(Commands(db).run(b,obj("v" to 1,"op" to "event.create","calendarId" to b.id,"eventId" to "marker",
                    "event" to marker,"operationId" to uid()),a))
            }
            Widgets.save(ctx,month,ids); Widgets.save(ctx,agenda,ids)
            assistant.confirm(plan("widgets.update",obj("widgetId" to month,"calendarIds" to JSONArray(listOf(book.id)))))
            assistant.confirm(plan("widgets.update",obj("widgetId" to agenda,"calendarIds" to JSONArray(listOf(other.id))),true))
            assertEquals(setOf(book.id),Widgets.selected(ctx,month))
            assertEquals(setOf(other.id),Widgets.selected(ctx,agenda))
            val create=obj("v" to 1,"op" to "event.create","calendarId" to book.id,"operationId" to uid(),
                "eventId" to "shift","event" to timed("早班"))
            requireOk(Commands(db).run(book,create,a))
            Sync(ctx,db,a).all()
            assertEquals(setOf(book.id),Widgets.selected(ctx,month))
            assertTrue(widgetText(host,month).contains("休息"))
            val upcoming=widgetText(host,agenda)
            assertTrue(upcoming.contains("家庭专属"))
            assertFalse(upcoming.contains("休息"))
            fun record()=requireOk(a.api("commands",obj("v" to 1,"op" to "event.get","calendarId" to book.id,"eventId" to "shift"))).getJSONObject("record")
            assertEquals("早班",record().getJSONObject("event").getString("title"))
            var earlierResolution: Turn? = null
            for (choice in listOf("local","remote")) {
                val cmd=create.copyJson().put("op","event.replace").put("baseVersion",record().getLong("version"))
                    .put("event",timed("本机"+choice)).put("operationId",uid())
                requireOk(Commands(db).run(db.rows().book(book.id)!!,cmd))
                requireOk(a.api("commands",cmd.copyJson().put("operationId",uid()).put("event",timed("远端"+choice))))
                assistant.confirm(plan("sync.run",obj("calendarId" to book.id)))
                val rev=DeviceCommands(ctx).run("sync.detail",obj("calendarId" to book.id)).getString("revision")
                val fields=obj("calendarId" to book.id,"choice" to choice,"revision" to rev)
                val resolution=plan("sync.resolve",fields,choice=="local")
                val stale=plan("sync.resolve",fields.copyJson())
                assistant.confirm(resolution); assistant.confirm(resolution)
                earlierResolution?.let { assistant.confirm(it) }
                earlierResolution = resolution
                assertTrue(runCatching { assistant.confirm(stale) }.isFailure)
                assertNull(db.rows().head(book.id))
                assertEquals((if(choice=="local") "本机" else "远端")+choice,record().getJSONObject("event").getString("title"))
            }
        } finally {
            main { host.stopListening(); host.deleteHost() }
            for (b in listOf(book,other)) db.rows().removeBook(b.id)
            vault.set("account",previousAccount); vault.set("model",previousModel)
        }
    }

    fun testAssistantScopeAndStream() {
        check(ctx.packageName.endsWith(".qa"))
        val base=args.getString("serverUrl")!!
        require(base.startsWith("http://127.0.0.1:"))
        val model=args.getString("modelUrl")!!
        val password="qa-stream-only-credentials"
        val name="Scope"+uid().take(8)
        request(base,"/api/v1/register",obj("name" to name,"password" to password))
        val vault = Vault(ctx)
        val previousAccount = vault.get("account")
        val previousModel = vault.get("model")
        try {
        vault.set("account",null)
        val a=Account.login(ctx,base,name,password)
        val console=request(base,"/api/v1/admin/login",obj("name" to "tester","password" to password)).cookie
        request(base,"/api/v1/admin/manage",obj("op" to "model.grant","subject" to name,"enabled" to true,"operationId" to uid()),cookie=console)
        val books=listOf("QA甲","QA乙","排除").map { label ->
            val b=Book("mobile-"+uid(),label,"#118844")
            db.rows().save(b)
            requireOk(Commands(db).run(b,obj("v" to 1,"op" to "event.create","calendarId" to b.id,"operationId" to uid(),"eventId" to "shift",
                "event" to obj("title" to label,"span" to obj("kind" to "date","start" to "2026-11-20","end" to "2026-11-21")))))
            b
        }
        Vault(ctx).set("model",obj("url" to model,"name" to "fixture","key" to ""))
        val assistant=Assistant(ctx,db)
        val scoped=books.take(2)
        fun verifyQuery(list:List<Book>,personal:Boolean) {
            val chunks=mutableListOf<String>()
            val r=assistant.chat(list,"多选查询",JSONArray(),personal,ChatStream {chunks.add(it)})
            assertEquals("已查询两本日历",r.text)
            assertTrue("missing partial text",chunks.contains("已查"))
        }
        verifyQuery(scoped,true)
        val local=assistant.chat(scoped,"多选改班",JSONArray(),true)
        assertEquals(scoped.last().id,local.target)
        assertEquals("QA乙",JSONObject(db.rows().entry(local.target,"shift")!!.json).getJSONObject("event").getString("title"))
        Sync(ctx,db,a).all()
        val shared=scoped.map {db.rows().book(it.id)!!}
        verifyQuery(shared,false)
        val remoteId = a.api("manage", obj("op" to "calendar.create", "operationId" to uid(), "name" to "远端", "color" to "#338855")).getString("id")
        val remote = Book(remoteId, "远端", "#338855", origin = a.origin, user = a.user)
        try {
            requireOk(a.api("commands",obj("v" to 1,"op" to "event.create","calendarId" to remoteId,"operationId" to uid(),"eventId" to "shift",
                "event" to obj("title" to "远端班次","span" to obj("kind" to "date","start" to "2026-11-20","end" to "2026-11-21")))))
            verifyQuery(listOf(shared.first(), remote), false)
            assertNull(db.rows().book(remoteId))
        } finally {
            a.api("manage", obj("op" to "calendar.delete", "target" to remoteId, "operationId" to uid()))
        }
        val turn=assistant.chat(shared,"多选改班",JSONArray(),false)
        assertEquals(shared.last().id,turn.target)
        assertTrue(turn.confirmation.isNotBlank())
        assistant.confirm(shared.last(),turn)
        assertEquals("改为晚班",JSONObject(db.rows().entry(turn.target,"shift")!!.json).getJSONObject("event").getString("title"))
        assertEquals("QA甲",JSONObject(db.rows().entry(shared.first().id,"shift")!!.json).getJSONObject("event").getString("title"))
        assertEquals("排除",JSONObject(db.rows().entry(books.last().id,"shift")!!.json).getJSONObject("event").getString("title"))
        DeviceCommands(ctx).run("calendar.delete",obj("calendarId" to shared.last().id,"operationId" to uid()))
        DeviceCommands(ctx).run("calendar.delete",obj("calendarId" to books.last().id,"operationId" to uid()))
        assertNull(db.rows().book(shared.last().id))
        assertNull(db.rows().book(books.last().id))
        assertNotNull(db.rows().book(shared.first().id))
        DeviceCommands(ctx).run("calendar.delete",obj("calendarId" to shared.first().id,"operationId" to uid()))
        } finally {
            vault.set("account",previousAccount)
            vault.set("model",previousModel)
        }
    }

    fun testPortableContract() {
        val assets = instrumentation.context.assets
        val files = assets.list("")!!.filter { it.endsWith(".json") && it != "schema.json" }
        assertEquals(7, files.size)
        for (file in files) {
            val case = assets.open(file).bufferedReader().use { JSONObject(it.readText()) }
            val sql = Room.inMemoryDatabaseBuilder(ctx, Store::class.java).build()
            try {
                val spec = case.getJSONObject("calendar")
                val b = Book(spec.getString("id"), "test", spec.getString("color"))
                sql.rows().save(b)
                val seen = mutableListOf<JSONObject>()
                case.getJSONArray("steps").objects().forEachIndexed { i, step ->
                    val a = case.getJSONObject("actors").getJSONObject(step.getString("as"))
                    val r =
                        Commands(sql)
                            .run(
                                b,
                                step.getJSONObject("command"),
                                a.getString("id"),
                                a.getString("role"),
                                false,
                            )
                    subset(step.getJSONObject("expect"), r, "$file/$i")
                    if (step.has("sameAs"))
                        assertEquals(canonical(seen[step.getInt("sameAs")]), canonical(r))
                    seen.add(r)
                }
            } finally {
                sql.close()
            }
        }
    }

    private fun waitFor(why: String, timeout: Long = 8000, fn: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        do {
            if (fn()) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < end)
        fail(why)
    }

    private fun tap(label: String) {
        waitFor("没有找到可点击的控件 " + label) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
            if (
                root.packageName?.toString() != ctx.packageName &&
                    root.packageName?.toString() != "android"
            )
                return@waitFor false
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                if (n.text?.toString() != label) continue
                var v: AccessibilityNodeInfo? = n
                while (v != null) {
                    if (v.isClickable && v.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        return@waitFor true
                    v = v.parent
                }
            }
            false
        }
    }

    private fun <T> main(f: () -> T): T {
        val r = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { r.set(runCatching(f)) }
        return r.get().getOrThrow()
    }

    private fun startFromHost(activity: String, extras: String) {
        val command =
            "am start -W -n " + ctx.packageName + "/org.timepin.app." + activity + " " + extras
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            )
            .bufferedReader()
            .use {
                val reply = it.readText()
                assertFalse(reply, reply.contains("Error:"))
            }
    }

    private fun bind(host: AppWidgetHost, month: Boolean): Int {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = main { host.allocateAppWidgetId() }
        val component =
            ComponentName(ctx, if (month) MonthWidget::class.java else AgendaWidget::class.java)
        assertTrue(
            "grantbind to the acceptance package",
            mgr.bindAppWidgetIdIfAllowed(id, component),
        )
        return id
    }

    private fun widgetText(host: AppWidgetHost, id: Int): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        val v = main {
            host.startListening()
            host.createView(ctx, id, mgr.getAppWidgetInfo(id))
        }
        fun texts(node: View): String =
            when (node) {
                is TextView -> node.text.toString()
                is android.view.ViewGroup ->
                    (0 until node.childCount).joinToString(" ") { texts(node.getChildAt(it)) }
                else -> ""
            }
        waitFor("组件没有应用 RemoteViews") {
            main {
                v.findViewById<View>(R.id.items)?.let {
                    (it as android.view.ViewGroup).childCount > 0
                } == true
            }
        }
        return main { texts(v) }
    }

    fun testFlightAndWidgets() {
        assertEquals(
            "flight mode required",
            1,
            Settings.Global.getInt(ctx.contentResolver, "airplane_mode_on", 0),
        )
        check(ctx.packageName.endsWith(".qa"))
        main { AppWidgetHost(ctx, 507).deleteHost() }
        db.initLocal()
        val b = Book("mobile-" + uid(), "QA 班表", "#287B69")
        val b2 = Book("mobile-" + uid(), "QA 家庭", "#9870B7", visible = false)
        db.rows().save(b)
        db.rows().save(b2)
        val initial =
            Commands(db)
                .run(
                    b2,
                    obj(
                        "v" to 1,
                        "op" to "event.create",
                        "calendarId" to b2.id,
                        "operationId" to uid(),
                        "eventId" to "home",
                        "event" to timed("家庭专属"),
                    ),
                )
        requireOk(initial)
        val importRows = JSONArray()
        for (i in 0..6) {
            val day = LocalDate.now().plusDays(i.toLong())
            importRows.put(
                obj(
                    "externalId" to "day-" + i,
                    "event" to
                        obj(
                            "title" to if (i == 0) "休息" else "排班 " + i,
                            "color" to if (i == 0) "#397eb3" else "#258253",
                            "span" to
                                obj(
                                    "kind" to "date",
                                    "start" to day.toString(),
                                    "end" to day.plusDays(1).toString(),
                                ),
                        ),
                )
            )
        }
        requireOk(
            Commands(db)
                .run(
                    b,
                    obj(
                        "v" to 1,
                        "calendarId" to b.id,
                        "op" to "import.commit",
                        "operationId" to uid(),
                        "batch" to obj("source" to "qa-shifts", "rows" to importRows),
                    ),
                )
        )
        requireOk(
            Commands(db)
                .run(
                    b,
                    obj(
                        "v" to 1,
                        "op" to "event.create",
                        "calendarId" to b.id,
                        "operationId" to uid(),
                        "eventId" to "shift-qa",
                        "event" to timed("交接班"),
                    ),
                )
        )
        val host = main { AppWidgetHost(ctx, 507) }
        val month = bind(host, true)
        val agenda = bind(host, false)
        fun configure(id: Int, only: Book) {
            // Start with an empty selection; exercise the actual exported configuration Activity.
            Widgets.save(ctx, id, emptySet())
            val monitor = instrumentation.addMonitor(WidgetSettings::class.java.name, null, false)
            val activity = try {
                startFromHost("WidgetSettings", "--ei appWidgetId " + id)
                requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000))
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            tap(only.name)
            tap("保存")
            waitFor("组件配置未保存") { Widgets.selected(ctx, id) == setOf(only.id) }
            // Rendering completes asynchronously before the configuration Activity finishes.
            waitFor("组件配置页未关闭") { main { activity.isDestroyed } }
        }
        configure(month, b)
        configure(agenda, b2)
        Widgets.refresh(ctx)
        assertTrue(widgetText(host, month).contains("休息"))
        val before = widgetText(host, agenda)
        assertTrue(before.contains("家庭专属"))
        assertFalse(before.contains("休息"))
        requireOk(
            Commands(db)
                .run(
                    b2,
                    obj(
                        "v" to 1,
                        "op" to "event.replace",
                        "calendarId" to b2.id,
                        "operationId" to uid(),
                        "eventId" to "home",
                        "baseVersion" to 1,
                        "event" to timed("改后的家庭班", 12),
                    ),
                )
        )
        Widgets.refresh(ctx, setOf(b2.id))
        assertTrue(widgetText(host, agenda).contains("改后的家庭班"))
        qa.edit()
            .putString("primary", b.id)
            .putString("secondary", b2.id)
            .putInt("month", month)
            .putInt("agenda", agenda)
            .commit()
        main { host.stopListening() }
    }

    fun testPublishAndModel() {
        val url = args.getString("url") ?: error("missing test NAS URL")
        val a = Account.login(ctx, url, args.getString("name")!!, args.getString("password")!!)
        val sync = Sync(ctx, db, a)
        sync.all()
        sync.syncBook(primary)
        val month = qa.getInt("month", -1)
        val agenda = qa.getInt("agenda", -1)
        val host = main { AppWidgetHost(ctx, 507) }
        try {
            assertEquals(setOf(primary), Widgets.selected(ctx, month))
            assertTrue(widgetText(host, month).contains("休息"))
            assertEquals(setOf(secondary), Widgets.selected(ctx, agenda))
            assertTrue(widgetText(host, agenda).contains("改后的家庭班"))
            val assistant = Assistant(ctx, db)
            Vault(ctx)
                .set(
                    "model",
                    obj(
                        "url" to args.getString("modelUrl"),
                        "name" to "timepin-fixture",
                        "key" to "",
                    ),
                )
            var b = db.rows().book(primary)!!
            val local = assistant.chat(b, "把交接班改到下午", JSONArray(), true)
            assertNotNull(local.proposal)
            assertEquals(
                1L,
                JSONObject(db.rows().entry(primary, "shift-qa")!!.json).getLong("version"),
            )
            assistant.confirm(b, local)
            sync.syncBook(primary)
            val get =
                obj("v" to 1, "op" to "event.get", "calendarId" to primary, "eventId" to "shift-qa")
            assertEquals(2L, a.api("commands", get).getJSONObject("record").getLong("version"))
            b = db.rows().book(primary)!!
            assistant.chat(b, "用实例模型把交接班调整为晚班", JSONArray(), false)
            assertEquals(
                3L,
                JSONObject(db.rows().entry(primary, "shift-qa")!!.json).getLong("version"),
            )
            assertEquals("模型调整后的晚班", assistant.chat(b, "查询交接班标题", JSONArray(), false).text)
            val remote = a.api("commands", get).getJSONObject("record")
            assertEquals("模型调整后的晚班", remote.getJSONObject("event").getString("title"))
            android.util.Log.i(
                "TimePinQA",
                "Published " +
                    primary +
                    "; same widget IDs " +
                    month +
                    " / " +
                    agenda +
                    "; shared version " +
                    remote.getLong("version"),
            )
        } finally {
            main { host.stopListening() }
        }
    }

    fun testFetchRemote() {
        val a = Account.read(ctx)!!
        Sync(ctx, db, a).syncBook(primary)
        val b = db.rows().book(primary)!!
        val title =
            JSONObject(db.rows().entry(primary, "shift-qa")!!.json)
                .getJSONObject("event")
                .getString("title")
        assertEquals("家人改班", title)
        assertEquals(title, Assistant(ctx, db).chat(b, "查询交接班标题", JSONArray(), false).text)
    }

    fun testRetryAndConflict() {
        val account = Account.read(ctx)!!
        val sync = Sync(ctx, db, account)
        val book = db.rows().book(primary)!!
        val eventId = "retry-" + uid()
        val create =
            obj(
                "v" to 1,
                "op" to "event.create",
                "calendarId" to primary,
                "operationId" to uid(),
                "eventId" to eventId,
                "event" to timed("待交班"),
            )
        requireOk(Commands(db).run(book, create))
        requireOk(account.api("commands", create))
        // The server accepted the operation, but the phone still has its original outbox item.
        sync.syncBook(primary)
        assertNull(db.rows().head(primary))
        val update =
            create
                .copyJson()
                .put("op", "event.replace")
                .put("operationId", uid())
                .put("baseVersion", 1)
                .put("event", timed("手机离线改班", 10))
        requireOk(Commands(db).run(book, update))
        val other = update.copyJson().put("operationId", uid()).put("event", timed("远端改班", 12))
        requireOk(account.api("commands", other))
        sync.syncBook(primary)
        val queued = db.rows().head(primary)!!
        assertEquals("conflict", queued.state)
        assertEquals("手机离线改班", JSONObject(queued.command).getJSONObject("event").getString("title"))
        assertEquals(
            "远端改班",
            JSONObject(queued.detail)
                .getJSONObject("current")
                .getJSONObject("event")
                .getString("title"),
        )
        sync.resolve(primary,Sync.queueRevision(db.rows().queue(primary)),"remote",uid())
        assertNull(db.rows().head(primary))
        val record = JSONObject(db.rows().entry(primary, eventId)!!.json)
        assertEquals(2L, record.getLong("version"))
        assertEquals("远端改班", record.getJSONObject("event").getString("title"))
        requireOk(
            Commands(db)
                .run(
                    book,
                    obj(
                        "v" to 1,
                        "op" to "event.delete",
                        "calendarId" to primary,
                        "eventId" to eventId,
                        "operationId" to uid(),
                        "baseVersion" to 2,
                    ),
                )
        )
        sync.syncBook(primary)
    }

    fun testMonthAndRefresh() {
        check(ctx.packageName.endsWith(".qa"))
        val book = db.rows().book(secondary)!!
        val first = LocalDate.now().withDayOfMonth(1)
        val rows = JSONArray()
        for (i in 0 until first.lengthOfMonth()) {
            val date = first.plusDays(i.toLong())
            val rest = i % 7 in setOf(0, 4)
            val event =
                if (rest)
                    obj(
                        "title" to "休息",
                        "color" to "#337BA9",
                        "span" to
                            obj(
                                "kind" to "date",
                                "start" to date.toString(),
                                "end" to date.plusDays(1).toString(),
                            ),
                    )
                else {
                    val hour = listOf(9, 10, 12)[i % 3]
                    obj(
                        "title" to "轮班",
                        "color" to "#298459",
                        "span" to
                            obj(
                                "kind" to "timed",
                                "zone" to "Asia/Shanghai",
                                "start" to date.toString() + "T%02d:00:00+08:00".format(hour),
                                "end" to date.toString() + "T%02d:00:00+08:00".format(hour + 8),
                            ),
                    )
                }
            rows.put(obj("externalId" to date.toString(), "event" to event))
        }
        val command =
            obj(
                "v" to 1,
                "op" to "import.commit",
                "calendarId" to secondary,
                "operationId" to uid(),
                "batch" to obj("source" to "qa-month", "rows" to rows),
            )
        requireOk(Commands(db).run(book, command))
        val refreshed = Widgets.refresh(ctx, setOf(secondary))
        assertTrue(qa.getInt("agenda", -1) in refreshed)
        assertFalse(qa.getInt("month", -1) in refreshed)
        assertTrue(refreshed.all { secondary in Widgets.selected(ctx, it).orEmpty() })
        android.util.Log.i(
            "TimePinQA",
            "Imported " +
                rows.length() +
                " items; refreshed widget IDs " +
                refreshed +
                "; launcher pin support " +
                AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported,
        )
        db.rows().save(book.copy(visible = true, name = "轮班日历"))
        for (b in db.rows().books().filter { it.id != secondary }) db.rows()
            .save(b.copy(visible = false))
        ctx.getSharedPreferences("display", 0).edit().putString("theme", "dark").commit()
    }

    // Interactive launcher acceptance on the disposable package. The system owns the pin dialog.
    fun testRequestLauncherWidget() {
        check(ctx.packageName.endsWith(".qa"))
        val month = args.getString("widget") == "month"
        val provider =
            ComponentName(ctx, if (month) MonthWidget::class.java else AgendaWidget::class.java)
        startFromHost("MainActivity", "-a android.intent.action.MAIN")
        val manager = AppWidgetManager.getInstance(ctx)
        assertTrue(manager.isRequestPinAppWidgetSupported)
        assertTrue(main { Widgets.requestPin(ctx, provider) })
    }
}
