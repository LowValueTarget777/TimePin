package org.timepin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ActionConfirmation(val scope: String, val personal: Boolean, val origin: String,
    val user: String, val selected: Set<String>, val operationId: String)

/** Account proposals execute on the instance; device proposals always execute on the phone. */
class ActionAssistant(private val ctx: Context, private val db: Store) {
    private val device = DeviceCommands(ctx)
    private val phoneOps = listOf("widgets.update", "sync.run", "sync.resolve", "background.update")
    private val accountOps = setOf("calendar.create", "group.create", "invite.accept")
    private val invitation = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")

    private fun selectedBooks(ids: Set<String>): List<Book> {
        val books = db.accountBooks(ctx).filter { it.id in ids && it.role != "none" }
        require(ids.isNotEmpty() && books.size == ids.size) { "请重新选择可用的本机日历" }
        return books
    }

    private fun widgets(ids: Set<String>) =
        device.run("widgets.detail",JSONObject()).getJSONArray("widgets").objects().filter {
            it.getJSONArray("calendarIds").strings().all(ids::contains)
        }

    private fun phoneTools(): JSONArray = JSONArray(phoneOps.map { op ->
        val schema = device.schemas.getJSONObject(op).copyJson()
        schema.getJSONObject("properties").remove("operationId")
        schema.put("required",JSONArray(schema.array("required").strings().filterNot { it == "operationId" }))
        obj("type" to "function","function" to obj("name" to op.replace('.','_'),
            "description" to schema.remove("description"),"parameters" to schema))
    })

    fun chat(scope: String, ids: Set<String>, text: String, history: JSONArray, personal: Boolean,
             stream: ChatStream, operationId: String): Turn {
        val a = Account.read(ctx)
        val meta = when (scope) {
            "account" -> obj("groups" to requireNotNull(a) { "请先登录实例" }.api("state").array("groups"))
            "device" -> obj("calendars" to JSONArray(selectedBooks(ids).map {
                device.syncStatus(db,it)
            }), "widgets" to JSONArray(widgets(ids))).apply {
                put("background", Backdrops.read(ctx).detail())
            }
            else -> error("未知的对话范围")
        }
        fun modelText(value: String) = if (scope == "account") invitation.replace(value,"[本次邀请]") else value
        val past = JSONArray(history.objects().map {
            obj("role" to it.getString("role"),"content" to modelText(it.getString("content")))
        })
        val requestBody = obj("scope" to scope,"message" to modelText(text),"history" to past,"context" to meta)
        val proposal: JSONObject
        if (personal) {
            val config = Vault(ctx).get("model") ?: error("请先配置个人模型")
            val tools = if (scope == "device") phoneTools() else
                requireNotNull(a).api("ai/actions?scope=account").getJSONArray("tools")
            val messages = JSONArray().put(obj("role" to "system","content" to
                ("你是 TimePin 助手。根据用户明确请求提出一项操作，确认后执行。目标不确定时询问。默认日历颜色 #278B77。选区和状态：" + meta)))
            past.objects().forEach { messages.put(it) }
            messages.put(obj("role" to "user","content" to modelText(text)))
            val answer = request(config.getString("url"),"/chat/completions",obj("model" to config.getString("name"),
                "tools" to tools,"messages" to messages,"stream" to true),bearer=config.optString("key"),stream=stream)
                .body.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = answer.array("tool_calls").objects()
            if (calls.isEmpty()) return Turn(answer.optString("content"))
            require(calls.size == 1) { "请一次确认一项操作" }
            val fn = calls.single().getJSONObject("function")
            proposal = JSONObject(fn.getString("arguments")).put("op",fn.getString("name").replace('_','.'))
        } else {
            requireNotNull(a) { "请先登录实例" }
            val answer = a.api("ai/actions",requestBody,stream)
            proposal = answer.optJSONObject("proposal") ?: return Turn(answer.getString("text"))
        }
        val binding = ActionConfirmation(scope,personal,a?.origin.orEmpty(),a?.user.orEmpty(),ids,operationId)
        val op = proposal.getString("op")
        val prompt = if (scope == "account") {
            require(op in accountOps)
            if (op == "invite.accept") {
                val userText = history.objects().filter { it.optString("role")=="user" }.map { it.getString("content") } + text
                val codes = userText.lastOrNull(invitation::containsMatchIn)?.let { invitation.findAll(it).toList() }
                proposal.put("token",requireNotNull(codes?.singleOrNull()) { "请粘贴一份邀请链接或邀请码" }.value)
            }
            when (op) {
                "group.create" -> "在当前账号创建团组「${proposal.getString("name")}」？"
                "calendar.create" -> "创建日历「${proposal.getString("name")}」？"
                else -> "用当前账号接受这份邀请？"
            }
        } else {
            val args = deviceArgs(op,proposal,binding)
            if (op == "sync.resolve") {
                val info = device.run("sync.detail",obj("calendarId" to args.getString("calendarId")))
                require(info.optString("revision") == args.getString("revision")) { "冲突已变化，请重新查询" }
                if (args.getString("choice") == "remote") "采用远端版本将放弃这本日历的全部 ${info.getInt("pendingCount")} 项本机草稿。"
                else { require(info.optBoolean("canRetryLocal")); "按当前远端版本重新提交本机改班。" }
            } else when (op) {
                "background.update" -> {
                    val target = if (args.has("widgetId")) "组件 " + args.getInt("widgetId") else "雾光主题"
                    val style = when (args.getString("mode")) {
                        "solid" -> "纯色 " + args.optString("color","（上次选择）")
                        "photo" -> "已选图片"
                        "follow" -> "跟随应用背景"
                        else -> "雾光"
                    }
                    "将${target}的背景设为${style}？"
                }
                "widgets.update" -> {
                    val kind = Widgets.installed(ctx)[args.getInt("widgetId")]
                    val names = args.getJSONArray("calendarIds").strings().map { db.rows().book(it)!!.name }
                    "将${if (kind == "month") "桌面月历" else "近期日程"}设为显示：${names.joinToString("、").ifEmpty { "不显示日历" }}？"
                }
                else -> "确认立即同步这本日历？"
            }
        }
        return Turn(prompt,proposal,action=binding)
    }

    private fun deviceArgs(op: String, proposal: JSONObject, binding: ActionConfirmation): JSONObject {
        require(op in phoneOps)
        selectedBooks(binding.selected)
        val args = proposal.copyJson().apply { remove("op"); remove("operationId") }
        if (op == "sync.resolve") args.put("operationId",binding.operationId)
        Contract.check(device.schemas.getJSONObject(op),args)
        if (op == "widgets.update") {
            require(args.getJSONArray("calendarIds").strings().all(binding.selected::contains) &&
                widgets(binding.selected).any { it.getInt("widgetId") == args.getInt("widgetId") }) { "组件或日历超出当前选区" }
        } else if (op == "background.update") {
            if (args.has("widgetId")) require(widgets(binding.selected).any {
                it.getInt("widgetId") == args.getInt("widgetId")
            }) { "组件超出当前选区" }
            else require(args.getString("mode") != "follow") { "仅组件可跟随应用背景" }
        } else require(args.getString("calendarId") in binding.selected) { "日历超出当前选区" }
        return args
    }

    fun confirm(turn: Turn): String {
        val binding = requireNotNull(turn.action)
        val a = Account.read(ctx)
        require(a?.user.orEmpty() == binding.user && a?.origin.orEmpty() == binding.origin) { "账号已切换，请重新发起对话" }
        val p = requireNotNull(turn.proposal).copyJson()
        val result = if (binding.scope == "account") {
            requireNotNull(a).api("ai/account/confirm",obj("userId" to a.user,"source" to
                (if (binding.personal) "personal" else "server"),"command" to p.put("operationId",binding.operationId)))
        } else {
            if (!binding.personal) require(requireNotNull(a).api("state").getJSONObject("ai").getBoolean("enabled")) { "模型授权已撤销" }
            val op = p.getString("op")
            device.run(op,deviceArgs(op,p,binding))
        }
        return "已执行 · " + result.toString()
    }
}
