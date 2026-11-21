package org.timepin.app

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.accessibility.AccessibilityNodeInfo as Node

class CollaborationTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val base get() = (instrumentation as InstrumentationTestRunner).arguments.getString("serverUrl")!!
    private val automation get() = instrumentation.uiAutomation

    private fun find(root: Node?, match: (Node) -> Boolean): Node? {
        if (root == null || match(root)) return root
        for (i in 0 until root.childCount) find(root.getChild(i), match)?.let { return it }
        return null
    }

    private fun node(label: String): Node {
        var forward = true
        repeat(70) { attempt ->
            val root = automation.rootInActiveWindow
            val found = find(root) {
                it.text?.toString() == label || it.contentDescription?.toString() == label ||
                    it.contentDescription?.toString()?.startsWith(label + "，") == true
            }
            if (found != null) return found
            if (root != null && attempt % 5 == 4) {
                val moved = find(root) { it.isScrollable }?.performAction(if (forward) Node.ACTION_SCROLL_FORWARD else Node.ACTION_SCROLL_BACKWARD)
                if (moved == false) forward = !forward
            }
            Thread.sleep(80)
        }
        error("页面缺少「$label」 (窗口 ${automation.rootInActiveWindow?.packageName})")
    }

    private fun click(label: String) {
        var p: Node? = node(label)
        while (p != null && !p.isClickable) p = p.parent
        check(p?.performAction(Node.ACTION_CLICK) == true) { "无法点击「$label」" }
    }

    private fun fill(hint: String, value: String) {
        val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(node(hint).performAction(Node.ACTION_SET_TEXT, args))
        click("确定")
    }

    private fun receiveError(code: Int, action: () -> Unit) {
        try {
            action()
            fail("预期 HTTP $code")
        } catch (e: HttpFailure) {
            assertEquals(code, e.status)
        }
    }

    private fun session(test: (MainActivity, Account, Account, String) -> Unit) {
        check(ctx.packageName.endsWith(".qa") && base.startsWith("http://127.0.0.1:"))
        check(Account.read(ctx) == null)
        val otherName = "Member" + uid().take(8)
        val other = request(base, "/api/v1/register", obj("name" to otherName, "password" to "qa-member-password"))
        val second = Account(base, other.body.getString("id"), other.cookie)
        val owner = Account.register(ctx, base, "Owner" + uid().take(8), "qa-owner-password", "")
        val db = Store.get(ctx)
        val autoImport = SystemCalendarWatcher.enabled(ctx)
        SystemCalendarWatcher.setEnabled(ctx, false)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var app: MainActivity? = null
        try {
            Sync(ctx, db, owner).all()
            val descriptor = automation.executeShellCommand("am start -W -f 0x10008000 -n " +
                ctx.packageName + "/org.timepin.app.MainActivity")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            val screen = instrumentation.waitForMonitorWithTimeout(monitor, 6000) as MainActivity
            app = screen
            test(screen, owner, second, otherName)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { app?.finish() }
            Sync.logout(ctx, db, owner)
            SystemCalendarWatcher.setEnabled(ctx, autoImport)
        }
    }

    fun testMemberSharing() = session { app, owner, member, memberName ->
        val cal = owner.api("state").getJSONArray("calendars").getJSONObject(0)
        val id = cal.getString("id")
        val group = owner.api("manage", obj("op" to "group.create", "name" to "协作小组", "operationId" to uid())).getString("id")
        owner.api("manage", obj("op" to "group.member", "target" to group, "subject" to memberName,
            "role" to "member", "operationId" to uid()))
        fun grantedRole() = member.api("state").getJSONArray("calendars").objects().find { it.getString("id") == id }?.getString("role")
        fun detail() = owner.api("manage", obj("op" to "calendar.detail", "target" to id))
        fun visit(token: String) = request(base, "/api/v1/public/$token?start=2026-11-01&end=2026-12-01&zone=UTC")
        instrumentation.runOnMainSync { app.page = "设置"; app.showSettings(SettingGroup.CALENDARS) }
        click("打开" + cal.getString("name") + "的日历设置")
        click("分享与成员权限")
        click("授权给账号")
        fill("对方的登录账号名", memberName)
        click("只读")
        node(memberName)
        assertEquals("reader", member.api("state").getJSONArray("calendars").objects().single { it.getString("id") == id }.getString("role"))
        receiveError(403) { member.api("manage", obj("op" to "calendar.detail", "target" to id)) }

        click("创建一次性邀请")
        node("加入后的角色")
        click("编辑全部日程")
        val url = visibleLink("invite")
        val invitation = detail().getJSONArray("invites").getJSONObject(0)
        val token = invitationCode(url, base)
        assertTrue(runCatching { invitationCode(url, "https://wrong.invalid") }.isFailure)
        member.api("manage", obj("op" to "invite.accept", "token" to token, "operationId" to uid()))
        receiveError(404) { member.api("manage", obj("op" to "invite.accept", "token" to token, "operationId" to uid())) }
        assertEquals("editor", invitation.getString("role"))
        click("关闭")

        click("开启只读链接")
        click("确认")
        val first = visibleLink("share").substringAfter("#share=")
        assertEquals(cal.getString("name"), visit(first).body.getString("name"))
        click("关闭")
        click("更换链接")
        click("确认")
        val next = visibleLink("share").substringAfter("#share=")
        assertFalse(first == next)
        receiveError(404) { visit(first) }
        click("关闭")
        click("关闭只读链接")
        click("确认")
        node("开启只读链接")
        receiveError(404) { visit(next) }
        click(memberName)
        click("移除此授权")
        click("确认")
        node("尚未单独授权给账号或团组")
        assertNull(grantedRole())
        click("授权给团组")
        click("协作小组")
        node("选择授予的角色")
        click("仅编辑自己的日程")
        node("协作小组")
        assertEquals("contributor", grantedRole())
    }

    private fun visibleLink(kind: String): String {
        val prefix = "$base/#$kind="
        repeat(70) {
            find(automation.rootInActiveWindow) { it.text?.startsWith(prefix) == true }?.let { return it.text.toString() }
            Thread.sleep(80)
        }
        error("未显示 $kind 链接")
    }

    fun testGroupManagement() = session { app, owner, member, memberName ->
        instrumentation.runOnMainSync { app.page = "设置"; app.settingsGroup = null; app.reload() }
        click("我的团组")
        click("创建团组")
        fill("团组名称", "晚班组")
        click("新建团组日历")
        fill("日历名称", "共同班次")
        node("共同班次")
        val group = owner.api("state").getJSONArray("groups").getJSONObject(0).getString("id")
        click("添加成员")
        fill("对方的登录账号名", memberName)
        click("普通成员")
        node(memberName)
        assertEquals("reader", member.api("state").getJSONArray("calendars").objects()
            .single { it.getString("ownerId") == group }.getString("role"))
        receiveError(403) { member.api("manage", obj("op" to "group.detail", "target" to group)) }
        click("创建一次性邀请")
        node("加入后的角色")
        click("普通成员")
        val token = invitationCode(visibleLink("invite"), base)
        click("关闭")
        click("普通成员邀请")
        click("确认")
        node("创建一次性邀请")
        receiveError(404) { member.api("manage", obj("op" to "invite.accept", "token" to token, "operationId" to uid())) }
        click("移交管理权")
        node("选择接任成员")
        click(memberName)
        click("确认")
        node("我的角色 · 普通成员")
        receiveError(403) { owner.api("manage", obj("op" to "group.detail", "target" to group)) }
        assertTrue(member.api("manage", obj("op" to "group.detail", "target" to group)).getJSONArray("members").length() == 2)
        val invite = member.api("manage", obj("op" to "calendar.invite", "target" to
            member.api("state").getJSONArray("calendars").objects().single { it.getString("ownerId") == member.user }.getString("id"),
            "role" to "reader", "operationId" to uid()))
        click("返回团组列表")
        click("接受邀请")
        fill("邀请链接或邀请码", base + "/#invite=" + invite.getString("token"))
        node("接受邀请")
        assertTrue(owner.api("state").getJSONArray("calendars").objects().any { it.getString("ownerId") == member.user })
    }
}
