package org.timepin.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import org.json.JSONObject

internal const val DRAFT_DOCUMENT = 5104

private fun stateLabel(state: String) = when (state) {
    "pending" -> "等待同步"
    "revoked" -> "授权已撤销"
    "forbidden" -> "修改未获授权"
    "signed_out" -> "退出时保留"
    "conflict" -> "版本冲突"
    "needs_review" -> "副本已重建，请核对"
    else -> "需要核对 · $state"
}

fun MainActivity.pendingScreen() {
    val account = Account.read(this) ?: return message("登录原账号后可查看本机草稿")
    val commands = DeviceCommands(this)
    detailSheet("待提交与冲突") { box, dialog ->
        fun loadPage(after: Long) {
            work(fn = {
                require(Account.read(this) == account) { "账号已切换，请重新打开" }
                commands.run("drafts.list", obj("after" to after))
            }) { page ->
                if (!dialog.isShowing || Account.read(this) != account) return@work
                val drafts = page.getJSONArray("drafts").objects()
                if (drafts.isEmpty() && after == 0L) box.addView(text("没有待处理的修改", 15, muted))
                for (draft in drafts) {
                    val seq = draft.getLong("seq")
                    val label = draft.getString("name") + " · " + stateLabel(draft.getString("state")) + "  #" + seq
                    box.addView(button(label) {
                        openDraft(account, seq) { dialog.dismiss(); pendingScreen() }
                    })
                }
                if (page.has("next")) {
                    val more = button("更多草稿") {}
                    more.setOnClickListener { box.removeView(more); loadPage(page.getLong("next")) }
                    box.addView(more)
                }
            }
        }
        loadPage(0)
    }
}

private fun MainActivity.openDraft(account: Account, seq: Long, onResolved: () -> Unit) {
    work(fn = {
        require(Account.read(this) == account) { "账号已切换，请重新打开" }
        DeviceCommands(this).run("drafts.detail", obj("seq" to seq))
    }) { draft ->
        if (Account.read(this) != account) return@work
        detailSheet("本机草稿 #" + seq) { box, dialog ->
            box.addView(medium(draft.getString("name") + " · " + stateLabel(draft.getString("state")), 16))
            val raw = draft.getString("input")
            val preview = if (raw.codePointCount(0, raw.length) > 1200)
                raw.substring(0, raw.offsetByCodePoints(0, 1200)) + "\n…（保存文件可查看全文）"
                else runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)
            box.addView(button("保存草稿文件") {
                draftExport = Bundle().apply {
                    putString("origin", account.origin)
                    putString("user", account.user)
                    putLong("seq", seq)
                }
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "timepin-draft-$seq.json"), DRAFT_DOCUMENT)
            })
            box.addView(text(preview, 14).apply { setTextIsSelectable(true) })
            if (draft.has("receiptPreview")) box.addView(text("服务端回执\n" + draft.getString("receiptPreview"), 14, muted))
            val resolution = draft.optJSONObject("resolution") ?: return@detailSheet
            fun resolve(choice: String) {
                val args = obj("calendarId" to draft.getString("calendarId"), "revision" to resolution.getString("revision"),
                    "choice" to choice, "operationId" to uid())
                work(Store.net, {
                    require(Account.read(this) == account) { "账号已切换，请重新打开" }
                    DeviceCommands(this).run("sync.resolve", args)
                }) { dialog.dismiss(); reload(); onResolved(); message("冲突已处理") }
            }
            box.addView(button("采用远端版本") {
                calendarDialog().setMessage("放弃这本日历全部 " + resolution.getInt("pendingCount") + " 项本机草稿并重建副本？")
                    .setNegativeButton("取消", null).setPositiveButton("放弃并重建") { _, _ -> resolve("remote") }.show()
            })
            if (resolution.getBoolean("canRetryLocal")) box.addView(button("采用我的改班") { resolve("local") })
        }
    }
}

internal fun MainActivity.exportDraft(target: Bundle, uri: Uri) {
    work(fn = {
        val a = Account.read(this) ?: error("请先登录原账号")
        require(a.origin == target.getString("origin") && a.user == target.getString("user")) { "账号已切换，请重新打开" }
        val temp = File.createTempFile("draft-", ".json", cacheDir)
        try {
            temp.bufferedWriter().use { writer ->
                var offset = 0
                val size = 65536
                while (true) {
                    val chunk = db.rows().accountDraft(a.origin, a.user, target.getLong("seq"), offset, size)
                        ?: error("草稿已经处理")
                    writer.write(chunk.input)
                    if (chunk.input.codePointCount(0, chunk.input.length) < size) break
                    offset += size
                }
            }
            require(Account.read(this) == a) { "账号已切换，请重新打开" }
            requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "无法写入文件" }.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            }
        } finally { temp.delete() }
    }) { message("草稿已保存") }
}
