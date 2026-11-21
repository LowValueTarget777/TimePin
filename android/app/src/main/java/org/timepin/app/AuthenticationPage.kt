package org.timepin.app

import android.app.Dialog
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout

internal fun MainActivity.authenticationPage(origin: String, existing: Account? = null) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val page = column().apply {
        if (frosted) background = glass.wallpaper else setBackgroundColor(base)
        setPadding(dp(20), dp(12), dp(20), dp(20))
    }
    val back = calendarBack("返回账号与同步") { dialog.dismiss() }
    page.addView(back, dp(48), dp(48))
    val body = column()
    val heading = medium("登录 TimePin", 30).apply { setPadding(0, dp(18), 0, dp(4)) }
    body.addView(heading)
    body.addView(text(origin, 14, muted))
    val card = column().apply {
        background = windowSurface(22)
        setPadding(dp(18), dp(12), dp(18), dp(18))
    }
    body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    fun entry(label: String, secret: Boolean = false) = field(label, secret = secret).apply {
        background = shape(blend(panel, muted, .09f), 12)
        setPadding(dp(14), 0, dp(14), 0)
        isSaveEnabled = false
        card.addView(this, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
    }
    val name = entry("账号")
    existing?.let { name.setText(AccountProfiles.read(this, it).optString("loginName")) }
    val password = entry("密码", true)
    val confirm = entry("确认密码", true)
    val invite = entry("邀请码或邀请链接（按服务器要求）")
    val status = text("", 14, muted).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(0, dp(12), 0, dp(12))
    }
    card.addView(status)
    var registering = false
    lateinit var submit: android.widget.TextView
    lateinit var switch: android.widget.TextView
    fun render() {
        heading.text = if (registering) "注册账号" else "登录 TimePin"
        submit.text = if (registering) "注册并登录" else "登录"
        switch.text = if (registering) "已有账号，去登录" else "注册新账号"
        confirm.visibility = if (registering) View.VISIBLE else View.GONE
        invite.visibility = confirm.visibility
        status.text = if (registering) "密码至少 10 个字符。邀请注册的服务器还需要邀请码。" else "登录后将自动同步此账号与手机的日历。"
    }
    submit = sheetPrimaryButton("登录") {
        val login = name.text.toString().trim()
        val key = password.text.toString()
        val inviteText = invite.text.toString().trim()
        when {
            login.isEmpty() -> name.error = "请输入账号名"
            key.isEmpty() -> password.error = "请输入密码"
            registering && (key.codePointCount(0, key.length) < 10 || key.toByteArray().size > 256) -> password.error = "密码至少 10 个字符，至多 256 字节"
            registering && key != confirm.text.toString() -> confirm.error = "两次密码不一致"
            else -> {
                submit.isEnabled = false
                switch.isEnabled = false
                back.isEnabled = false
                dialog.setCancelable(false)
                status.text = if (registering) "正在注册…" else "正在登录…"
                work(Store.net, { runCatching {
                    if (registering) {
                        val uri = Uri.parse(inviteText)
                        val code = if (uri.scheme in listOf("https", "http")) {
                            uri.getQueryParameter("invite") ?: Uri.Builder().encodedQuery(uri.encodedFragment)
                                .build().getQueryParameter("invite").orEmpty()
                        } else inviteText
                        require(inviteText.isEmpty() || code.isNotEmpty()) { "邀请链接里缺少邀请码" }
                        Account.register(this, origin, login, key, code)
                    } else Account.login(this, origin, login, key)
                } }) { result ->
                    result.onSuccess {
                        dialog.dismiss()
                        autoSync()
                        updates.foreground()
                        reload()
                    }.onFailure {
                        dialog.setCancelable(true)
                        back.isEnabled = true
                        switch.isEnabled = true
                        submit.isEnabled = true
                        status.text = when ((it as? HttpFailure)?.status) {
                            401 -> "账号或密码不正确"
                            403 -> "该服务器需要有效的邀请码"
                            404 -> if (registering) "邀请码无效或已过期" else "服务器登录入口不可用"
                            409 -> "这个账号名已有人使用"
                            else -> it.message ?: "操作失败，请重试"
                        }
                    }
                }
            }
        }
    }
    card.addView(submit)
    switch = button("注册新账号") {
        registering = !registering
        password.text.clear()
        confirm.text.clear()
        render()
        if (registering) work(Store.net, {
            runCatching { request(origin, "/api/v1/state", calendarServer = true).body.getBoolean("canRegister") }.getOrNull()
        }) { open ->
            if (dialog.isShowing && registering && submit.isEnabled && open != null) {
                invite.hint = if (open) "邀请码或邀请链接（选填）" else "邀请码或邀请链接（必填）"
                status.text = if (open) "此服务器开放注册，密码至少 10 个字符。"
                    else "此服务器仅限邀请注册，请填写管理员发放的邀请码。"
            }
        }
    }
    if (existing == null) body.addView(switch, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    render()
    page.addView(scroll(body))
    dialog.setContentView(page)
    dialog.window!!.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(base))
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        statusBarColor = base
        navigationBarColor = base
    }
    dialog.show()
    dialog.window!!.setLayout(-1, -1)
}
