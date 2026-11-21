package org.timepin.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import java.io.File
import org.json.JSONObject

class DraftRecoveryTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val db get() = Store.get(ctx)
    private val commands get() = DeviceCommands(ctx)

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(80)
        }
        fail(description)
    }

    private fun click(label: String) {
        waitFor("找不到按钮 $label") {
            // Exercise this application's own windows. An OEM lock screen or a
            // second package can occupy the system accessibility window.
            if (Build.VERSION.SDK_INT >= 29) {
                var clicked = false
                instrumentation.runOnMainSync {
                    fun find(v: View): View? {
                        if (v.contentDescription == label || (v as? TextView)?.text?.toString() == label) return v
                        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                        return null
                    }
                    for (window in WindowInspector.getGlobalWindowViews().asReversed()) {
                        var view = find(window)
                        while (view != null && !view.isClickable) view = view.parent as? View
                        if (view != null) { clicked = view.performClick(); break }
                    }
                }
                return@waitFor clicked
            }
            var node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    fun testOfflineRecoveryAndIsolation() {
        check(ctx.packageName == "org.timepin.app.qa")
        val vault = Vault(ctx)
        val prior = vault.get("account")
        val auto = SystemCalendarWatcher.enabled(ctx)
        val account = Account("http://127.0.0.1:1", uid(), "offline")
        val book = Book(uid(), "已断开的共享日历", "#60766A", origin = account.origin, user = account.user, role = "none")
        val secondUser = account.copy(user = uid())
        val secondInstance = account.copy(origin = "http://127.0.0.1:2")
        val others = listOf(secondUser, secondInstance).map { book.copy(id = uid(), origin = it.origin, user = it.user) }
        val prefix = "{\"title\":\""
        val input = prefix + "班".repeat(16383 - prefix.length) + "🌙第二天交接\"}"
        val output = File.createTempFile("saved-draft-", ".json", ctx.cacheDir)
        var screen: MainActivity? = null
        try {
            SystemCalendarWatcher.setEnabled(ctx, false)
            vault.set("account", account.json())
            val seq = db.atomic {
                db.rows().save(book)
                val first = db.rows().enqueue(Pending(book.id, input, "revoked", "old-server-receipt"))
                repeat(30) { db.rows().enqueue(Pending(book.id, "{}", "revoked")) }
                for (b in others) {
                    db.rows().save(b)
                    db.rows().enqueue(Pending(b.id, "{}", "revoked"))
                }
                first
            }
            val page = commands.run("drafts.list", obj())
            assertEquals(30, page.getJSONArray("drafts").length())
            val last = commands.run("drafts.list", obj("after" to page.getLong("next")))
            assertEquals(1, last.getJSONArray("drafts").length())
            assertFalse(last.has("next"))
            val detail = commands.run("drafts.detail", obj("seq" to seq))
            assertFalse(detail.has("resolution"))
            assertFalse(detail.toString().contains("old-server-receipt"))
            val chunk = detail.getString("input")
            assertFalse(chunk.last().isHighSurrogate())
            assertEquals(input, chunk + commands.run("drafts.detail", obj("seq" to seq,
                "offset" to detail.getInt("nextOffset"))).getString("input"))
            for (other in listOf(secondUser, secondInstance)) {
                vault.set("account", other.json())
                assertEquals(1, commands.run("drafts.list", obj()).getJSONArray("drafts").length())
                assertTrue(runCatching { commands.run("drafts.detail", obj("seq" to seq)) }.isFailure)
            }
            vault.set("account", null)
            assertTrue(runCatching { commands.run("drafts.list", obj()) }.isFailure)
            vault.set("account", account.json())
            assertFalse("空 QA 安装应仅含已撤权和其他身份的日历", db.accountBooks(ctx).any { it.role != "none" })
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            val activity = try {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -f 0x10008000 -n " + ctx.packageName + "/org.timepin.app.MainActivity")).use { it.readBytes() }
                instrumentation.waitForMonitorWithTimeout(monitor, 6000) as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            screen = activity
            instrumentation.runOnMainSync {
                activity.page = "设置"
                activity.settingsGroup = SettingGroup.CONNECTION
                activity.reload()
            }
            click("待提交与冲突")
            click(book.name + " · 授权已撤销  #" + seq)
            val monitorSave = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("application/json")
            }, ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))), true)
            try {
                click("保存草稿文件")
                waitFor("未取回完整原文") { output.readText() == input }
                assertEquals(1, monitorSave.hits)
            } finally { instrumentation.removeMonitor(monitorSave) }
            assertEquals(31, db.rows().queueCount(book.id))
            assertFalse(db.accountBooks(ctx).filter { it.role != "none" }.any { it.id == book.id })
        } finally {
            screen?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
            db.atomic {
                for (b in others + book) {
                    db.rows().queue(b.id).forEach { db.rows().dequeue(it.seq) }
                    db.rows().removeBook(b.id)
                }
            }
            vault.set("account", prior)
            SystemCalendarWatcher.setEnabled(ctx, auto)
            output.delete()
        }
    }

    fun testRevokeDuringOfflineEdit() {
        check(ctx.packageName == "org.timepin.app.qa")
        val url = (instrumentation as InstrumentationTestRunner).arguments.getString("serverUrl")!!
        require(url.startsWith("http://127.0.0.1:"))
        val password = "draft-qa-password"
        val ownerReply = request(url, "/api/v1/register", obj("name" to "Owner" + uid().take(8), "password" to password))
        val owner = Account(url, ownerReply.body.getString("id"), ownerReply.cookie)
        val cal = owner.api("state").getJSONArray("calendars").getJSONObject(0).getString("id")
        val name = "Writer" + uid().take(8)
        val writer = Account.register(ctx, url, name, password, "")
        val sync = Sync(ctx, db, writer)
        fun grant(role: String) = owner.api("manage", obj("op" to "calendar.grant", "target" to cal,
            "subjectKind" to "user", "subject" to name, "role" to role, "operationId" to uid()))
        fun create(id: String) = obj("op" to "event.create", "v" to 1, "operationId" to uid(), "calendarId" to cal,
            "eventId" to id, "event" to obj("title" to id, "span" to obj("kind" to "date", "start" to "2026-11-20", "end" to "2026-11-21")))
        try {
            grant("editor")
            requireOk(owner.api("commands", create("private-server-event")))
            sync.all()
            val b = db.rows().book(cal)!!
            assertNotNull(db.rows().entry(cal, "private-server-event"))
            val local = create("my-offline-event")
            requireOk(Commands(db).run(b, local))
            grant("remove")
            sync.all()
            assertEquals("none", db.rows().book(cal)!!.role)
            assertNull(db.rows().entry(cal, "private-server-event"))
            assertTrue(db.accountBooks(ctx).any { it.role != "none" && it.id != cal })
            val row = commands.run("drafts.list", obj()).getJSONArray("drafts").objects().single { it.getString("calendarId") == cal }
            assertEquals("revoked", row.getString("state"))
            val detail = commands.run("drafts.detail", obj("seq" to row.getLong("seq")))
            assertEquals(canonical(local), canonical(JSONObject(detail.getString("input"))))
            assertFalse(detail.has("resolution"))
            assertTrue(runCatching { commands.run("sync.run", obj("calendarId" to cal)) }.isFailure)
            grant("editor")
            sync.all()
            assertEquals("editor", db.rows().book(cal)!!.role)
            assertEquals("revoked", db.rows().queue(cal).single().state)
            val remote = owner.api("commands", obj("v" to 1, "op" to "event.get", "calendarId" to cal, "eventId" to "my-offline-event"))
            assertEquals("not_found", remote.getString("code"))
        } finally { Sync.logout(ctx, db, writer) }
    }
}
