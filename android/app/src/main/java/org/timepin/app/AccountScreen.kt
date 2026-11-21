package org.timepin.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

internal fun MainActivity.detailSheet(title: String, build: (LinearLayout, Dialog) -> Unit) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box =
        column().apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = windowSurface(26)
        }
    box.addView(
        View(this).apply { background = shape(blend(panel, muted, .4f), 4) },
        LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        },
    )
    val head = row()
    head.addView(medium(title, 21), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(button("关闭") { dialog.dismiss() })
    box.addView(head)
    build(box, dialog)
    dialog.setContentView(scroll(box))
    val win = dialog.window!!
    win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    win.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dimWindow(win)
    dialog.show()
    win.setLayout(minOf(resources.displayMetrics.widthPixels, dp(500)), -2)
    box.translationY = dp(16).toFloat()
    box.animate().translationY(0f).setDuration(170).start()
}

private fun MainActivity.identityCard(a: Account?, click: (() -> Unit)?): View {
    val p = a?.let { AccountProfiles.read(this, it) }
    val server = a?.endpoint ?: Account.selectedServer(this)
    val name = p?.optString("displayName") ?: if (server.isEmpty()) "连接日历服务器" else "登录 TimePin"
    val deep = if (frosted) 0xffd4e5f5.toInt() else if (dark) 0xff204940.toInt() else 0xffdceee3.toInt()
    val main = if (frosted) ink else if (dark) 0xffe1f3e9.toInt() else 0xff173e33.toInt()
    val soft = blend(main, deep, .29f)
    val card =
        row().apply {
            setPadding(dp(18), dp(20), dp(14), dp(20))
            background = if (frosted) glass.pane(22) else shape(deep, 22)
        }
    val avatar =
        FrameLayout(this).apply {
            background = shape(blend(deep, main, .13f), 99)
            clipToOutline = true
        }
    val data = p?.optString("avatar").orEmpty()
    if (data.isNotEmpty()) {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        avatar.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        )
    } else if (a == null) avatar.addView(icon(R.drawable.ic_profile, main, 13))
    else
        avatar.addView(
            medium(
                    name.take(
                        if (name.isNotEmpty()) Character.charCount(name.codePointAt(0)) else 0
                    ),
                    24,
                    main,
                )
                .apply { gravity = Gravity.CENTER }
        )
    card.addView(avatar, dp(52), dp(52))
    val info = column().apply { setPadding(dp(13), 0, dp(5), 0) }
    info.addView(
        medium(name, 20, main).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    )
    val login = p?.optString("loginName").orEmpty()
    val sub =
        if (a == null) server.ifEmpty { "使用服务器地址或局域网发现" }
        else
            listOfNotNull(
                    login.takeIf { it.isNotBlank() }?.let { "@" + it },
                    Uri.parse(a.endpoint).authority,
                )
                .joinToString("  ·  ")
    info.addView(
        text(sub, 12, soft).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
    )
    card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    if (click != null) {
        card.addView(icon(R.drawable.ic_next, soft, 2), dp(18), dp(18))
        press(card, name, 22, click)
    }
    return card
}

internal fun MainActivity.accountPage(): View {
    val a = Account.read(this)
    val body = column().apply { setPadding(dp(18), dp(4), dp(18), dp(18)) }
    body.addView(
        identityCard(a) {
            if (a == null) {
                val origin = Account.selectedServer(this)
                if (origin.isEmpty()) connectToServer("") else authenticationPage(origin)
            }
            else accountActions(a)
        }
    )
    val options = column().apply { background = shape(panel, 18) }
    body.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    if (a == null) {
        val origin = Account.selectedServer(this)
        if (origin.isNotEmpty())
            options.addView(settingRow("更换服务器", R.drawable.ic_server, green) { connectToServer(origin) })
        options.addView(settingRow("发现局域网服务器", R.drawable.ic_server, green) { findServer() })
    } else {
        options.addView(settingRow("连接地址", R.drawable.ic_server, green) { connectToServer(a.endpoint) })
        options.addView(settingRow("立即同步", R.drawable.ic_sync, green) { syncNow(a) })
        options.addView(
            settingRow("待提交与冲突", R.drawable.ic_clock, groupInk(SettingGroup.CONNECTION)) {
                pendingScreen()
            }
        )
    }
    return body
}

private fun MainActivity.syncNow(a: Account) {
    work(Store.net, { Sync(this, db, a).all() }) {
        message("同步完成")
        reload()
    }
}

private fun MainActivity.accountActions(a: Account) {
    val p = AccountProfiles.read(this, a)
    detailSheet("我的账号") { box, dialog ->
        box.addView(identityCard(a, null))
        fun option(label: String, icon: Int, fn: () -> Unit) {
            box.addView(
                settingRow(label, icon, green) {
                    dialog.dismiss()
                    fn()
                }
            )
        }
        option("编辑昵称", R.drawable.ic_profile) {
            detailSheet("编辑昵称") { editor, sheet ->
                val name = field("显示昵称", p.optString("displayName"))
                editor.addView(name)
                editor.addView(
                    sheetPrimaryButton("保存昵称") {
                        val value = name.text.toString().trim()
                        work(
                            Store.net,
                            {
                                AccountProfiles.update(
                                    this,
                                    a,
                                    p.optString("revision"),
                                    "displayName",
                                    value,
                                )
                            },
                        ) {
                            sheet.dismiss()
                            reload()
                        }
                    }
                )
            }
        }
        option("从相册选择头像", R.drawable.ic_photo) {
            avatarRequest =
                Bundle().apply {
                    putString("origin", a.origin)
                    putString("user", a.user)
                    putString("revision", p.optString("revision"))
                }
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                AVATAR_PICKER,
            )
        }
        if (p.optString("avatarVersion").isNotEmpty())
            option("恢复文字头像", R.drawable.ic_undo) {
                work(
                    Store.net,
                    { AccountProfiles.update(this, a, p.optString("revision"), "avatar", "") },
                ) {
                    reload()
                }
            }
        option("立即同步", R.drawable.ic_sync) { syncNow(a) }
        option("重新登录", R.drawable.ic_profile) { authenticationPage(a.endpoint, a) }
        option("待提交与冲突", R.drawable.ic_clock) { pendingScreen() }
        option("退出实例账号", R.drawable.ic_exit) {
            calendarDialog()
                .setTitle("退出 @" + p.optString("loginName") + "？")
                .setMessage("退出后清理本机的共享副本，保留未提交的草稿。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ ->
                    work(Store.net, { Sync.logout(this, db, a) }) { reload() }
                }
                .show()
        }
    }
}

internal const val AVATAR_PICKER = 7453

internal fun MainActivity.savePickedAvatar(uri: Uri, target: Bundle) {
    val a = Account.read(this) ?: return
    if (a.origin != target.getString("origin") || a.user != target.getString("user")) return
    work(
        Store.net,
        {
            AccountProfiles.update(
                this,
                a,
                target.getString("revision").orEmpty(),
                "avatar",
                AccountProfiles.photo(this, uri),
            )
        },
    ) {
        reload()
    }
}

internal fun MainActivity.sheetPrimaryButton(label: String, click: () -> Unit) =
    medium(label, 16, onColor(green)).apply {
        minimumHeight = dp(48)
        gravity = Gravity.CENTER
        background = shape(green, 14)
        press(this, label, 14, click)
    }
