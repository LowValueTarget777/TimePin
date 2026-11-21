package org.timepin.app

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

private fun Book.sourceLabel() =
    when {
        publication.isNotEmpty() -> "等待同步"
        origin.isEmpty() -> "本机日历"
        else -> "已连接"
    }

internal fun MainActivity.calendarDirectory(): View {
    val body = padded()
    val top = row()
    top.addView(text("共 ${books.size} 本", 13, muted), LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(button("＋ 新建日历") { createBookForm() })
    body.addView(top)
    if (books.isEmpty()) {
        body.addView(text("暂无可用日历", 14, muted).apply { setPadding(0, dp(16), 0, 0) })
        return body
    }
    val list =
        column().apply {
            background = shape(panel, 20)
            clipToOutline = true
        }
    body.addView(list)
    for (b in books) {
        val tint = Color.parseColor(b.color)
        val line =
            row().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minimumHeight = dp(68)
            }
        line.addView(
            icon(R.drawable.ic_calendar, readableAccent(tint, panel, ink), 8).apply {
                background = shape(blend(panel, tint, .14f), 12)
            },
            dp(38),
            dp(38),
        )
        val info = column().apply { setPadding(dp(13), 0, dp(6), 0) }
        info.addView(medium(b.name, 16))
        info.addView(text(b.sourceLabel(), 12, muted))
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        if (active == b.id)
            line.addView(
                text("当前", 12, green).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = shape(blend(panel, green, .1f), 8)
                }
            )
        line.addView(icon(R.drawable.ic_next, muted, 4), dp(22), dp(22))
        press(line, "打开${b.name}的日历设置", 16) { bookSheet(b) }
        list.addView(line)
    }
    return body
}

private fun MainActivity.bookSheet(book: Book) =
    detailSheet("日历设置") { box, sheet ->
        val title = medium(book.name, 24)
        box.addView(title)
        val role =
            when (book.role) {
                "manager" -> "可管理"
                "editor" -> "可编辑"
                "contributor" -> "可投稿"
                "reader" -> "只读"
                else -> "无访问权限"
            }
        box.addView(
            text(book.sourceLabel() + "  ·  " + role, 13, muted).apply {
                setPadding(0, dp(2), 0, dp(13))
            }
        )
        fun option(
            label: String,
            icon: Int,
            tint: Int = green,
            next: Boolean = false,
            click: () -> Unit,
        ) {
            box.addView(settingRow(label, icon, tint, showNext = next, action = click))
        }
        if (book.role != "none") {
            if (book.id != active)
                option("设为当前日历", R.drawable.ic_check) {
                    sheet.dismiss()
                    active = book.id
                    reload()
                }
            else
                box.addView(text("当前日历", 14, green).apply { setPadding(dp(13), dp(10), 0, dp(10)) })
            val visible =
                android.widget.Switch(this).apply {
                    text = "在月历中显示"
                    isChecked = book.visible
                    setTextColor(ink)
                    textSize = 15f
                    minHeight = dp(56)
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener {
                        val enabled = isChecked
                        isEnabled = false
                        work(
                            fn = {
                                runCatching {
                                    DeviceCommands(this@bookSheet)
                                        .run(
                                            "calendar.visibility",
                                            obj(
                                                "calendarId" to book.id,
                                                "enabled" to enabled,
                                                "operationId" to uid(),
                                            ),
                                        )
                                }
                            }
                        ) { result ->
                            isEnabled = true
                            result
                                .onSuccess { reload() }
                                .onFailure {
                                    isChecked = !enabled
                                    message(it.message ?: "保存失败")
                                }
                        }
                    }
                }
            box.addView(visible)
        }
        if (book.role == "manager" && book.publication.isEmpty()) {
            if (book.origin.isNotEmpty()) {
                option("分享与成员权限", R.drawable.ic_people, next = true) {
                    sheet.dismiss()
                    openCollaboration(book)
                }
            } else {
                box.addView(text("登录服务器并同步后可分享这本日历", 13, muted))
            }
            option("名称与颜色", R.drawable.ic_sliders, next = true) {
                sheet.dismiss()
                editBook(book)
            }
            box.addView(
                View(this).apply { setBackgroundColor(blend(panel, muted, .18f)) },
                -1,
                dp(1),
            )
            option(
                "删除这本日历",
                R.drawable.ic_trash,
                if (dark) 0xfff3aa9e.toInt() else 0xffa23730.toInt(),
            ) {
                calendarDialog()
                    .setTitle("删除「${book.name}」？")
                    .setMessage(
                        if (book.origin.isEmpty()) "这本日历及其中的日程将从 TimePin 删除。"
                        else "服务器上的这本日历及日程将永久删除，成员和只读链接也会失去访问权限。"
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认删除") { _, _ ->
                        val id = uid()
                        work(
                            Store.net,
                            {
                                DeviceCommands(this)
                                    .run(
                                        "calendar.delete",
                                        obj("calendarId" to book.id, "operationId" to id),
                                    )
                            },
                        ) {
                            sheet.dismiss()
                            reload()
                        }
                    }
                    .show()
            }
        }
    }

private fun MainActivity.editBook(book: Book) =
    detailSheet("名称与颜色") { box, sheet ->
        val name = field("日历名称", book.name)
        val color = field("默认颜色", book.color)
        box.addView(name)
        box.addView(color)
        val choices = row()
        for (value in listOf("#29885F", "#357CB3", "#8262A7", "#CC7441", "#BF5166")) {
            val cell = android.widget.FrameLayout(this)
            val mark = View(this).apply { background = shape(Color.parseColor(value), 20) }
            cell.addView(
                mark,
                android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
            press(cell, "选择颜色 $value", 12) { color.setText(value) }
            choices.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        box.addView(choices)
        box.addView(
            sheetPrimaryButton("保存设置") {
                val args =
                    obj(
                        "calendarId" to book.id,
                        "operationId" to uid(),
                        "name" to name.text.toString().trim(),
                        "color" to color.text.toString().trim(),
                    )
                work(Store.net, { DeviceCommands(this).run("calendar.update", args) }) {
                    sheet.dismiss()
                    reload()
                }
            }
        )
    }
