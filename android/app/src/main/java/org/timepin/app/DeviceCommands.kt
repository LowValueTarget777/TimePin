package org.timepin.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal class DeviceCommands(private val c: Context) {
    val schemas by lazy {
        c.assets.open("device.json").bufferedReader().use { JSONObject(it.readText()) }
    }

    fun readOnly(op: String) = op.endsWith(".detail") || op == "system.list" || op == "drafts.list"

    fun syncStatus(db: Store, book: Book): JSONObject = db.atomic {
        val sql = db.rows()
        val head = sql.head(book.id)
        val info = obj("calendarId" to book.id, "name" to book.name, "linked" to book.origin.isNotEmpty(),
            "role" to book.role, "pendingCount" to (if (head == null) 0 else sql.queueCount(book.id)),
            "status" to (head?.state ?: "ready"))
        if (head != null && head.state != "pending") {
            val edit = JSONObject(head.command)
            val current = runCatching { JSONObject(head.detail).optJSONObject("current") }.getOrNull()
            fun summary(event: JSONObject?) = event?.let { obj("title" to it.optString("title"), "span" to it.optJSONObject("span")) }
            info.put("revision", Sync.queueRevision(sql.queue(book.id)))
                .put("localEvent", summary(edit.optJSONObject("event")))
                .put("remoteEvent", summary(current?.optJSONObject("event")))
                .put("canRetryLocal", info.getInt("pendingCount") == 1 && edit.optString("op") == "event.replace" && current?.optBoolean("deleted", true) == false)
        }
        info
    }

    fun run(op: String, args: JSONObject): JSONObject {
        Contract.check(schemas.getJSONObject(op), args)
        val db = Store.get(c)
        return when (op) {
            "drafts.list", "drafts.detail" -> {
                val account = Account.read(c) ?: error("请先登录实例")
                db.atomic {
                    if (op == "drafts.list") {
                        val rows = db.rows().accountDrafts(account.origin, account.user, args.optLong("after"))
                        val page = rows.take(30)
                        obj("drafts" to JSONArray(page.map {
                            obj("seq" to it.seq, "calendarId" to it.calendarId, "name" to it.name, "state" to it.state)
                        })).apply { if (rows.size > page.size) put("next", page.last().seq) }
                    } else {
                        val seq = args.getLong("seq")
                        val offset = args.optInt("offset")
                        val pageSize = 16384
                        val draft = db.rows().accountDraft(account.origin, account.user, seq, offset, pageSize + 1)
                            ?: error("草稿已处理或不属于当前账号")
                        require(draft.input.isNotEmpty()) { "草稿位置无效" }
                        val more = draft.input.codePointCount(0, draft.input.length) > pageSize
                        val input = if (more) draft.input.dropLast(Character.charCount(draft.input.codePointBefore(draft.input.length))) else draft.input
                        obj("seq" to seq, "calendarId" to draft.calendarId, "name" to draft.name,
                            "state" to draft.state, "input" to input).apply {
                            if (more) put("nextOffset", offset + pageSize)
                            if (offset == 0 && draft.role != "none" && draft.state != "pending") {
                                db.rows().draftReceiptPreview(account.origin, account.user, seq)
                                    ?.takeIf { it.isNotEmpty() }?.let { put("receiptPreview", it) }
                            }
                            if (offset == 0 && draft.role != "none" && draft.state != "pending" &&
                                db.rows().head(draft.calendarId)?.seq == seq) {
                                put("resolution", syncStatus(db, requireNotNull(db.rows().book(draft.calendarId))))
                            }
                        }
                    }
                }.also { check(Account.read(c) == account) { "账号已切换，请重新打开" } }
            }
            "server.detail", "server.connect" -> {
                val origin = if (op == "server.connect") Account.connect(c, args.getString("url")) else Account.selectedServer(c)
                obj("url" to origin, "signedIn" to (Account.read(c) != null))
            }
            "background.detail", "background.update", "background.photo" -> {
                val id = args.optInt("widgetId")
                val widgets = Widgets.installed(c)
                require(id == 0 || id in widgets) { "组件已被移除" }
                if (op != "background.detail") {
                    Backdrops.save(c, id, if (op == "background.photo") args.copyJson().put("mode","photo") else args)
                    for ((widget, kind) in widgets) {
                        if (widget == id || id == 0 && Backdrops.read(c,widget).mode == "follow")
                            Widgets.render(c,widget,kind == "month")
                    }
                    if (id == 0) c.contentResolver.notifyChange(Uri.parse("content://" + c.packageName + ".device"), null)
                }
                Backdrops.read(c,id).detail()
            }
            "widgets.detail", "widgets.update" -> {
                val installed = Widgets.installed(c)
                val books = db.accountBooks(c).filter { it.role != "none" }
                if (op == "widgets.update") {
                    val id = args.getInt("widgetId")
                    val kind = installed[id] ?: error("组件已被移除")
                    val ids = args.getJSONArray("calendarIds").strings().toSet()
                    require(ids.all { cal -> books.any { it.id == cal } }) { "日历不可用" }
                    Widgets.save(c, id, ids)
                    Widgets.render(c,id,kind == "month")
                    return obj("ok" to true)
                }
                obj("widgets" to JSONArray(installed.map { (id, kind) ->
                    val selected = Widgets.selected(c,id)
                    obj("widgetId" to id, "kind" to kind, "calendarIds" to JSONArray(books.filter {
                        selected?.contains(it.id) ?: it.visible
                    }.map { it.id })).apply {
                        put("background",Backdrops.read(c,id).detail())
                    }
                }))
            }
            "sync.detail", "sync.run", "sync.resolve" -> {
                val id = args.getString("calendarId")
                require(db.accountBooks(c).any { it.id == id && it.role != "none" }) { "日历不可用" }
                if (op != "sync.detail") {
                    val a = Account.read(c) ?: error("请先登录实例")
                    val sync = Sync(c,db,a)
                    when (op) {
                        "sync.run" -> sync.syncBook(id)
                        "sync.resolve" -> sync.resolve(id,args.getString("revision"),args.getString("choice"),args.getString("operationId"))
                    }
                }
                syncStatus(db, db.rows().book(id) ?: error("日历已移除"))
            }
            "calendar.update", "calendar.visibility" -> {
                val id = args.getString("calendarId")
                val opId = args.getString("operationId")
                val book = db.accountBooks(c).firstOrNull { it.id == id && it.role != "none" } ?: error("日历不可用")
                val edit = op == "calendar.update"
                val name = if (edit) args.getString("name").trim() else ""
                val color = if (edit) args.getString("color").uppercase() else ""
                val visible = args.optBoolean("enabled")
                val actor = "book-settings"
                val fingerprint = hash(op, name, color, visible.toString())
                val sql = db.rows()
                if (edit) {
                    require(book.role == "manager" && book.publication.isEmpty()) { "当前无法编辑这本日历" }
                    require(name.isNotEmpty() && name.none { it.isISOControl() } && colorPattern.matches(color)) { "请检查日历名称和颜色" }
                }
                sql.receipt(id,actor,opId)?.let {
                    require(it.fingerprint == fingerprint) { "操作编号已用于其他请求" }
                    return obj("ok" to true)
                }
                if (edit && book.origin.isNotEmpty()) {
                    val account = Account.read(c) ?: error("请先登录")
                    require(account.origin == book.origin && account.user == book.user)
                    account.api("manage",obj("op" to op,"target" to book.serverId,"operationId" to opId,"name" to name,"color" to color))
                    Sync(c,db,account).syncBook(id)
                }
                db.atomic {
                    val live = db.accountBooks(c).firstOrNull { it.id == id } ?: error("日历已变更")
                    require(live.origin == book.origin && live.user == book.user && live.role != "none") { "日历授权已变更" }
                    val prior = sql.receipt(id,actor,opId)
                    if (prior != null) {
                        require(prior.fingerprint == fingerprint)
                    } else {
                        if (!edit) sql.save(live.copy(visible=visible))
                        else if (live.origin.isEmpty()) {
                            require(live.publication.isEmpty() && live.role == "manager")
                            sql.save(live.copy(name=name,color=color))
                        }
                        sql.save(Receipt(id,actor,opId,fingerprint,"[]","{}"))
                    }
                }
                Widgets.refresh(c,setOf(id))
                obj("ok" to true)
            }
            "calendar.delete" -> {
                val id = args.getString("calendarId")
                val opId = args.getString("operationId")
                val a = Account.read(c)
                val instanceActor = a?.let { "delete:" + hash(it.origin, it.user) }
                val localActor = "delete:local"
                val sql = db.rows()
                val b = db.accountBooks(c).find { it.id == id }
                if (b == null) {
                    require(listOfNotNull(localActor, instanceActor).any { sql.receipt(id,it,opId)!=null }) { "日历不可用" }
                    return obj("ok" to true)
                }
                require(b.role == "manager" || (b.origin.isNotEmpty() && b.role == "none")) { "没有删除权限" }
                require(b.publication.isEmpty()) { "请先完成日历发布" }
                val actor = if (b.origin.isEmpty()) localActor else requireNotNull(instanceActor)
                if (b.origin.isNotEmpty()) {
                    requireNotNull(a) { "请先登录实例" }
                    require(b.origin==a.origin && b.user==a.user)
                    a.api("manage",obj("op" to "calendar.delete","operationId" to args.getString("operationId"),"target" to b.serverId))
                }
                db.atomic {
                    val live = sql.book(id)
                    if (live == null) {
                        require(sql.receipt(id, actor, opId) != null) { "日历已被删除" }
                        return@atomic
                    }
                    require(live.origin == b.origin && live.user == b.user) { "日历归属已变化" }
                    for (op in sql.queue(id)) { sql.detach(op.seq); sql.dequeue(op.seq) }
                    sql.clearEvents(id)
                    sql.clearReceipts(id)
                    sql.clearBatches(id)
                    sql.clearObservations(id)
                    sql.removeBook(id)
                    sql.save(Receipt(id,actor,opId,"calendar.delete","[]","{}"))
                }
                Widgets.refresh(c,setOf(id))
                Reminders.reschedule(c)
                obj("ok" to true)
            }
            "system.autoImport.detail", "system.autoImport.update" -> {
                if (op.endsWith(".update")) SystemCalendarWatcher.setEnabled(c, args.getBoolean("enabled"))
                obj("enabled" to SystemCalendarWatcher.enabled(c),
                    "readGranted" to SystemCalendarWatcher.hasPermission(c))
            }
            "display.detail" -> MonthOptions.read(c).json()
            "display.update" -> {
                val old = MonthOptions.read(c)
                val updated =
                    MonthOptions(
                        args.optString("weekStart", old.weekStart),
                        args.optBoolean("weekNumbers", old.weekNumbers),
                        args.optBoolean("lunar", old.lunar),
                        args.optString("holidayRegion", old.holidayRegion),
                        args.optInt("fontSize", old.fontSize),
                        args.optString("lunarFormat", old.lunarFormat),
                    )
                MonthOptions.save(c, updated)
                Widgets.refresh(c)
                updated.json()
            }
            "reminders.detail",
            "reminders.update" -> {
                val books = db.accountBooks(c).filter { it.role != "none" }
                if (op == "reminders.update") {
                    val enabled = args.optBoolean("enabled", Reminders.enabled(c))
                    require(
                        !enabled ||
                            Build.VERSION.SDK_INT < 33 ||
                            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                    ) {
                        "请先在手机上允许日程通知"
                    }
                    val muted = args.optJSONArray("muted")?.strings()?.toSet()
                    require(muted == null || muted.all { id -> books.any { it.id == id } }) {
                        "未知日历"
                    }
                    Reminders.configure(
                        c,
                        enabled,
                        args.optInt("minutes", Reminders.minutes(c)),
                        muted ?: Reminders.muted(c),
                    )
                }
                obj(
                    "enabled" to Reminders.enabled(c),
                    "minutes" to Reminders.minutes(c),
                    "muted" to
                        JSONArray(
                            Reminders.muted(c).intersect(books.map { it.id }.toSet()).toList()
                        ),
                    "calendars" to JSONArray(books.map { obj("id" to it.id, "name" to it.name) }),
                )
            }
            else -> {
                require(
                    c.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    "请先在手机上允许读取日历"
                }
                val sources = SystemCalendars.list(c)
                if (op == "system.list")
                    obj(
                        "calendars" to
                            JSONArray(sources.map { obj("sourceId" to it.id, "name" to it.name) })
                    )
                else {
                    val s = sources.single { it.id == args.getLong("sourceId") }
                    val counts =
                        SystemCalendars.copy(
                            c,
                            s,
                            LocalDate.parse(args.getString("from")),
                            LocalDate.parse(args.getString("until")),
                            args.optBoolean("updateShared"),
                        )
                    Widgets.refresh(c, setOf(s.localId))
                    obj("calendarId" to s.localId, "counts" to JSONObject(counts))
                }
            }
        }
    }
}

// The manifest and binder check restrict the command interface to the app or an authorized ADB
// shell.
class DeviceCommandProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = requireNotNull(context)
        c.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, "需要已授权的 ADB 连接")
        val caller = android.os.Binder.clearCallingIdentity()
        try {
            val commands = DeviceCommands(c)
            val result =
                if (method == "tools") commands.schemas
                else commands.run(method, JSONObject(arg ?: "{}"))
            if (method != "tools" && !commands.readOnly(method))
                c.contentResolver.notifyChange(
                    Uri.parse("content://" + c.packageName + ".device"),
                    null,
                )
            return Bundle().apply { putString("json", result.toString()) }
        } finally {
            android.os.Binder.restoreCallingIdentity(caller)
        }
    }

    override fun query(
        uri: Uri,
        p: Array<out String>?,
        s: String?,
        a: Array<out String>?,
        o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
