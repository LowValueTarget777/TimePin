package org.timepin.app

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class Turn(
    val text: String,
    val proposal: JSONObject? = null,
    val confirmation: String = "",
    val changed: Boolean = false,
    val target: String = "",
    val action: ActionConfirmation? = null,
)

class Assistant(private val ctx: Context, private val db: Store) {
    private fun hasCopy(b: Book, a: Account) =
        db.rows().book(b.id)?.let { it.origin == a.origin && it.user == a.user } == true

    private fun shared(b: Book): Account {
        val a = Account.read(ctx) ?: error("请先登录实例")
        require(b.origin == a.origin && b.user == a.user && b.publication.isEmpty()) {
            "请选择已同步的实例日历"
        }
        if (hasCopy(b, a) && db.rows().head(b.id) != null) {
            Sync(ctx, db, a).syncBook(b.id)
            require(db.rows().head(b.id) == null) { "请先处理待同步的修改" }
        }
        return a
    }

    fun chat(b: Book, text: String, history: JSONArray, personal: Boolean): Turn =
        chat(listOf(b), text, history, personal)

    fun chat(books: List<Book>, text: String, history: JSONArray, personal: Boolean,
             stream: ChatStream = ChatStream(), operationId: String = uid()): Turn {
        require(books.isNotEmpty()) { "请至少选择一本日历" }
        val first = books.first()
        val ids = books.map { it.id }
        val multi = ids.size > 1
        fun checkScope() {
            if (personal) {
                val available = db.accountBooks(ctx).associateBy { it.id }
                require(books.all { available[it.id]?.role == it.role && it.role != "none" }) { "日历权限已改变，请重新选择" }
            }
            check(!stream.cancelled) { "已停止接收回复" }
        }
        checkScope()
        require(text.isNotBlank())
        val zone = ZoneId.systemDefault().id
        if (!personal) {
            val a = shared(first)
            require(books.all { it.origin == a.origin && it.user == a.user && it.publication.isEmpty() }) { "服务器模型仅可操作已连接日历" }
            // Pull only calendars with pending local edits before a remote operation.
            for (b in books.drop(1)) if (db.rows().head(b.id) != null) shared(b)
            val payload = obj("operationId" to operationId, "zone" to zone, "message" to text, "history" to history)
            if (multi) payload.put("calendarIds",JSONArray(books.map { it.serverId }))
            else payload.put("calendarId",first.serverId)
            val r = a.api("ai/chat",payload,stream)
            r.optJSONObject("result")?.let(::requireOk)
            val pending = r.optString("status") == "confirmation"
            val proposal = r.optJSONObject("proposal")
            val remoteTarget = proposal?.optString("calendarId")?.takeIf { it.isNotBlank() }
                ?: proposal?.optString("target")?.takeIf { it.isNotBlank() } ?: first.serverId
            val target = books.single { it.serverId == remoteTarget }.id
            if (!pending && !multi && r.optString("op").isNotEmpty() && hasCopy(first, a)) Sync(ctx, db, a).syncBook(first.id)
            return Turn(r.optString("text"), proposal, if (pending) operationId else "", !pending,target)
        }
        val cfg = Vault(ctx).get("model") ?: error("请在设置中填写自配模型")
        val messages =
            JSONArray()
                .put(
                    obj(
                        "role" to "system",
                        "content" to
                            "你是 TimePin 排班助手。当前日期 " +
                                LocalDate.now() +
                                "，时区 " +
                                zone +
                                "，日历 " +
                                JSONArray(books.map { obj("id" to it.id,"name" to it.name,"role" to it.role) }) +
                                "。event_query 省略 calendarId 即查询全部选中日历。其他操作指定目标，目标不明时先问用户" +
                                "。先查询真实事件，按用户要求调用工具。写操作会展示给用户确认。",
                    )
                )
        for (h in history.objects()) messages.put(h)
        messages.put(obj("role" to "user", "content" to text))
        fun expand(s: JSONObject): JSONObject {
            if (s.has("\$ref"))
                return expand(
                    Contract.defs.getJSONObject(s.getString("\$ref").substringAfterLast('/'))
                )
            val copy = JSONObject()
            for (k in s.keys()) when (val v = s.get(k)) {
                is JSONObject -> copy.put(k, expand(v))
                is JSONArray ->
                    copy.put(
                        k,
                        JSONArray(
                            (0 until v.length()).map { i ->
                                val x = v.get(i)
                                if (x is JSONObject) expand(x) else x
                            }
                        ),
                    )
                else -> copy.put(k, v)
            }
            return copy
        }
        val names =
            listOf("event.query", "event.get", "event.create", "event.replace", "event.delete")

        val tools = JSONArray()
        for (name in names) {
            if (books.all { it.role == "reader" } && name !in listOf("event.query", "event.get")) continue
            val schema = expand(Contract.defs.getJSONObject("op." + name))
            val hidden = mutableSetOf("v", "op", "operationId", "calendarId")
            if (name == "event.create") hidden.add("eventId")
            for (k in hidden) schema.getJSONObject("properties").remove(k)
            schema.put(
                "required",
                JSONArray(schema.array("required").strings().filterNot { it in hidden }),
            )
            if (multi) {
                schema.getJSONObject("properties").put("calendarId",obj("type" to "string","enum" to JSONArray(ids)))
                if (name!="event.query") schema.getJSONArray("required").put("calendarId")
            }
            schema.remove("x-timepin")
            tools.put(
                obj(
                    "type" to "function",
                    "function" to
                        obj(
                            "name" to name.replace('.', '_'),
                            "description" to (schema.remove("description") ?: name),
                            "parameters" to schema,
                        ),
                )
            )
        }
        val device = DeviceCommands(ctx)
        val includeGlobal = db.accountBooks(ctx).filter { it.role!="none" }.all {it.id in ids}
        for (name in device.schemas.keys()) {
            if (name.startsWith("widgets.") || name.startsWith("sync.") || name.startsWith("drafts.")) continue
            if (!includeGlobal && (name.startsWith("reminders.") || name.startsWith("system."))) continue
            val schema = device.schemas.getJSONObject(name).copyJson()
            if (name=="calendar.delete") {
                val managers=books.filter {it.role=="manager"}.map {it.id}
                if(managers.isEmpty()) continue
                schema.getJSONObject("properties").remove("operationId")
                schema.getJSONObject("properties").getJSONObject("calendarId").put("enum",JSONArray(managers))
                schema.put("required",JSONArray().put("calendarId"))
            }
            tools.put(
                obj(
                    "type" to "function",
                    "function" to
                        obj(
                            "name" to name.replace('.', '_'),
                            "description" to schema.remove("description"),
                            "parameters" to schema,
                        ),
                )
            )
        }
        fun groupedQuery(call: JSONObject): String {
            val fn = call.getJSONObject("function")
            val cmd = JSONObject(fn.getString("arguments")).put("v", 1).put("op", "event.query")
            val targets = if (cmd.has("calendarId")) {
                val id = cmd.getString("calendarId")
                listOf(books.find { it.id == id } ?: error("目标不在所选日历范围"))
            } else books
            val groups = JSONArray()
            var length = 0
            for (book in targets) {
                val query = cmd.copyJson().put("calendarId", book.id)
                Contract.command(query)
                val result = requireOk(Commands(db).run(book, query))
                val group = obj("calendarId" to book.id, "name" to book.name, "result" to result)
                length += group.toString().length
                if (length > 24000) return "结果太多，请缩小日期范围"
                groups.put(group)
            }
            return groups.toString()
        }
        repeat(5) {
            checkScope()
            val r =
                request(
                        cfg.getString("url"),
                        "/chat/completions",
                        obj(
                            "model" to cfg.getString("name"),
                            "messages" to messages,
                            "tools" to tools,
                            "stream" to true,
                        ),
                        bearer = cfg.optString("key"), stream = stream,
                    )
                    .body
            checkScope()
            val message = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = message.array("tool_calls").objects()
            val answer = if (message.isNull("content")) "" else message.optString("content")
            if (calls.isEmpty()) return Turn(answer)
            if (multi && calls.all { it.getJSONObject("function").getString("name") == "event_query" }) {
                messages.put(message)
                var length = 0
                for (call in calls) {
                    val result = groupedQuery(call)
                    length += result.length
                    require(length <= 24000) { "结果太多，请缩小日期范围" }
                    messages.put(obj("role" to "tool", "tool_call_id" to call.getString("id"), "content" to result))
                }
                return@repeat
            }
            require(calls.size == 1) { "请让助手一次执行一个操作" }
            messages.put(message)
            val call = calls.single()
            val fn = call.getJSONObject("function")
            val op = fn.getString("name").replace('_', '.')
            if (device.schemas.has(op)) {
                require(tools.objects().any { it.getJSONObject("function").getString("name") == fn.getString("name") })
                val args = JSONObject(fn.getString("arguments"))
                require(includeGlobal || (!op.startsWith("reminders.") && !op.startsWith("system.")))
                if (op=="calendar.delete") {
                    require(args.getString("calendarId") in ids)
                    args.put("operationId",operationId)
                }
                Contract.check(device.schemas.getJSONObject(op), args)
                if (!device.readOnly(op)) {
                    val target = books.find { it.id == args.optString("calendarId") }
                    val prompt = target?.let { "请核对「" + it.name + "」的改动" } ?: answer.ifBlank { "请核对设备操作" }
                    return Turn(prompt, obj("device" to op, "args" to args), target = target?.id.orEmpty())
                }
                messages.put(
                    obj(
                        "role" to "tool",
                        "tool_call_id" to call.getString("id"),
                        "content" to device.run(op, args).toString(),
                    )
                )
                return@repeat
            }
            require(op in names)
            val cmd = JSONObject(fn.getString("arguments")).put("v",1).put("op",op)
            if (multi && !cmd.has("calendarId")) return Turn("要操作哪本日历？请告诉我日历名称。")
            val id=cmd.optString("calendarId", first.id)
            val b=books.find { it.id==id } ?: error("目标不在所选日历范围")
            cmd.put("calendarId",id)
            if (op == "event.create") cmd.put("eventId", uid())
            if (op !in listOf("event.query", "event.get", "import.undoPreview"))
                cmd.put("operationId", uid())
            Contract.command(cmd)
            if (op !in listOf("event.query", "event.get", "import.preview", "import.undoPreview"))
                return Turn("请核对「" + b.name + "」的改动", cmd, target=b.id)
            val current = db.rows().book(b.id) ?: error("日历不存在")
            val body = Commands(db).run(current, cmd).toString()
            messages.put(
                obj(
                    "role" to "tool",
                    "tool_call_id" to call.getString("id"),
                    "content" to if (body.length < 90000) body else "结果太多，请缩小日期范围",
                )
            )
        }
        error("查询轮数已达上限，请缩小改班范围")
    }

    fun confirm(book: Book, t: Turn): String {
        if (t.confirmation.isNotEmpty()) {
            val a = shared(book)
            val reply = a.api("ai/confirm", obj("operationId" to t.confirmation))
            if (hasCopy(book, a)) Sync(ctx, db, a).syncBook(book.id)
            return reply.optString("text", "已确认")
        }
        t.proposal
            ?.optString("device")
            ?.takeIf { it.isNotBlank() }
            ?.let { name ->
                val result = DeviceCommands(ctx).run(name, t.proposal.getJSONObject("args"))
                return "已执行 " + name + " · " + result
            }
        val b = db.accountBooks(ctx).find { it.id == book.id && it.role!="none" } ?: error("日历不可用")
        val reply = requireOk(Commands(db).run(b, t.proposal!!))
        Widgets.refresh(ctx, setOf(b.id))
        return "已执行 " + t.proposal.getString("op") + " · " + reply.getString("code")
    }
}
