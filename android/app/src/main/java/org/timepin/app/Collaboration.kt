package org.timepin.app

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId

private val calendarRoles = linkedMapOf(
    "reader" to "只读", "contributor" to "仅编辑自己的日程", "editor" to "编辑全部日程", "manager" to "管理日历",
)
private val teamRoles = linkedMapOf("member" to "普通成员", "admin" to "管理员")

internal fun invitationCode(input: String, vararg servers: String): String {
    val raw = input.trim()
    val uri = Uri.parse(raw)
    if (uri.scheme == null && !raw.contains('/')) return raw
    require(uri.scheme in listOf("https", "http") && uri.userInfo == null) { "请输入邀请码或 TimePin 邀请链接" }
    val site = URI.create(uri.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/'))
    require(servers.any { URI.create(it.trimEnd('/')) == site }) { "邀请链接的地址与当前服务器不同。请核对服务器，或粘贴邀请码。" }
    val fragment = Uri.Builder().encodedQuery(uri.encodedFragment).build()
    return (fragment.getQueryParameter("invite") ?: uri.getQueryParameter("invite"))
        ?.takeIf { it.isNotBlank() } ?: error("链接里没有成员邀请码")
}

internal fun MainActivity.openCollaboration(book: Book? = null) {
    val session = Account.read(this)
    if (session == null) {
        calendarDialog().setTitle("登录后参与共享").setMessage("日历邀请和团组由你连接的日历服务器管理。")
            .setNegativeButton("取消", null).setPositiveButton("账号与同步") { _, _ ->
                showSettings(SettingGroup.CONNECTION)
            }.show()
        return
    }
    if (book != null && (book.origin != session.origin || book.user != session.user ||
            book.role != "manager" || book.publication.isNotEmpty())) {
        message("只有已同步日历的管理者可以修改共享")
        return
    }
    CollaborationSheet(this, session, book).open()
}

private class CollaborationSheet(
    private val app: MainActivity,
    private val account: Account,
    private val book: Book?,
) {
    private lateinit var sheet: Dialog
    private lateinit var body: LinearLayout
    private var teamId: String? = null
    private var busy = false

    fun open() = app.detailSheet(if (book == null) "我的团组" else "日历分享") { box, dialog ->
        sheet = dialog
        body = app.column()
        box.addView(body)
        load()
    }

    private fun label(value: String) = body.addView(app.text(value, 14, app.muted).apply {
        setPadding(app.dp(8), app.dp(8), app.dp(8), app.dp(8))
    })

    private fun section(value: String) = body.addView(app.medium(value, 17).apply {
        setPadding(app.dp(6), app.dp(16), app.dp(6), app.dp(3))
    })

    private fun row(title: String, icon: Int, value: String = "", click: () -> Unit) {
        body.addView(app.settingRow(title, icon, app.green, value, action = click))
    }

    private fun <T> runRequest(task: () -> T, success: (T) -> Unit, retry: () -> Unit, syncAfterWrite: Boolean = false) {
        if (busy) return
        busy = true
        app.work(Store.net, {
            runCatching {
                check(Account.read(app) == account) { "账号已切换，请重新打开" }
                task()
            }
        }) { result ->
            busy = false
            if (Account.read(app) != account) {
                sheet.dismiss()
                return@work
            }
            if (syncAfterWrite && result.isSuccess) app.autoSync()
            if (!sheet.isShowing) return@work
            result.onSuccess(success).onFailure { e ->
                body.removeAllViews()
                val status = (e as? HttpFailure)?.status
                label(when (status) {
                    400 -> "服务器未接受操作，请核对账号、角色以及团组是否仍有管理员。"
                    401 -> "登录已过期，请重新登录。"
                    403 -> "当前账号没有管理权限。"
                    404 -> "邀请或目标已失效。"
                    409 -> "数据已变化，请刷新后重试。"
                    else -> e.message ?: "服务器暂时不可用"
                })
                if (e is IOException || (status ?: 0) >= 500) row("重试", R.drawable.ic_sync, click = retry)
                if (book == null) row("返回团组列表", R.drawable.ic_prev) { teamId = null; load() }
                else row("刷新分享设置", R.drawable.ic_sync) { load() }
            }
        }
    }

    private data class Snapshot(val state: JSONObject, val team: JSONObject?, val detail: JSONObject?)

    private fun load() {
        body.removeAllViews()
        label("正在读取服务器…")
        val selected = teamId
        runRequest({
            val state = account.api("state")
            val team = state.getJSONArray("groups").objects().find { it.getString("id") == selected }
            val detail = when {
                book != null -> account.api("manage", obj("op" to "calendar.detail", "target" to book.serverId))
                team?.getString("role") == "admin" -> account.api("manage", obj("op" to "group.detail", "target" to selected))
                else -> null
            }
            Snapshot(state, team, detail)
        }, { snapshot ->
            body.removeAllViews()
            val groups = snapshot.state.getJSONArray("groups").objects()
            if (book != null) {
                section(book.name)
                val detail = requireNotNull(snapshot.detail)
                publicShare(book.serverId, detail.getString("shareToken"))
                management("calendar", book.serverId, detail, groups)
            } else if (snapshot.team == null) {
                teamId = null
                groupList(groups)
            } else {
                groupPage(snapshot, groups)
            }
        }, ::load)
    }

    private fun write(op: String, args: JSONObject, receipt: (JSONObject) -> Unit = {}) {
        if (busy) return
        args.put("op", op).put("operationId", uid())
        fun send() {
            body.removeAllViews()
            label("正在提交…")
            runRequest({ account.api("manage", args) }, { result ->
                receipt(result)
                load()
            }, ::send, syncAfterWrite = true)
        }
        send()
    }

    private fun input(title: String, hint: String, submit: (String) -> Unit) {
        val box = app.padded()
        val field = app.field(hint)
        box.addView(field)
        app.form(title, box, "确定") { dialog ->
            val value = field.text.toString().trim()
            require(value.isNotEmpty()) { "请填写$hint" }
            submit(value)
            dialog.dismiss()
        }
    }

    private fun choose(title: String, options: Map<String, String>, select: (String) -> Unit) {
        val keys = options.keys.toList()
        app.calendarDialog().setTitle(title).setItems(options.values.toTypedArray()) { _, i ->
            select(keys[i])
        }.setNegativeButton("取消", null).show()
    }

    private fun confirm(title: String, note: String, submit: () -> Unit) {
        app.calendarDialog().setTitle(title).setMessage(note).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> submit() }.show()
    }

    private fun groupList(groups: List<JSONObject>) {
        row("创建团组", R.drawable.ic_people) {
            input("创建团组", "团组名称") { name ->
                write("group.create", obj("name" to name)) { teamId = it.getString("id") }
            }
        }
        row("接受邀请", R.drawable.ic_key) {
            input("接受日历或团组邀请", "邀请链接或邀请码") { value ->
                val token = invitationCode(value, account.endpoint, account.origin)
                write("invite.accept", obj("token" to token)) { reply ->
                    if (reply.getString("kind") == "group") teamId = reply.getString("id")
                    app.message(if (teamId == null) "已加入日历" else "已加入团组")
                }
            }
        }
        section("已加入的团组")
        if (groups.isEmpty()) label("还没有加入团组")
        for (team in groups) {
            row(team.getString("name"), R.drawable.ic_people, teamRoles.getValue(team.getString("role"))) {
                teamId = team.getString("id")
                load()
            }
        }
    }

    private fun groupPage(snapshot: Snapshot, groups: List<JSONObject>) {
        val team = requireNotNull(snapshot.team)
        val target = team.getString("id")
        val detail = snapshot.detail
        row("返回团组列表", R.drawable.ic_prev) { teamId = null; load() }
        section(team.getString("name"))
        label("我的角色 · " + teamRoles.getValue(team.getString("role")))
        section("团组日历")
        val calendars = snapshot.state.getJSONArray("calendars").objects().filter {
            it.getString("ownerKind") == "group" && it.getString("ownerId") == target
        }
        if (calendars.isEmpty()) label("这个团组还没有日历")
        calendars.forEach { label(it.getString("name")) }
        if (detail != null) {
            row("新建团组日历", R.drawable.ic_calendar) {
                input("新建团组日历", "日历名称") { name ->
                    write("calendar.create", obj("name" to name, "team" to target, "color" to "#278B77"))
                }
            }
            management("group", target, detail, groups)
        }
    }

    private fun management(kind: String, target: String, detail: JSONObject, groups: List<JSONObject>) {
        val team = kind == "group"
        val roles = if (team) teamRoles else calendarRoles
        val members = detail.getJSONArray("members").objects()
        val memberOp = if (team) "group.member" else "calendar.grant"
        fun change(subjectKind: String, subject: String, role: String) =
            write(memberOp, obj("target" to target, "subjectKind" to subjectKind, "subject" to subject, "role" to role))
        fun assign(subjectKind: String, subject: String) =
            choose("选择授予的角色", roles) { role -> change(subjectKind, subject, role) }

        section(if (team) "团组成员" else "成员权限")
        if (!team && members.isEmpty()) label("尚未单独授权给账号或团组")
        row(if (team) "添加成员" else "授权给账号", R.drawable.ic_profile) {
            input("添加账号", "对方的登录账号名") { name -> assign("user", name) }
        }
        if (!team && groups.isNotEmpty()) row("授权给团组", R.drawable.ic_people) {
            choose("选择团组", groups.associate { it.getString("id") to it.getString("name") }) { assign("group", it) }
        }
        val admins = if (team) members.count { it.getString("role") == "admin" } else 0
        for (member in members) {
            val name = member.getString("name")
            val type = member.getString("kind")
            val subject = if (type == "group") member.getString("id") else name
            val lastAdmin = admins == 1 && member.getString("role") == "admin"
            row(name, if (type == "group") R.drawable.ic_people else R.drawable.ic_profile, roles.getValue(member.getString("role"))) {
                if (lastAdmin) app.message("团组需要保留一位管理员，可先移交管理权")
                else choose(name, roles + ("remove" to if (team) "移除成员" else "移除此授权")) { role ->
                    if (role == "remove") confirm("移除「$name」？", "将撤销这项授权。") { change(type, subject, role) }
                    else change(type, subject, role)
                }
            }
        }
        val successors = members.filter { it.getString("id") != account.user }
        if (team && successors.isNotEmpty()) row("移交管理权", R.drawable.ic_key) {
            choose("选择接任成员", successors.associate { it.getString("id") to it.getString("name") }) { id ->
                val name = successors.first { it.getString("id") == id }.getString("name")
                confirm("移交给「$name」？", "对方将成为团组管理员，你将成为普通成员。") {
                    write("group.transfer", obj("target" to target, "subject" to name))
                }
            }
        }

        section("成员邀请")
        row("创建一次性邀请", R.drawable.ic_key) {
            choose("加入后的角色", roles) { role ->
                write("$kind.invite", obj("target" to target, "role" to role)) {
                    link("成员邀请 · " + roles.getValue(role), "invite", it.getString("token"))
                }
            }
        }
        for (invite in detail.getJSONArray("invites").objects()) {
            val expires = Instant.ofEpochSecond(invite.getLong("expires")).atZone(ZoneId.systemDefault()).toLocalDate()
            row(roles.getValue(invite.getString("role")) + "邀请", R.drawable.ic_clock, "$expires 到期") {
                confirm("撤销邀请？", "该邀请链接将立即失效。") {
                    write("$kind.uninvite", obj("target" to target, "inviteId" to invite.getString("id")))
                }
            }
        }
    }

    private fun publicShare(target: String, token: String) {
        section("免登录只读链接")
        fun enable() = write("calendar.share", obj("target" to target, "enabled" to true)) {
            link("只读日历链接", "share", it.getString("token"))
        }
        if (token.isEmpty()) row("开启只读链接", R.drawable.ic_key) {
            confirm("开启只读链接？", "拿到链接的人可以查看这本日历的日程。") { enable() }
        } else {
            row("分享只读链接", R.drawable.ic_calendar) { link("只读日历链接", "share", token) }
            row("更换链接", R.drawable.ic_sync) {
                confirm("更换只读链接？", "旧链接将立即失效。") { enable() }
            }
            row("关闭只读链接", R.drawable.ic_key) {
                confirm("关闭只读链接？", "已经分享出去的链接将立即失效。") {
                    write("calendar.share", obj("target" to target, "enabled" to false))
                }
            }
        }
    }

    private fun link(title: String, kind: String, token: String) {
        val url = account.endpoint.trimEnd('/') + "/#" + kind + "=" + Uri.encode(token)
        val box = app.padded()
        if (kind == "invite") box.addView(app.text("仅可使用一次，7 天内有效", 14, app.muted))
        box.addView(app.text(url, 15, app.ink).apply { setTextIsSelectable(true) })
        app.calendarDialog().setTitle(title).setView(box).setNegativeButton("关闭", null)
            .setNeutralButton("复制链接") { _, _ ->
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, url))
                app.message("已复制链接")
            }.setPositiveButton("分享…") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                app.startActivity(Intent.createChooser(intent, title))
            }.show()
    }
}
