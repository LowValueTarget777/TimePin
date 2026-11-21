package org.timepin.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.*
import android.widget.*
import java.time.*
import org.json.*

private val palette =
    listOf("#29885F", "#357CB3", "#8262A7", "#C66A42", "#AD515C", "#486494", "#766946")

fun MainActivity.form(
    title: String,
    box: LinearLayout,
    action: String,
    save: (AlertDialog) -> Unit,
): AlertDialog {
    val dialog =
        calendarDialog()
            .setTitle(title)
            .setView(scroll(box))
            .setNegativeButton("取消", null)
            .setPositiveButton(action, null)
            .create()
    dialog.setOnShowListener {
        dialog.getButton(-1).setOnClickListener {
            runCatching { save(dialog) }.onFailure { message(it.message ?: "输入无效") }
        }
    }
    dialog.show()
    return dialog
}

fun MainActivity.editEvent(cal: String? = null, eventId: String? = null) {
    fun open(b: Book, r: JSONObject?) {
        val old = r?.optJSONObject("event")
        val span = old?.getJSONObject("span")
        val initialZone =
            ZoneId.of(
                span?.optString("zone")?.takeIf { it.isNotEmpty() } ?: ZoneId.systemDefault().id
            )
        val form = padded()
        form.addView(text(b.name, 13, muted))
        val title = field("事件标题", old?.optString("title") ?: "")
        form.addView(title)
        val all =
            CheckBox(this).apply {
                text = "全天"
                isChecked = span == null || span.getString("kind") == "date"
            }
        form.addView(all)
        fun fields(label: String, raw: String, defaultHour: Int): Pair<EditText, EditText> {
            form.addView(text(label, 13, muted))
            val row = row()
            val date = field("年-月-日", raw.take(10))
            val time =
                field(
                    "时:分",
                    if (raw.length >= 16) raw.substring(11, 16) else "%02d:00".format(defaultHour),
                )
            date.isFocusable = false
            date.setOnClickListener {
                val d = LocalDate.parse(date.text.toString())
                calendarDateDialog(
                        this,
                        { _, y, m, n -> date.setText(LocalDate.of(y, m + 1, n).toString()) },
                        d.year,
                        d.monthValue - 1,
                        d.dayOfMonth,
                    )
                    .show()
            }
            time.isFocusable = false
            time.setOnClickListener {
                val t = LocalTime.parse(time.text.toString())
                calendarTimeDialog(
                        this,
                        { _, h, m -> time.setText("%02d:%02d".format(h, m)) },
                        t.hour,
                        t.minute,
                        true,
                    )
                    .show()
            }
            row.addView(date, LinearLayout.LayoutParams(0, dp(48), 2f))
            row.addView(time, LinearLayout.LayoutParams(0, dp(48), 1f))
            form.addView(row)
            return date to time
        }
        val start = fields("开始", span?.optString("start") ?: day.toString(), 9)
        val end =
            fields(
                "结束（全天为最后一天）",
                when {
                    span == null -> day.toString()
                    all.isChecked -> LocalDate.parse(span.getString("end")).minusDays(1).toString()
                    else -> span.getString("end")
                },
                17,
            )
        val zoneLabel = text("时区", 13, muted)
        val zones = (listOf(initialZone.id, ZoneId.systemDefault().id, "Asia/Shanghai", "UTC") +
            ZoneId.getAvailableZoneIds().sorted()).distinct()
        val zonePicker = calendarSpinner().apply {
            contentDescription = "事件时区"
            adapter = ArrayAdapter(this@editEvent, android.R.layout.simple_spinner_dropdown_item, zones)
        }
        form.addView(zoneLabel)
        form.addView(zonePicker)
        fun times() {
            zoneLabel.visibility = if (all.isChecked) View.GONE else View.VISIBLE
            zonePicker.visibility = zoneLabel.visibility
            start.second.visibility = if (all.isChecked) View.GONE else View.VISIBLE
            end.second.visibility = start.second.visibility
        }
        times()
        all.setOnCheckedChangeListener { _, _ -> times() }
        var color = old?.optString("color")?.ifEmpty { null }
        val colors = row()
        form.addView(text("事件颜色", 13, muted))
        val custom = button("自定义颜色") {}
        fun swatches() {
            custom.text = color?.takeUnless { it in palette }?.let { "自定义颜色 · $it" } ?: "自定义颜色"
            colors.removeAllViews()
            for (c in listOf("") + palette) {
                val sw =
                    button(if (c == color.orEmpty()) "✓" else "") {
                        color = c.ifEmpty { null }
                        swatches()
                    }
                sw.contentDescription = if (c == "") "跟随日历颜色" else c
                sw.setTextColor(-1)
                sw.background = shape(android.graphics.Color.parseColor(c.ifEmpty { b.color }), 14)
                sw.minHeight = 0
                colors.addView(
                    sw,
                    LinearLayout.LayoutParams(0, dp(29), 1f).apply { marginEnd = dp(3) },
                )
            }
        }
        swatches()
        form.addView(colors)
        custom.setOnClickListener {
            val box = padded()
            val hex = field("#RRGGBB", color ?: b.color)
            box.addView(hex)
            form("自定义事件颜色", box, "使用") { dialog ->
                val value = hex.text.toString().trim()
                require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "请输入六位颜色值，例如 #337FB5" }
                color = value.uppercase(java.util.Locale.ROOT)
                swatches()
                dialog.dismiss()
            }
        }
        form.addView(custom)
        val freq = listOf("不重复", "每天", "每周", "每月", "每年")
        val rules = listOf("", "daily", "weekly", "monthly", "yearly")
        val repeat = calendarSpinner()
        repeat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, freq)
        repeat.setSelection(
            rules
                .indexOf(old?.optJSONObject("repeat")?.optString("frequency") ?: "")
                .coerceAtLeast(0)
        )
        form.addView(repeat)
        val readOnly =
            b.role == "reader" ||
                (b.role == "contributor" && r != null && r.optString("creatorId") != b.user)
        if (readOnly) {
            calendarDialog()
                .setTitle(old?.optString("title"))
                .setMessage((span?.optString("start") ?: "") + " — " + span?.optString("end"))
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        var del: TextView? = null
        if (r != null) {
            del = button("删除事件") {}
            form.addView(del)
        }
        val dialog =
            form(if (r == null) "新增日程" else "编辑日程", form, "保存") { dlg ->
                val a = LocalDate.parse(start.first.text.toString())
                val z = LocalDate.parse(end.first.text.toString())
                val zone = ZoneId.of(zones[zonePicker.selectedItemPosition])
                fun time(d: LocalDate, v: EditText): String {
                    val t = d.atTime(LocalTime.parse(v.text.toString()))
                    require(zone.rules.getValidOffsets(t).isNotEmpty()) { "所选当地时间不存在" }
                    return stamp(t.atZone(zone))
                }
                val s =
                    if (all.isChecked) Span("date", a.toString(), z.plusDays(1).toString())
                    else Span("timed", time(a, start.second), time(z, end.second), zone.id)
                val event =
                    (old?.copyJson() ?: JSONObject())
                        .put("title", title.text.toString())
                        .put("span", s.json())
                        .put("color", color)
                val rule = rules[repeat.selectedItemPosition]
                if (rule != old?.optJSONObject("repeat")?.optString("frequency").orEmpty()) {
                    event.remove("exceptions")
                    event.put(
                        "repeat",
                        if (rule == "") null else obj("frequency" to rule, "interval" to 1),
                    )
                }
                val op = if (r == null) "event.create" else "event.replace"
                execute(
                    b,
                    obj(
                        "v" to 1,
                        "op" to op,
                        "calendarId" to b.id,
                        "operationId" to uid(),
                        "eventId" to (eventId ?: uid()),
                        "event" to event,
                        "baseVersion" to r?.getLong("version"),
                    ),
                ) {
                    dlg.dismiss()
                    reload()
                    autoSync()
                }
            }
        if (del != null && r != null)
            del.setOnClickListener {
                calendarDialog()
                    .setMessage("删除「" + old?.optString("title") + "」？")
                    .setNegativeButton("保留", null)
                    .setPositiveButton("删除") { _, _ ->
                        execute(
                            b,
                            obj(
                                "v" to 1,
                                "op" to "event.delete",
                                "calendarId" to b.id,
                                "operationId" to uid(),
                                "eventId" to eventId,
                                "baseVersion" to r.getLong("version"),
                            ),
                        ) {
                            dialog.dismiss()
                            reload()
                            autoSync()
                        }
                    }
                    .show()
            }
    }
    if (cal == null) {
        chooseBook("写入日历") { open(it, null) }
        return
    }
    val b = books.find { it.id == cal && it.role != "none" } ?: return
    if (eventId == null) open(b, null)
    else
        work(
            fn = {
                db.rows().entry(cal, eventId)?.takeIf { it.live }?.let { JSONObject(it.json) }
                    ?: error("事件已删除")
            }
        ) {
            open(b, it)
        }
}

fun MainActivity.settings(): View {
    val group = settingsGroup ?: return settingsHome()
    val v = column().apply { setPadding(dp(18), dp(3), dp(18), dp(18)) }
    val card =
        column().apply {
            background = shape(panel, 19)
            clipToOutline = true
        }
    v.addView(card, -1, -2)
    fun item(s: String, icon: Int = group.symbol, act: () -> Unit) {
        if (card.childCount > 0)
            card.addView(
                View(this).apply { setBackgroundColor(blend(panel, muted, .16f)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(62)
                    marginEnd = dp(15)
                },
            )
        card.addView(settingRow(s, icon, groupInk(group), action = act))
    }
    when (group) {
        SettingGroup.ABOUT -> return updates.page()
        SettingGroup.CALENDARS -> return calendarDirectory()
        SettingGroup.IMPORT -> {
            val enabled = SystemCalendarWatcher.enabled(this)
            item("自动导入系统日历 · " + if (enabled) "已开启" else "已关闭") {
                work(fn = { DeviceCommands(this).run("system.autoImport.update", obj("enabled" to !enabled)) }) {
                    if (!enabled && !SystemCalendarWatcher.hasPermission(this)) requestAutomaticCalendarAccess()
                    reload()
                }
            }
            if (enabled && !SystemCalendarWatcher.hasPermission(this))
                item("允许读取系统日历") { requestAutomaticCalendarAccess() }
            item("识别手机系统日历") { detectCalendars() }
        }
        SettingGroup.DISPLAY -> monthSettings(card, ::item)
        SettingGroup.REMINDERS -> reminderSettings(::item)
        SettingGroup.CONNECTION -> return accountPage()
        SettingGroup.MODEL -> {
            item("配置个人模型", R.drawable.ic_key) {
                editPersonalModel()
            }
        }
        SettingGroup.WIDGETS -> {
            item("添加桌面月历到桌面",R.drawable.ic_calendar) { pinCalendarWidget(true) }
            item("添加近期日程到桌面",R.drawable.ic_list) { pinCalendarWidget(false) }
        }
        SettingGroup.THEME -> return appearanceSettings()
    }
    return v
}

internal fun MainActivity.pinCalendarWidget(month: Boolean) {
    val provider = if (month) MonthWidget::class.java else AgendaWidget::class.java
    if (!Widgets.requestPin(this, android.content.ComponentName(this,provider)))
        calendarDialog().setTitle("添加桌面组件")
            .setMessage("长按桌面空白处，打开小组件列表，选择 TimePin 的月历或近期日程。")
            .setPositiveButton("好",null).show()
    else message("已请求桌面添加组件")
}

fun MainActivity.chooseTheme() {
    val modes = mutableListOf("system" to "跟随系统", "dark" to "深色", "light" to "浅色")
    modes.add("frost" to "雾光 · 毛玻璃")
    calendarDialog()
        .setItems(modes.map { it.second }.toTypedArray()) { _, i ->
            prefs.edit().putString("theme", modes[i].first).apply()
            recreate()
        }
        .show()
}

private fun MainActivity.instanceChatBooks(): List<Book> {
    val account = Account.read(this)
    val owner = account?.let { it.origin + "/" + it.user }.orEmpty()
    if (serverChatOwner != owner) {
        serverChatOwner = owner
        serverChatBooks = null
        serverChatPending = false
        refreshChatBooks = true
        serverChatError = ""
        chatBookIds = null
        draft = ""
    }
    if (account == null) {
        serverChatError = "请先登录日历服务器"
        return emptyList()
    }
    if (refreshChatBooks && !serverChatPending) {
        refreshChatBooks = false
        serverChatPending = true
        work(Store.net, {
            runCatching {
                val state = account.api("state")
                require(state.getJSONObject("ai").optBoolean("enabled")) { "服务器尚未向此账号开放共用模型" }
                val replicas = db.accountBooks(this).filter {
                    it.origin == account.origin && it.user == account.user
                }.associateBy { it.serverId }
                state.getJSONArray("calendars").objects().map {
                    val id = it.getString("id")
                    replicas[id]?.copy(role = it.getString("role")) ?: Book(id,
                        it.getString("name"), it.getString("color"), origin = account.origin,
                        user = account.user, role = it.getString("role"))
                }
            }
        }) { result ->
            if (owner == serverChatOwner) {
                serverChatPending = false
                serverChatBooks = result.getOrNull()
                serverChatError = result.exceptionOrNull()?.message.orEmpty()
                if (page == "日历助手") reload()
            }
        }
    }
    return serverChatBooks.orEmpty()
}

fun MainActivity.chatPanel(): View {
    val f = padded()
    val personal = prefs.getBoolean("personal", true)
    val account = Account.read(this)
    val available = when {
        chatMode == "account" -> emptyList()
        personal || chatMode == "device" -> books.filter { it.role != "none" }
        else -> instanceChatBooks()
    }
    val selected = available.filter { chatBookIds?.contains(it.id) != false }
    val canSend = if (chatMode == "account") account != null else selected.isNotEmpty()
    val key = chatMode + account?.origin + account?.user + selected.joinToString("|") { it.origin + it.user + it.id + it.role } + personal
    if (historyKey != key) {
        while (history.length() > 0) history.remove(0)
        conversation = ""
        chatPartial = ""
        retryChat = null
        historyKey = key
    }
    val modes = linkedMapOf("calendar" to "日历日程","account" to "账号管理","device" to "本机设备")
    fun switchMode(next: String, request: String = "") {
        if (next == chatMode || modelBusy) return
        if (chatMode != "account") chatSelections[chatMode] = chatBookIds
        chatMode = next
        chatBookIds = chatSelections[next]
        draft = request
        reload()
    }
    val scopeRow = row()
    scopeRow.addView(button("操作范围 · " + modes[chatMode] + " ▾") {
        if (!modelBusy) calendarDialog().setItems(modes.values.toTypedArray()) { _, i ->
            switchMode(modes.keys.elementAt(i))
        }.show()
    })
    if (chatMode == "calendar" && account != null) {
        scopeRow.addView(button("创建日历") {
            switchMode("account", draft.ifBlank { "帮我创建一本日历，名称是" })
        }.apply { isEnabled = !modelBusy })
    }
    f.addView(scopeRow)
    if (chatMode != "account") f.addView(text("对话日历", 14, muted))
    val choices = row()
    fun pick(label:String, checked:Boolean, change:(Boolean)->Unit) {
        choices.addView(CheckBox(this).apply {
            text=label; setTextColor(ink); isChecked=checked; isEnabled=!modelBusy
            setOnCheckedChangeListener { _, on -> change(on); reload() }
        })
    }
    pick("全部日历",available.isNotEmpty() && selected.size==available.size) { chatBookIds=if(it) null else emptySet() }
    for (book in available) pick(book.name,book in selected) { on ->
        val ids=selected.map {it.id}.toMutableSet()
        if(on) ids.add(book.id) else ids.remove(book.id)
        chatBookIds=ids.takeUnless {it.size==available.size}
    }
    if (chatMode != "account") f.addView(HorizontalScrollView(this).apply { addView(choices) })
    val header = row().apply { background = shape(panel, 16) }
    header.addView(
        button((if (personal) "个人模型" else "实例共用模型") + " ▾") {
            if(modelBusy) return@button
            calendarDialog()
                .setItems(arrayOf("个人模型", "实例共用模型", "配置个人模型")) { _, i ->
                    when (i) {
                        0 -> { chatBookIds=null; prefs.edit().putBoolean("personal",true).apply(); reload() }
                        1 -> {
                            refreshChatBooks = true
                            chatBookIds = null
                            prefs.edit().putBoolean("personal", false).apply()
                            reload()
                        }
                        else -> editPersonalModel()
                    }
                }
                .show()
        }
    )
    f.addView(header)
    if (chatMode == "calendar" && !personal && serverChatError.isNotEmpty()) {
        f.addView(text(serverChatError, 14, muted))
        f.addView(button("重新读取日历") { refreshChatBooks = true; reload() })
    } else if (chatMode == "calendar" && !personal && serverChatPending && available.isEmpty()) {
        f.addView(text("正在读取账号日历…", 14, muted))
    }
    val log =
        text((conversation + chatPartial).ifBlank { when {
            chatMode == "account" -> if (account == null) "请先登录服务器账号" else "可以创建日历、建团组或接受邀请"
            selected.isEmpty() -> "请先勾选日历"
            chatMode == "device" -> "可以配置桌面组件、同步或处理冲突"
            else -> "输入要查询或调整的班次"
        } }, 16, if (conversation.isBlank()) muted else ink)
            .apply {
                setPadding(dp(8), dp(18), dp(8), dp(15))
                tag = "chat-output"
                setLineSpacing(dp(3).toFloat(), 1f)
            }
    val chatLog = scroll(log)
    var blankDown = -1f
    chatLog.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                blankDown = if (e.y + view.scrollY > log.bottom) e.y else -1f
                blankDown >= 0f
            }
            MotionEvent.ACTION_UP -> {
                val close = blankDown >= 0f && kotlin.math.abs(e.y - blankDown) < ViewConfiguration.get(this).scaledTouchSlop
                blankDown = -1f
                if (close) { view.performClick(); page = "月历"; reload() }
                close
            }
            MotionEvent.ACTION_CANCEL -> { blankDown = -1f; false }
            else -> blankDown >= 0f
        }
    }
    f.addView(chatLog, weighted(false))
    val input = field(if (chatMode == "account") "例如：创建一本叫排班的日历" else "例如：把周五的早班改到下午", draft)
    input.setSelection(input.text.length)
    input.addTextChangedListener(
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft = s.toString()
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        }
    )
    val composer =
        row().apply {
            background = shape(panel, 19)
            setPadding(dp(10), dp(5), dp(5), dp(5))
        }
    input.background = null
    composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
    val send = button(if (modelBusy) "停止" else "发送") {
        if (modelBusy) { chatStream?.cancel(); return@button }
        val msg=input.text.toString().trim()
        if (msg.isBlank() || !canSend) return@button
        modelBusy=true
        val past=JSONArray(history.toString())
        val op=retryChat?.takeIf {it.first==msg}?.second ?: uid()
        retryChat=msg to op
        conversation += "\n我　$msg\n"
        chatPartial="正在连接模型…"
        input.setText("")
        val pendingPaint = java.util.concurrent.atomic.AtomicBoolean()
        val stream=ChatStream { partial ->
            if (historyKey==key) {
                chatPartial="助手　"+partial
                if (pendingPaint.compareAndSet(false,true)) root.postDelayed({
                    pendingPaint.set(false)
                    if (modelBusy && historyKey==key && !isDestroyed)
                        root.findViewWithTag<TextView>("chat-output")?.text=conversation+chatPartial
                },32)
            }
        }
        chatStream=stream
        reload()
        Store.net.execute {
            val response=runCatching {
                if (chatMode == "calendar") Assistant(this,db).chat(selected,msg,past,personal,stream,op)
                else ActionAssistant(this,db).chat(chatMode,selected.map { it.id }.toSet(),msg,past,personal,stream,op)
            }
            runOnUiThread {
                modelBusy=false; chatStream=null
                if (isDestroyed || historyKey!=key) return@runOnUiThread
                response.onFailure {
                    conversation += chatPartial + "\n" + (if(stream.cancelled) "已停止接收回复" else it.message ?: "模型调用失败") + "\n"
                    draft=msg
                }.onSuccess { t ->
                    retryChat=null
                    history.put(obj("role" to "user","content" to msg))
                    history.put(obj("role" to "assistant","content" to t.text.take(2000)))
                    while(history.length()>8) history.remove(0)
                    conversation+="助手　"+t.text+"\n"
                    if(t.proposal!=null || t.confirmation.isNotEmpty()) {
                        val target=selected.find{it.id==t.target} ?: selected.firstOrNull()
                        calendarDialog().setTitle("核对日历操作")
                            .setMessage(t.text+"\n"+(t.proposal?.toString(2) ?: ""))
                            .setNegativeButton("取消",null).setPositiveButton("确认执行") { _,_ ->
                                work(Store.net,{
                                    if (t.action != null) ActionAssistant(this,db).confirm(t)
                                    else Assistant(this,db).confirm(requireNotNull(target),t)
                                }) {
                                    conversation+=it+"\n"; refreshChatBooks = true; reload()
                                    if (t.action == null) autoSync()
                                }
                            }.show()
                    }
                }
                chatPartial="";reload()
            }
        }
    }.apply { background=shape(green,14);setTextColor(onColor(green));isEnabled=modelBusy || canSend }
    composer.addView(send)
    val pref="chat-enter-"+(available.firstOrNull { it.origin.isNotBlank() }?.user ?: "local")
    input.setSingleLine(false);input.maxLines=4;input.isEnabled=!modelBusy
    fun imeMode() {
        input.imeOptions=if(prefs.getBoolean(pref,false)) android.view.inputmethod.EditorInfo.IME_ACTION_SEND else android.view.inputmethod.EditorInfo.IME_ACTION_NONE
    }
    imeMode()
    input.setOnEditorActionListener { _,action,event ->
        val physical=event?.keyCode==KeyEvent.KEYCODE_ENTER && !event.isShiftPressed &&
            (event.isCtrlPressed || prefs.getBoolean(pref,false)) &&
            android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text)<0
        if (action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND || physical) {
            if (!modelBusy && (event==null || (event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0))) send.performClick()
            true
        } else false
    }
    val arrow=button("⌄") {}.apply { contentDescription="回车键行为" }
    arrow.setOnClickListener {
        PopupMenu(this,arrow).apply {
            menu.add(1,1,0,"回车换行");menu.add(1,2,1,"回车发送");menu.setGroupCheckable(1,true,true)
            menu.findItem(if(prefs.getBoolean(pref,false)) 2 else 1).isChecked=true
            setOnMenuItemClickListener { prefs.edit().putBoolean(pref,it.itemId==2).apply();imeMode();true }
        }.show()
    }
    composer.addView(arrow)
    f.addView(composer)
    return f
}

internal fun MainActivity.createBookForm() {
    val account = Account.read(this)
    val box = padded()
    val name = field("日历名称")
    val col = field("颜色", "#278B77")
    box.addView(name)
    box.addView(col)
    form("新建日历", box, "创建") { d ->
        val n = name.text.toString().trim()
        val c = col.text.toString()
        require(n.length in 1..80 && colorPattern.matches(c))
        val b = Book("mobile-" + uid(), n, c)
        work(fn = {
            if (account == null) db.rows().save(b) else Sync(this, db, account).createCalendar(b)
        }) {
            active = b.id
            d.dismiss()
            reload()
            if (account != null) autoSync()
        }
    }
}
