package org.timepin.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import java.time.*
import java.util.concurrent.Executor
import org.json.*

class MainActivity : Activity() {
    internal var avatarRequest: Bundle? = null
    internal var draftExport: Bundle? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DRAFT_DOCUMENT) {
            val target = draftExport
            draftExport = null
            if (resultCode == RESULT_OK && target != null) data?.data?.let { exportDraft(target, it) }
        }
        if (requestCode == AVATAR_PICKER) {
            val target = avatarRequest
            avatarRequest = null
            if (resultCode == RESULT_OK && target != null) data?.data?.let { savePickedAvatar(it, target) }
        }
    }

    val db by lazy { Store.get(this) }
    private val calendarImport by lazy { SystemCalendarWatcher(this) }
    val prefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    var day = LocalDate.now()
    var month = YearMonth.from(day)
    var page = "月历"
    var settingsGroup: SettingGroup? = null
    var books = listOf<Book>()
    var slots = listOf<Slot>()
    var active = ""
    var notice = ""
    private var generation = 0
    private var incoming: Intent? = null
    private var startX = 0f
    internal val updates by lazy { AppUpdates(this) }
    private val foregroundSync by lazy { ForegroundSync(this) }
    val themeMode by lazy { calendarMode() }
    val frosted get() = themeMode == "frost"
    internal val glass by lazy { FrostGlass(this) }
    val dark by lazy {
        when (themeMode) {
            "dark" -> true
            "light", "frost" -> false
            else -> resources.configuration.uiMode and 48 == 32
        }
    }

    val base
        get() = if (frosted) 0xffd2e4f1.toInt() else if (dark) 0xff131B1A.toInt() else 0xffF4F4EE.toInt()

    val panel
        get() = if (frosted) 0xffedf3fa.toInt() else if (dark) 0xff202B29.toInt() else 0xffFFFEFA.toInt()

    val ink
        get() = if (frosted) 0xff173047.toInt() else if (dark) 0xfff1f4ef.toInt() else 0xff17261f.toInt()

    val muted
        get() = if (frosted) 0xff4a6076.toInt() else if (dark) 0xffa6b2a8.toInt() else 0xff53695d.toInt()

    val green
        get() = if (frosted) 0xff345b83.toInt() else if (dark) 0xff91D9B9.toInt() else 0xff21694F.toInt()

    lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var pages: PageHost
    private lateinit var addEvent: View
    private var navigationPage: String? = null
    private var calendarTitle: TextView? = null
    private var headerBooks = emptyList<Book>()
    private var spareMonth: MonthGrid? = null

    fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    fun text(s: String, size: Int = 15, c: Int = ink) =
        TextView(this).apply {
            text = s
            textSize = size.toFloat()
            setTextColor(c)
            gravity = Gravity.CENTER_VERTICAL
        }

    fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun shape(c: Int, r: Int = 12) =
        if (frosted && c == panel) glass.pane(r) else GradientDrawable().apply {
            setColor(c)
            cornerRadius = dp(r).toFloat()
        }

    fun button(s: String, fn: () -> Unit) =
        text(s, 15, green).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(42)
            isFocusable = true
            foreground = pressEffect(12)
            setOnClickListener { fn() }
        }

    fun field(label: String, value: String = "", secret: Boolean = false) =
        EditText(this).apply {
            hint = label
            setText(value)
            isSingleLine = true
            setTextColor(ink)
            setHintTextColor(muted)
            if (secret) inputType = 129
        }

    fun weighted(horizontal: Boolean = true) =
        LinearLayout.LayoutParams(if (horizontal) 0 else -1, if (horizontal) -1 else 0, 1f)

    fun scroll(content: View) = ScrollView(this).apply { addView(content) }

    fun padded() = column().apply { setPadding(dp(14), dp(8), dp(14), dp(12)) }

    fun message(s: String) {
        notice = s
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    fun <T> work(ex: Executor = Store.io, fn: () -> T, done: (T) -> Unit) {
        ex.execute {
            val r = runCatching(fn)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) r.fold(done) { message(it.message ?: "操作失败") }
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        installCalendarTheme()
        super.onCreate(state)
        window.statusBarColor = base
        window.navigationBarColor = if (frosted) 0xffc9d7ec.toInt() else base
        if (!dark)
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (state != null) {
            avatarRequest = state.getBundle("avatarRequest")
            draftExport = state.getBundle("draftExport")
            day = LocalDate.parse(state.getString("day"))
            month = YearMonth.parse(state.getString("month"))
            page = state.getString("page") ?: "月历"
            settingsGroup = state.getString("settingsGroup")?.let(SettingGroup::valueOf)
            active = state.getString("active") ?: ""
        }
        if (state == null) incoming = intent
        reload()
    }

    override fun onSaveInstanceState(s: Bundle) {
        s.putBundle("avatarRequest", avatarRequest)
        s.putBundle("draftExport", draftExport)
        s.putString("day", day.toString())
        s.putString("month", month.toString())
        s.putString("page", page)
        s.putString("settingsGroup", settingsGroup?.name)
        s.putString("active", active)
        super.onSaveInstanceState(s)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        incoming = i
        reload()
    }

    private val deviceChanges =
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (backdropChanged()) recreate() else reload()
            }
        }

    private fun backdropChanged() = ::root.isInitialized && frosted && Backdrops.read(this) != glass.selection

    override fun onStart() {
        super.onStart()
        val app = applicationContext
        Store.io.execute { Widgets.reconcile(app) }
        contentResolver.registerContentObserver(
            Uri.parse("content://" + packageName + ".device"),
            true,
            deviceChanges,
        )
        foregroundSync.start()
    }

    override fun onStop() {
        foregroundSync.stop()
        calendarImport.stop()
        contentResolver.unregisterContentObserver(deviceChanges)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updates.showAvailableUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (backdropChanged()) { recreate(); return }
        calendarImport.resume()
        updates.foreground()
        if (::root.isInitialized) reload()
        if (Reminders.enabled(this)) Reminders.queue(this)
    }

    fun requestAutomaticCalendarAccess() = calendarImport.requestAccess()

    fun autoSync() = foregroundSync.edited()

    fun reload() {
        val n = ++generation
        val intentToOpen = incoming
        if (intentToOpen?.action == "org.timepin.OPEN") page = "月历"
        val needsAgenda = page == "月历" || page == "日程"
        intentToOpen?.getStringExtra("day")?.let {
            day = runCatching { LocalDate.parse(it) }.getOrDefault(day)
            month = YearMonth.from(day)
        }
        val first = month.atDay(1).minusDays(MonthOptions.read(this).lead(month.atDay(1)).toLong())
        val end = first.plusWeeks(6)
        work(
            fn = {
                db.initLocal()
                val available = db.accountBooks(this).filter { it.role != "none" }
                available to
                    if (needsAgenda)
                        db.agenda(
                            available.filter { it.visible },
                            first,
                            end,
                            ZoneId.systemDefault(),
                        )
                    else emptyList()
            }
        ) { (available, list) ->
            if (generation != n) return@work
            books = available
            slots = list
            active = intentToOpen?.getStringExtra("cal") ?: active
            if (books.none { it.id == active })
                active = books.firstOrNull { it.visible }?.id ?: books.firstOrNull()?.id.orEmpty()
            draw()
            incoming = null
            if (intentToOpen != null) {
                when {
                    intentToOpen.getBooleanExtra("new", false) -> editEvent()
                    intentToOpen.getStringExtra("event") != null ->
                        intentToOpen.getStringExtra("cal")?.let {
                            editEvent(it, intentToOpen.getStringExtra("event"))
                        }
                    intentToOpen.getStringExtra("day") != null &&
                        !intentToOpen.getBooleanExtra("selectOnly", false) -> showDay(day)
                }
            }
        }
    }

    fun book() = books.firstOrNull { it.id == active && it.role != "none" } ?: error("请先选择日历")

    fun chooseBook(title: String, writable: Boolean = true, done: (Book) -> Unit) {
        val avail = books.filter { it.role != "none" && (!writable || it.role != "reader") }
        if (avail.isEmpty()) {
            message("没有可用日历")
            return
        }
        if (avail.size == 1) {
            done(avail.single())
            return
        }
        calendarDialog()
            .setTitle(title)
            .setItems(avail.map { it.name }.toTypedArray()) { _, i -> done(avail[i]) }
            .show()
    }

    fun showSettings(group: SettingGroup?) {
        settingsGroup = group
        draw()
    }

    override fun onBackPressed() {
        if (page == "设置" && settingsGroup != null) {
            showSettings(if (settingsGroup == SettingGroup.DISPLAY) SettingGroup.THEME else null)
        } else if (page == "日历助手") {
            page = "月历"
            reload()
        } else super.onBackPressed()
    }

    private fun createShell() {
        root = column().apply { if (frosted) background = glass.wallpaper else setBackgroundColor(base) }
        header = column()
        if (frosted && glass.selection.mode == "solid" && Color.luminance(Color.parseColor(glass.selection.color)) < .42) {
            header.background = glass.pane(18)
        }
        root.addView(header, -1, -2)
        val frame = FrameLayout(this)
        pages = PageHost(this)
        frame.addView(pages, -1, -1)
        addEvent =
            iconButton(R.drawable.ic_plus, "新增日程", Color.WHITE) { editEvent() }
                .apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = if (frosted) glaze(19, 0xff486c91, 0xff284966) else shape(0xff21694F.toInt(), 19)
                    elevation = dp(3).toFloat()
                }
        frame.addView(
            addEvent,
            FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(14), dp(12))
            },
        )
        root.addView(frame, weighted(false))
        setContentView(root)
    }

    private fun drawHeader(calendarPage: Boolean) {
        val heading =
            if (calendarPage) "${month.year}年${month.monthValue}月"
            else if (page == "设置") settingsGroup?.title ?: page else page
        val existing = calendarTitle
        if (calendarPage && existing != null && books == headerBooks) {
            if (existing.text != heading) {
                existing.text = heading
                existing.contentDescription = "选择年月，" + heading
            }
            return
        }
        header.removeAllViews()
        val top = row().apply { setPadding(dp(18), 0, dp(12), 0) }
        if (page == "设置" && settingsGroup != null)
            top.addView(
                calendarBack(if (settingsGroup == SettingGroup.DISPLAY) "返回外观" else "返回设置") { onBackPressed() },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) },
            )
        val title = medium(heading, if (calendarPage) 25 else 30)
        calendarTitle = title.takeIf { calendarPage }
        headerBooks = if (calendarPage) books else emptyList()
        if (calendarPage) {
            press(title, "选择年月，" + heading, 12) { jumpDate() }
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null,
                null,
                getDrawable(R.drawable.ic_down)?.apply { setTint(muted) },
                null,
            )
            top.addView(title)
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(iconButton(R.drawable.ic_prev, "上个月", muted) { move(-1) }, dp(38), dp(42))
            top.addView(
                button("今天") {
                    day = LocalDate.now()
                    month = YearMonth.from(day)
                    reload()
                }
            )
            top.addView(iconButton(R.drawable.ic_next, "下个月", muted) { move(1) }, dp(38), dp(42))
        } else top.addView(title, weighted())
        header.addView(top, -1, dp(if (calendarPage) 59 else 68))
        if (calendarPage) {
            val chips = row().apply { setPadding(dp(if (frosted) 14 else 8), 0, dp(8), 0) }
            for (b in books.filter { it.role != "none" }) {
                val chip =
                    button(b.name) {
                        active = b.id
                        work(
                            fn = {
                                db.atomic {
                                    db.rows().book(b.id)?.let {
                                        db.rows().save(it.copy(visible = !it.visible))
                                    }
                                }
                            }
                        ) {
                            reload()
                        }
                    }
                val calColor = Color.parseColor(b.color)
                val marker =
                    if (b.visible)
                        getDrawable(R.drawable.ic_check)!!.mutate().also {
                            it.setTint(readableAccent(calColor, panel, ink))
                        }
                    else shape(blend(panel, calColor, .65f), 5)
                val markSize = dp(if (b.visible) 15 else 8)
                marker.setBounds(0, 0, markSize, markSize)
                chip.setCompoundDrawablesRelative(marker, null, null, null)
                chip.compoundDrawablePadding = dp(5)
                chip.isSelected = b.visible
                chip.background = if (frosted && b.visible)
                    glaze(16, 0xdeffffff, (blend(panel, calColor, .25f).toLong() and 0xffffff) or 0xce000000)
                else shape(if (b.visible) blend(panel, calColor, .16f) else panel, 16)
                chip.setTextColor(if (b.visible) blend(ink, calColor, .30f) else muted)
                chip.minHeight = 0
                val p = LinearLayout.LayoutParams(-2, dp(33))
                p.marginEnd = dp(6)
                chips.addView(chip, p)
            }
            header.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                },
                -1,
                dp(44),
            )
        }
    }

    private fun draw() {
        if (!::root.isInitialized) createShell()
        val calendarPage = page == "月历" || page == "日程"
        if (frosted) {
            val inset = if (page == "月历") dp(10) else 0
            pages.setPadding(inset, 0, inset, if (inset > 0) dp(3) else 0)
        }
        drawHeader(calendarPage)
        if (page != "月历") spareMonth = null
        val content =
            when (page) {
                "月历" -> monthGrid()
                "日程" -> {
                    val list = padded()
                    var previousDate: LocalDate? = null
                    for (s in
                        slots.filter {
                            val (a, b) = it.span.daysIn(ZoneId.systemDefault())
                            a <= month.atEndOfMonth() && b >= month.atDay(1)
                        }) {
                        val date = s.span.daysIn(ZoneId.systemDefault()).first
                        if (date != previousDate) {
                            list.addView(
                                medium(
                                        "${date.monthValue}月${date.dayOfMonth}日　周" +
                                            "一二三四五六日"[date.dayOfWeek.value - 1],
                                        14,
                                        muted,
                                    )
                                    .apply { setPadding(dp(3), dp(14), 0, dp(7)) }
                            )
                            previousDate = date
                        }
                        list.addView(
                            eventLine(s),
                            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) },
                        )
                    }
                    list.setPadding(dp(15), 0, dp(15), dp(70))
                    if (list.childCount == 0) list.addView(text("本月没有日程", 16, muted))
                    scroll(list)
                }
                "设置" -> scroll(settings())
                else -> chatPanel()
            }
        if (frosted && glass.selection.mode == "solid" && page != "月历"
            && Color.luminance(Color.parseColor(glass.selection.color)) < .42)
            content.background = glass.pane(18)
        pages.display(
            content,
            page,
            month.takeIf { calendarPage },
            settingsGroup.takeIf { page == "设置" },
        )
        addEvent.visibility = if (calendarPage) View.VISIBLE else View.GONE
        if (navigationPage != page) {
            if (navigationPage != null) root.removeViewAt(root.childCount - 1)
            val navLayout = LinearLayout.LayoutParams(-1, dp(63)).apply {
                if (frosted) setMargins(dp(12), dp(6), dp(12), dp(7))
            }
            root.addView(navigation(), navLayout)
            navigationPage = page
        }
    }

    fun move(count: Long) {
        month = month.plusMonths(count)
        day = month.atDay(minOf(day.dayOfMonth, month.lengthOfMonth()))
        reload()
    }

    fun jumpDate() {
        calendarDateDialog(
                this,
                { _, y, m, d ->
                    day = LocalDate.of(y, m + 1, d)
                    month = YearMonth.from(day)
                    reload()
                },
                day.year,
                day.monthValue - 1,
                day.dayOfMonth,
            )
            .show()
    }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) startX = e.x
        if (
            e.action == MotionEvent.ACTION_UP &&
                page == "月历" &&
                e.y > dp(150) &&
                kotlin.math.abs(e.x - startX) > dp(110)
        ) {
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            move(if (e.x < startX) 1 else -1)
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    private fun monthGrid(): MonthGrid {
        val current = pages.getChildAt(0) as? MonthGrid
        val options = MonthOptions.read(this)
        if (current != null && current.month == month && current.options == options) {
            current.bind(month, day, slots)
            return current
        }
        val grid =
            spareMonth?.takeIf { it.parent == null && it.options == options }
                ?: MonthGrid(this, options, ::showDay)
        spareMonth = current
        grid.bind(month, day, slots)
        return grid
    }

    private fun eventLine(s: Slot): View {
        val line =
            row().apply {
                background = shape(panel, 15)
                setPadding(dp(14), dp(10), dp(12), dp(10))
            }
        line.addView(
            View(this).apply { background = shape(Color.parseColor(s.color), 3) },
            dp(4),
            dp(32),
        )
        val body = column().apply { setPadding(dp(12), 0, dp(8), 0) }
        val meta =
            s.span.label(ZoneId.systemDefault()).ifEmpty { "全天" } +
                "　" +
                books.firstOrNull { it.id == s.cal }?.name.orEmpty()
        body.addView(medium(s.title, 16))
        body.addView(text(meta, 12, muted))
        line.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(icon(R.drawable.ic_next, muted, 3), dp(17), dp(17))
        press(line, "${s.title}，$meta", 15) { editEvent(s.cal, s.id) }
        return line
    }

    private fun showDay(
        d: LocalDate,
        es: List<Slot> =
            slots.filter {
                val (a, b) = it.span.daysIn(ZoneId.systemDefault())
                d in a..b
            },
    ) {
        if (es.isEmpty()) return
        calendarDialog()
            .setTitle(d.toString())
            .setPositiveButton("新增") { _, _ -> editEvent() }
            .setNegativeButton("关闭", null)
            .setItems(
                es.map { it.span.label(ZoneId.systemDefault()).ifEmpty { "全天" } + "   " + it.title }
                    .toTypedArray()
            ) { _, i ->
                editEvent(es[i].cal, es[i].id)
            }
            .show()
    }

    fun execute(b: Book, c: JSONObject, done: (JSONObject) -> Unit = { reload() }) {
        work(
            fn = {
                val r = requireOk(Commands(db).run(b, c, Account.read(this)))
                if (
                    c.getString("op") !in
                        listOf("import.preview", "import.undoPreview", "event.get", "event.query")
                )
                    Widgets.refresh(this, setOf(b.id))
                r
            },
            done = done,
        )
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<String>,
        grants: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, grants)
        when (code) {
            41 ->
                if (
                    checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                )
                    detectCalendars()
                else message("需要日历读取权限才能发现系统日历，可以在应用权限设置中开启")
            43 -> {
                calendarImport.permissionResult()
                reload()
            }
            42 ->
                if (
                    android.os.Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Reminders.configure(this, on = true)
                    reload()
                } else message("通知权限已关闭，可在系统设置中开启")
        }
    }

    val history = JSONArray()
    @Volatile var historyKey = ""
    var conversation = ""
    var draft = ""
    var chatMode = "calendar"
    val chatSelections = mutableMapOf<String,Set<String>?>()
    var modelBusy = false
    var serverChatOwner = ""
    var serverChatBooks: List<Book>? = null
    var serverChatPending = false
    var serverChatError = ""
    var refreshChatBooks = true
    var chatBookIds: Set<String>? = null
    @Volatile var chatPartial = ""
    var chatStream: ChatStream? = null
    var retryChat: Pair<String,String>? = null

    override fun onDestroy() {
        chatStream?.cancel()
        super.onDestroy()
    }
}
