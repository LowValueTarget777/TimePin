package org.timepin.app

import android.animation.ValueAnimator
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.RemoteViews
import java.io.File
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class AppearanceTest : InstrumentationTestCase() {
    private val ctx
        get() = instrumentation.targetContext

    private val db
        get() = Store.get(ctx)

    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun waitFor(what: String, detail: () -> String = { "" }, ready: () -> Boolean) {
        repeat(120) {
            if (ui(ready)) return
            Thread.sleep(75)
        }
        fail(what + detail())
    }

    private fun find(view: View, label: String): View? {
        if (view.contentDescription == label || (view is TextView && view.text.toString() == label))
            return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let {
                return it
            }
        return null
    }

    private fun capture(view: View, name: String) {
        if (
            (instrumentation as InstrumentationTestRunner).arguments.getString("snapshots") != "yes"
        )
            return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File(ctx.filesDir, name + ".png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sample(): Book {
        check(ctx.packageName.endsWith(".qa"))
        val b = Book("mobile-visual-sample", "工作班表", "#248365")
        db.rows().save(b)
        val first = LocalDate.now().withDayOfMonth(1)
        val today = LocalDate.now()
        for (i in 0 until first.lengthOfMonth() + 7) {
            val date = first.plusDays(i.toLong())
            val kind = Math.floorMod((date.toEpochDay() - today.toEpochDay()).toInt(), 5)
            val title = listOf("门店早班", "盘点交接", "休息", "培训", "晚班")[kind]
            val color = listOf("#358467", "#476DAD", "#7E72AD", "#B4773A", "#44847E")[kind]
            val span =
                if (kind == 2)
                    obj(
                        "kind" to "date",
                        "start" to date.toString(),
                        "end" to date.plusDays(1).toString(),
                    )
                else {
                    val hour = listOf(9, 12, 0, 10, 14)[kind]
                    obj(
                        "kind" to "timed",
                        "zone" to "Asia/Shanghai",
                        "start" to "%sT%02d:00:00+08:00".format(date, hour),
                        "end" to "%sT%02d:00:00+08:00".format(date, hour + 8),
                    )
                }
            if (db.rows().entry(b.id, "day-" + i) == null)
                requireOk(
                    Commands(db)
                        .run(
                            b,
                            obj(
                                "v" to 1,
                                "op" to "event.create",
                                "calendarId" to b.id,
                                "operationId" to uid(),
                                "eventId" to "day-" + i,
                                "event" to obj("title" to title, "color" to color, "span" to span),
                            ),
                        )
                )
        }
        return b
    }

    private fun launch(extras: String = ""): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val launch =
            instrumentation.uiAutomation.executeShellCommand(
                "am start --activity-clear-task -n " + ctx.packageName + "/org.timepin.app.MainActivity " + extras
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        return try {
            instrumentation.waitForMonitorWithTimeout(monitor, 6000) as? MainActivity
                ?: error("日历未启动")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun dialogItem(label: String): AccessibilityNodeInfo {
        repeat(40) {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label }
                ?.let {
                    return it
                }
            Thread.sleep(75)
        }
        error("弹窗缺少「" + label + "」")
    }

    fun testCalendarDirectoryAvailability() {
        check(ctx.packageName.endsWith(".qa"))
        val vault = Vault(ctx)
        val previousAccount = vault.get("account")
        val autoImport = SystemCalendarWatcher.enabled(ctx)
        val offline = Account("http://127.0.0.1:1", "qa-directory", "offline")
        val local = Book("mobile-" + uid(), "我的离线排班", "#227755")
        val shared = local.copy(id = uid(), name = "只读排班", origin = offline.origin, user = offline.user,
            role = "reader", visible = false)
        val pending = shared.copy(id = uid(), name = "待同步排班", role = "manager", publication = "{}")
        val detached = shared.copy(id = uid(), name = "已断开的共享日历", role = "none")
        val fixtures = listOf(local, shared, pending, detached)
        var activity: MainActivity? = null
        try {
            SystemCalendarWatcher.setEnabled(ctx, false)
            vault.set("account", offline.json())
            val existing = db.accountBooks(ctx).count { it.role != "none" }
            fixtures.forEach { db.rows().save(it) }
            val a = launch()
            activity = a
            waitFor("月历载入本机日历") { a.books.any { it.id == local.id } }
            ui {
                a.page = "设置"
                a.active = detached.id
                a.reload()
            }
            waitFor("设置卡片只统计可访问的日历") {
                a.active != detached.id && find(a.root, "管理日历  ·  ${existing + 3} 本") != null
            }
            ui { find(a.root, "管理日历，" + a.book().name)!!.performClick() }
            fun row(book: Book) = find(a.root, "打开${book.name}的日历设置")
            waitFor("目录显示本机、隐藏的只读日历和待同步日历") {
                find(a.root, "共 ${existing + 3} 本") != null &&
                    listOf(local, shared, pending).all { row(it) != null } && row(detached) == null
            }
            db.rows().save(shared.copy(role = "none"))
            ui { a.reload() }
            waitFor("撤权后条目和数量同时更新") {
                find(a.root, "共 ${existing + 2} 本") != null && row(shared) == null
            }
            db.rows().save(shared)
            ui { a.reload() }
            waitFor("重新授权后日历重新出现") {
                find(a.root, "共 ${existing + 3} 本") != null && row(shared) != null
            }
            assertEquals(detached, db.rows().book(detached.id))
        } finally {
            activity?.let { ui { it.finish() } }
            instrumentation.waitForIdleSync()
            fixtures.forEach { db.rows().removeBook(it.id) }
            vault.set("account", previousAccount)
            SystemCalendarWatcher.setEnabled(ctx, autoImport)
        }
    }

    fun testAssistantCalendarSelection() {
        check(ctx.packageName.endsWith(".qa"))
        val one=Book("qa-selector-a","选择验收甲","#227755")
        val two=Book("qa-selector-b","选择验收乙","#557799")
        db.rows().save(one)
        db.rows().save(two)
        val a=launch("-a android.intent.action.MAIN")
        try {
            ui { a.prefs.edit().putBoolean("personal",true).apply(); a.page="日历助手";a.reload() }
            fun check(label:String)=find(a.window.decorView,label) as? android.widget.CheckBox
            waitFor("助手默认勾选全部日历"){check("全部日历")?.isChecked==true && check(two.name)?.isChecked==true}
            ui { check("全部日历")!!.performClick() }
            waitFor("空选择暂停发送"){find(a.root,"发送")?.isEnabled==false}
            ui { check(two.name)!!.performClick() }
            waitFor("只选指定日历"){check(two.name)?.isChecked==true && check(one.name)?.isChecked==false && find(a.root,"发送")?.isEnabled==true}
            ui { a.onBackPressed() }
            waitFor("收回助手"){a.page=="月历"}
        } finally {ui {a.finish()}}
    }

    fun testNewEventDefaultsToAllDay() {
        check(ctx.packageName.endsWith(".qa"))
        val book = Book("qa-editor-" + uid(), "全天验收", "#338866")
        val day = LocalDate.of(2040, 12, 31)
        db.rows().save(book)
        val activity = launch()
        try {
            waitFor("测试日历加载") { activity.books.any { it.id == book.id } }
            ui { activity.day = day; activity.editEvent(book.id) }
            val all = dialogItem("全天")
            assertTrue(all.isChecked)
            assertTrue(all.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertFalse(dialogItem("全天").isChecked)
            dialogItem("09:00")
            assertTrue(dialogItem("全天").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val title = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "跨年全天")
            }
            val fields = dialogItem("全天").parent
            val titleField = (0 until fields.childCount).mapNotNull(fields::getChild)
                .single { it.className == "android.widget.EditText" }
            assertTrue(titleField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, title))
            assertTrue(dialogItem("保存").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            for (attempt in 0 until 80) {
                val entry = db.rows().live(book.id).singleOrNull()
                if (entry != null) {
                    val span = JSONObject(entry.json).getJSONObject("event").getJSONObject("span")
                    assertEquals("date", span.getString("kind"))
                    assertEquals(day.toString(), span.getString("start"))
                    assertEquals(day.plusDays(1).toString(), span.getString("end"))
                    return
                }
                Thread.sleep(75)
            }
            fail("全天事件未保存")
        } finally {
            ui { activity.finish() }
            DeviceCommands(ctx).run("calendar.delete", obj("calendarId" to book.id, "operationId" to uid()))
        }
    }

    fun testEventColorAndZone() {
        check(ctx.packageName.endsWith(".qa"))
        val book = Book("qa-editor", "编辑验收", "#338866")
        val id = uid()
        db.rows().save(book)
        requireOk(Commands(db).run(book, obj("v" to 1, "op" to "event.create", "eventId" to id,
            "calendarId" to book.id, "operationId" to uid(), "event" to obj("title" to "时区验收",
                "span" to obj("kind" to "timed", "zone" to "Asia/Shanghai",
                    "start" to "2041-08-09T09:00:00+08:00", "end" to "2041-08-09T10:00:00+08:00")))))
        val activity = launch()
        fun click(label: String) {
            var node = dialogItem(label)
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            waitFor("测试日历加载") { activity.books.any { it.id == book.id } }
            ui { activity.editEvent(book.id, id) }
            assertFalse(dialogItem("全天").isChecked)
            click("Asia/Shanghai")
            click("UTC")
            click("自定义颜色")
            val value = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#123ABC") }
            assertTrue(dialogItem(book.color).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value))
            click("使用")
            dialogItem("自定义颜色 · #123ABC")
            click("保存")
            for (attempt in 0 until 80) {
                val event = JSONObject(db.rows().entry(book.id, id)!!.json).getJSONObject("event")
                if (event.optString("color") == "#123ABC") {
                    assertEquals("UTC",event.getJSONObject("span").getString("zone"))
                    return
                }
                Thread.sleep(75)
            }
            fail("时区与自定义颜色未保存")
        } finally {
            ui { activity.finish() }
            DeviceCommands(ctx).run("calendar.delete", obj("calendarId" to book.id, "operationId" to uid()))
        }
    }

    fun testDateSelectionAndNavigation() {
        check(ctx.packageName.endsWith(".qa"))
        val book = Book("mobile-interaction", "交互验收", "#248365")
        db.rows().save(book)
        val empty = LocalDate.of(2033, 4, 6)
        val occupied = empty.plusDays(1)
        val shift = "交互验收班次"
        if (db.rows().entry(book.id, "shift") == null)
            requireOk(
                Commands(db)
                    .run(
                        book,
                        obj(
                            "v" to 1,
                            "op" to "event.create",
                            "calendarId" to book.id,
                            "operationId" to uid(),
                            "eventId" to "shift",
                            "event" to
                                obj(
                                    "title" to shift,
                                    "span" to
                                        obj(
                                            "kind" to "date",
                                            "start" to occupied.toString(),
                                            "end" to occupied.plusDays(1).toString(),
                                        ),
                                ),
                        ),
                    )
            )
        // A widget can look empty even when an additional app-visible calendar has an event.
        val a = launch("-a org.timepin.OPEN --es day " + occupied + " --ez selectOnly true")
        fun cell(date: LocalDate) = find(a.root, date.toString())!!.parent as View
        fun pageOf(label: String): View {
            var v = find(a.root, label)!!
            while (v.parent !is PageHost) v = v.parent as View
            return v
        }
        fun close(label: String) {
            assertTrue(dialogItem(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("弹窗没有关闭") { a.hasWindowFocus() }
        }
        try {
            waitFor("桌面空日期应直接定位") {
                a.books.any { it.id == book.id } && a.hasWindowFocus() && cell(occupied).isSelected
            }
            ui {
                val previous = cell(occupied)
                val next = cell(empty)
                next.performClick()
                next.performClick()
                assertTrue(next.isSelected)
                assertFalse(previous.isSelected)
                assertSame(next, cell(empty))
                assertEquals(empty, a.day)
            }
            instrumentation.waitForIdleSync()
            assertTrue("空日期弹出了窗口", ui { a.hasWindowFocus() })
            ui { find(a.root, "新增日程")!!.performClick() }
            if (ui { a.books.count { it.role != "none" && it.role != "reader" } > 1 }) {
                var option = dialogItem(book.name)
                while (!option.isClickable) option = option.parent
                assertTrue(option.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            dialogItem(empty.toString())
            close("取消")
            ui { cell(occupied).performClick() }
            dialogItem("全天   " + shift)
            close("关闭")
            ui { find(a.root, shift)!!.performClick() }
            dialogItem("编辑日程")
            close("取消")

            ui {
                find(a.root, "下个月")!!.performClick()
                find(a.root, "下个月")!!.performClick()
                find(a.root, "上个月")!!.performClick()
            }
            waitFor("连续翻月后日期错误") { find(a.root, "2033-05-15") != null }
            ui { find(a.root, book.name)!!.performClick() }
            waitFor("日历未隐藏") { !a.books.single { it.id == book.id }.visible }
            ui { find(a.root, "上个月")!!.performClick() }
            waitFor("没有返回四月") { find(a.root, empty.toString()) != null }
            ui {
                assertNull(find(a.root, shift))
                cell(occupied).performClick()
            }
            instrumentation.waitForIdleSync()
            assertTrue("隐藏日历后仍弹出旧班次", ui { a.hasWindowFocus() })
            ui { find(a.root, book.name)!!.performClick() }
            waitFor("重新显示日历后缺少班次") { find(a.root, shift) != null }
            ui { find(a.root, "设置")!!.performClick() }
            waitFor("未进入设置") { find(a.root, "外观") != null }
            ui {
                val animate = ValueAnimator.areAnimatorsEnabled()
                val expected =
                    (instrumentation as InstrumentationTestRunner).arguments.getString("motion")
                if (expected != null) assertEquals(expected == "on", animate)
                find(a.root, "导入日历")!!.performClick()
                val detail = pageOf("识别手机系统日历")
                assertEquals(animate, detail.translationX > 0f)
                a.onBackPressed()
                val home = pageOf("外观")
                assertEquals(animate, home.translationX < 0f)
                assertEquals(1, (home.parent as ViewGroup).childCount)
            }
            waitFor("返回动画没有结束") {
                val home = pageOf("外观")
                home.translationX == 0f && home.alpha == 1f
            }
        } finally {
            ui { a.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    fun testMonthOptionsInAppAndWidget() {
        val old = MonthOptions.read(ctx)
        val options = MonthOptions("monday", false, true, "CN", 18)
        val host = ui { AppWidgetHost(ctx, 609) }
        val manager = AppWidgetManager.getInstance(ctx)
        try {
            MonthOptions.save(ctx, options)
            val a = launch("-a org.timepin.OPEN --es day 2026-02-17 --ez selectOnly true")
            try {
                waitFor("春节与农历未显示") { a.books.isNotEmpty() && find(a.root, "春节 · 正月") != null }
                ui {
                    val number = find(a.root, "2026-02-17") as TextView
                    assertEquals(
                        18f,
                        number.textSize / ctx.resources.displayMetrics.scaledDensity,
                        0.1f,
                    )
                    val weekdays = find(a.root, "周一")!!.parent as ViewGroup
                    assertEquals(7, weekdays.childCount)
                    assertEquals("周一", (weekdays.getChildAt(0) as TextView).text.toString())
                }
            } finally {
                ui { a.finish() }
                instrumentation.waitForIdleSync()
            }
            ui { host.startListening() }
            val id = ui { host.allocateAppWidgetId() }
            val component = ComponentName(ctx, MonthWidget::class.java)
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, component))
            val delta =
                java.time.temporal.ChronoUnit.MONTHS.between(
                        java.time.YearMonth.now(),
                        java.time.YearMonth.of(2026, 2),
                    )
                    .toInt()
            Widgets.move(ctx, id, delta)
            manager.updateAppWidgetOptions(
                id,
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 265)
                },
            )
            Store.io.submit { Widgets.render(ctx, id, true) }.get()
            val widget = ui { host.createView(ctx, id, manager.getAppWidgetInfo(id)) }
            waitFor("组件缺少节假日与农历") { find(widget, "2026-02-17，春节 · 正月") != null }
            ui {
                val header = widget.findViewById<ViewGroup>(R.id.items).getChildAt(0)
                assertEquals("一", header.findViewById<TextView>(R.id.d0).text.toString())
                assertEquals(View.GONE, header.findViewById<View>(R.id.week_number).visibility)
            }
            MonthOptions.save(ctx, options.copy(lunarFormat = "numeric"))
            Store.io.submit { Widgets.render(ctx, id, true) }.get()
            waitFor("桌面月历未切换到数字农历") { find(widget, "2026-02-17，春节 · 1/1") != null }
        } finally {
            MonthOptions.save(ctx, old)
            ui {
                host.stopListening()
                host.deleteHost()
            }
        }
    }

    fun testDragResizeAndAllDayShiftTitle() = verifyShiftSizes(reconcileOnOpen = false)

    fun testReconcileResizeAndAllDayShiftTitle() = verifyShiftSizes(reconcileOnOpen = true)

    private fun verifyShiftSizes(reconcileOnOpen: Boolean) {
        check(ctx.packageName.endsWith(".qa"))
        val book = Book("mobile-" + uid(), "组件缩放验收", "#278B77")
        val today = LocalDate.now()
        val title = "12:00-21:00"
        class TrackedView(c: Context) : AppWidgetHostView(c) {
            var updates = 0
            override fun updateAppWidget(views: RemoteViews?) {
                super.updateAppWidget(views)
                updates++
            }
        }
        val host = ui { object : AppWidgetHost(ctx, 641) {
            override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView = TrackedView(c)
        } }
        val manager = AppWidgetManager.getInstance(ctx)
        val display = MonthOptions.read(ctx)
        try {
            MonthOptions.save(ctx, MonthOptions(weekNumbers = true, holidayRegion = "off", fontSize = 14))
            db.rows().save(book)
            val span = obj("kind" to "date", "start" to today.toString(), "end" to today.plusDays(1).toString())
            val row = obj("externalId" to "shift", "event" to obj("title" to title, "span" to span))
            requireOk(Commands(db).run(book, obj("v" to 1, "op" to "import.commit", "calendarId" to book.id,
                "operationId" to uid(), "batch" to obj("source" to "shifts", "rows" to org.json.JSONArray().put(row)))))
            ui { host.startListening() }
            for (month in listOf(true, false)) {
                val component = ComponentName(ctx, if (month) MonthWidget::class.java else AgendaWidget::class.java)
                val id = ui { host.allocateAppWidgetId() }
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, component))
                val info = manager.getAppWidgetInfo(id)
                assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, info.resizeMode)
                Widgets.save(ctx, id, setOf(book.id))
                val view = ui { host.createView(ctx, id, info) as TrackedView }
                val sizes = if (month) listOf(520 to 300, 340 to 265, 250 to 210, 210 to 425, 128 to 104, 520 to 300)
                    else listOf(340 to 220, 110 to 68, 340 to 220)
                for ((w, h) in sizes) {
                    val before = ui { view.updates }
                    manager.updateAppWidgetOptions(id, Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                    })
                    if (reconcileOnOpen) Store.io.submit { Widgets.reconcile(ctx) }.get()
                    var pending = "组件宿主未送达更新"
                    waitFor("组件未在 ${w}×${h} 更新全天班次布局", { "（$pending）" }) {
                        if (view.updates == before) return@waitFor false
                        val density = ctx.resources.displayMetrics.density
                        val width = (w * density).toInt()
                        val height = (h * density).toInt()
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                        val focus = view.findViewById<TextView>(R.id.focus_title)
                        val label = if (!month) view.findViewById<TextView>(R.id.line)
                            else if (focus?.visibility == View.VISIBLE) focus
                            else find(view, "$today，$title") as? TextView
                        if (label == null || label.visibility != View.VISIBLE || label.layout == null) return@waitFor false
                        val text = label.text.toString()
                        if (!text.replace("\n", "").contains(title)) return@waitFor false
                        if (month && w == 210 && text.count { it == '\n' } != 2) return@waitFor false
                        if (month && w == 520 && text.count { it == '\n' } != 1) return@waitFor false
                        if (!month && (view.findViewById<View>(R.id.agenda_time)?.visibility == View.GONE) != (h == 68)) return@waitFor false
                        // A resize can deliver the old RemoteViews and its replacement in successive frames.
                        // Measure and inspect the same attached hierarchy within this UI turn.
                        val layout = label.layout
                        for (line in 0 until layout.lineCount) {
                            if (layout.getEllipsisCount(line) > 0 || layout.getLineWidth(line) > label.width + 1) {
                                pending = "第 ${line + 1} 行被省略或超出格宽 ${label.width}"
                                return@waitFor false
                            }
                        }
                        val last = layout.lineCount - 1
                        if (layout.getLineBottom(last) > label.height + 1 || text.length != layout.getLineEnd(last)) {
                            pending = "标题高度 ${layout.getLineBottom(last)}，可用 ${label.height}，格宽 ${label.width}，字号 ${label.textSize}，各行 " +
                                (0..last).map { text.substring(layout.getLineStart(it),layout.getLineEnd(it)).trimEnd() } +
                                "，标题自然宽度 " + android.text.Layout.getDesiredWidth(label.text, text.indexOf('\n') + 1, text.length, label.paint)
                            return@waitFor false
                        }
                        var bottom = label.totalPaddingTop + layout.getLineBottom(last)
                        var child: View = label
                        while (child !== view) {
                            bottom += child.top
                            val parent = child.parent as ViewGroup
                            if (bottom > parent.height + 1) {
                                pending = "标题底部 $bottom 超出父布局 ${parent.height}"
                                return@waitFor false
                            }
                            child = parent
                        }
                        true
                    }
                    assertEquals(setOf(book.id), Widgets.selected(ctx, id))
                }
            }
        } finally {
            ui { host.stopListening(); host.deleteHost() }
            MonthOptions.save(ctx, display)
            DeviceCommands(ctx).run("calendar.delete", obj("calendarId" to book.id, "operationId" to uid()))
        }
    }

    fun testThemeBackAndColorPicker() {
        check(ctx.packageName.endsWith(".qa"))
        val prefs = ctx.getSharedPreferences("display",0)
        val backdrops = ctx.getSharedPreferences("widgets",0)
        val previousTheme = prefs.getString("theme",null)
        val previousMode = backdrops.getString("back-mode-0",null)
        val previousColor = backdrops.getString("back-color-0",null)
        fun open(): BackdropActivity {
            val intent = android.content.Intent(ctx,BackdropActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val screen = instrumentation.startActivitySync(intent) as BackdropActivity
            waitFor("背景编辑器未显示") { screen.window.decorView.width > 0 }
            return screen
        }
        var screen: BackdropActivity? = null
        try {
            prefs.edit().remove("theme").commit()
            backdrops.edit().remove("back-mode-0").remove("back-color-0").commit()
            assertEquals("frost",ctx.calendarMode())
            assertEquals("solid",Backdrops.read(ctx).mode)
            assertEquals("#DCE7F4",Backdrops.read(ctx).color)
            for (theme in listOf("frost","light","dark")) {
                prefs.edit().putString("theme",theme).commit()
                val a = open()
                screen = a
                val root = a.window.decorView
                ui {
                    val back = find(root,"返回") as android.widget.ImageButton
                    assertTrue(back.background is android.graphics.drawable.GradientDrawable)
                    assertTrue(back.foreground is android.graphics.drawable.RippleDrawable)
                    assertTrue(back.width / ctx.resources.displayMetrics.density >= 48)
                    val code = find(root,"自定义颜色") as android.widget.EditText
                    assertEquals("#DCE7F4",code.text.toString())
                    if (theme == "frost") capture(root, "android-frost-back")
                    find(root,"丁香")!!.performClick()
                    val red = find(root,"红色") as? android.widget.SeekBar ?:
                        (find(root,"红色")!!.parent as ViewGroup).getChildAt(1) as android.widget.SeekBar
                    val start = android.os.SystemClock.uptimeMillis()
                    for ((type, position) in listOf(android.view.MotionEvent.ACTION_DOWN to .85f,
                        android.view.MotionEvent.ACTION_MOVE to .3f, android.view.MotionEvent.ACTION_UP to .3f)) {
                        val event = android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),type,
                            red.width * position, red.height / 2f, 0)
                        try { red.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    assertTrue(code.text.toString().matches(Regex("#[0-9A-F]{2}C9EB")))
                    assertTrue(code.text.toString() != "#D8C9EB")
                    code.setText("#12")
                    assertFalse(find(root,"保存背景")!!.isEnabled)
                    code.setText("#124EAC")
                    assertEquals(18,red.progress)
                    if (theme == "dark") find(root,"保存背景")!!.performClick() else back.performClick()
                }
                waitFor("未关闭背景编辑器") { a.isFinishing || a.isDestroyed }
                assertEquals(if (theme == "dark") "#124EAC" else "#DCE7F4",Backdrops.read(ctx).color)
            }
        } finally {
            ui { screen?.finish() }
            prefs.edit().putString("theme",previousTheme).commit()
            backdrops.edit().putString("back-mode-0",previousMode).putString("back-color-0",previousColor).commit()
        }
    }

    fun testWidgetBackdropIsolation() {
        check(ctx.packageName.endsWith(".qa"))
        val monthId = 910401
        val agendaId = 910402
        try {
            assertEquals("follow",Backdrops.read(ctx,monthId).mode)
            val photo = Bitmap.createBitmap(40,20,Bitmap.Config.ARGB_8888)
            photo.eraseColor(android.graphics.Color.MAGENTA)
            val data = android.util.Base64.encodeToString(Backdrops.encode(photo),android.util.Base64.NO_WRAP)
            Backdrops.save(ctx,monthId,obj("mode" to "photo","image" to data))
            val source = Backdrops.read(ctx,monthId)
            assertTrue(source.photo.isNotEmpty())
            Backdrops.save(ctx,agendaId,obj("mode" to "solid","color" to "#294658"))
            val solid = Backdrops.art(ctx,Backdrops.resolve(ctx,agendaId),160,75,true,agendaId)
            assertEquals(android.graphics.Color.parseColor("#294658"),solid.getPixel(80,37))
            val blurred = Backdrops.art(ctx,source,160,80,true,monthId)
            assertTrue(android.graphics.Color.alpha(blurred.getPixel(80,40)) in 200..254)
            val rounded = Backdrops.widgetTexture(ctx, source, monthId, 320, 160)
            assertEquals(0, rounded.getPixel(0, 0))
            assertEquals(blurred.getPixel(80, 40), rounded.getPixel(80, 40))
            assertTrue(runCatching { Backdrops.save(ctx,agendaId,obj("mode" to "photo")) }.isFailure)
            assertEquals("solid",Backdrops.read(ctx,agendaId).mode)
            Backdrops.save(ctx,monthId,obj("mode" to "follow"))
            assertEquals(Backdrops.read(ctx),Backdrops.resolve(ctx,monthId))
            Backdrops.save(ctx,monthId,obj("mode" to "photo"))
            assertEquals(source,Backdrops.read(ctx,monthId))
        } finally {
            Backdrops.drop(ctx,monthId)
            Backdrops.drop(ctx,agendaId)
        }
    }

    fun testSettingsAndWidgets() {
        val icon = ctx.getDrawable(ctx.applicationInfo.icon) as android.graphics.drawable.AdaptiveIconDrawable
        assertEquals(android.graphics.Color.rgb(21, 93, 79),
            (icon.background as android.graphics.drawable.ColorDrawable).color)
        val book = sample()
        for (theme in listOf("light", "dark", "frost")) {
            ctx.getSharedPreferences("display", 0).edit().putString("theme", theme).commit()
            val a = launch()
            try {
                waitFor("月历未显示") { a.books.any { it.id == book.id } && a.root.width > 0 }
                ui {
                    assertEquals(theme == "frost", a.frosted)
                    if (theme == "dark") capture(a.root, "android-month")
                    find(a.root, "设置")!!.performClick()
                }
                waitFor("设置卡片未显示") {
                    var page = find(a.root, "导入日历") ?: return@waitFor false
                    while (page.parent !is PageHost) page = page.parent as View
                    page.isLaidOut && page.alpha == 1f && page.translationY == 0f
                }
                ui { capture(a.root, "android-settings-" + theme) }
                for ((card, row) in
                    listOf(
                        "导入日历" to "识别手机系统日历",
                        "账号与同步" to (if (Account.read(ctx)==null) "发现局域网服务器" else "立即同步"),
                        "日历助手" to "配置个人模型",
                        "桌面组件" to "添加近期日程到桌面",
                        ("外观，" + a.themeName()) to "日期与字号",
                        "日程通知" to "选择提醒日历",
                    )) {
                    ui { find(a.root, card)!!.performClick() }
                    waitFor("缺少设置入口 " + row) { find(a.root, row) != null }
                    if (row == "日期与字号") {
                        ui { (find(a.root,row)!!.parent as View).performClick() }
                        waitFor("未打开月历显示") { find(a.root, "月历字号 · 标准") != null }
                        ui { a.onBackPressed() }
                        waitFor("未返回外观") { find(a.root, "日期与字号") != null }
                    }
                    ui { a.onBackPressed() }
                    waitFor("未回到设置首页") { find(a.root, "外观") != null }
                }
            } finally {
                ui { a.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        val host = ui { AppWidgetHost(ctx, 608) }
        val manager = AppWidgetManager.getInstance(ctx)
        try {
            ui { host.startListening() }
            for (month in listOf(true, false)) {
                val provider =
                    ComponentName(
                        ctx,
                        if (month) MonthWidget::class.java else AgendaWidget::class.java,
                    )
                val id = ui { host.allocateAppWidgetId() }
                assertTrue("组件绑定授权", manager.bindAppWidgetIdIfAllowed(id, provider))
                Widgets.save(ctx, id, setOf(book.id))
                for (size in listOf(340 to (if (month) 265 else 218), 160 to 110)) {
                    val (w, h) = size
                    val solid = month == (w == 340)
                    Backdrops.save(ctx, id, obj("mode" to if (solid) "solid" else "frost", "color" to "#294658"))
                    val options =
                        Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                        }
                    manager.updateAppWidgetOptions(id, options)
                    Store.io.submit { Widgets.render(ctx, id, month) }.get()
                    val view = ui { host.createView(ctx, id, manager.getAppWidgetInfo(id)) }
                    waitFor("系统没有应用组件布局") {
                        (view.findViewById<ViewGroup>(R.id.items)?.childCount ?: 0) > 0
                    }
                    ui {
                        val density = ctx.resources.displayMetrics.density
                        val width = (w * density).toInt()
                        val height = (h * density).toInt()
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, width, height)
                        // A software canvas exposes square fills even when clipToOutline is set.
                        val backdrop = view.findViewById<View>(R.id.backdrop)
                        val bitmap = Bitmap.createBitmap(backdrop.width, backdrop.height, Bitmap.Config.ARGB_8888)
                        try {
                            backdrop.draw(Canvas(bitmap))
                            for (x in listOf(0, bitmap.width - 1)) for (y in listOf(0, bitmap.height - 1)) {
                                assertEquals("组件四角应露出壁纸", 0, bitmap.getPixel(x, y))
                            }
                            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                            if (solid) assertEquals(android.graphics.Color.parseColor("#294658"), center)
                            else assertTrue(android.graphics.Color.alpha(center) in 200..254)
                        } finally {
                            bitmap.recycle()
                        }
                        assertTrue(
                            view.findViewById<View>(R.id.setup).isShown ||
                                view.findViewById<View>(R.id.setup).width > 0
                        )
                        val items = view.findViewById<ViewGroup>(R.id.items)
                        assertTrue(items.height > 0)
                        assertTrue(
                            items.getChildAt(items.childCount - 1).bottom <= items.height + 1
                        )
                        if (!month) {
                            val date = items.getChildAt(0).findViewById<TextView>(R.id.agenda_day)
                            assertEquals("今天", date.text.toString())
                            assertTrue(date.height >= date.lineHeight)
                            if (w == 340) {
                                val lastTime =
                                    items
                                        .getChildAt(items.childCount - 1)
                                        .findViewById<TextView>(R.id.agenda_time)
                                assertTrue("末行班次时间被裁切", lastTime.height >= lastTime.lineHeight - 2)
                            }
                            val firstTitle = items.getChildAt(0).findViewById<TextView>(R.id.line)
                            assertTrue(firstTitle.text.startsWith("门店早班"))
                        } else {
                            val day =
                                items
                                    .getChildAt(items.childCount - 1)
                                    .findViewById<TextView>(R.id.d0)
                            assertTrue("底部日期被裁切", day.height >= day.lineHeight - 2)
                        }
                        if (w == 340)
                            capture(
                                view.findViewById(R.id.widget_surface),
                                if (month) "android-month-widget" else "android-agenda-widget",
                            )
                    }
                }
            }
        } finally {
            ui {
                host.stopListening()
                host.deleteHost()
            }
        }
    }
}
