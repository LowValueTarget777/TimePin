package org.timepin.app

import android.os.SystemClock
import java.net.*
import java.util.Collections
import org.json.JSONObject

internal fun discoverCalendarServers(): List<String> {
    val targets = Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.toSet()
    val found = linkedSetOf<String>()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val probe = "TIMEPIN-DISCOVER/1".toByteArray()
        for (address in targets + InetAddress.getByName("255.255.255.255"))
            runCatching { socket.send(DatagramPacket(probe, probe.size, address, 41873)) }
        val until = SystemClock.elapsedRealtime() + 1700
        while (SystemClock.elapsedRealtime() < until && found.size < 16) {
            socket.soTimeout = (until - SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
            val packet = DatagramPacket(ByteArray(256), 256)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
            if (packet.port != 41873 || !packet.address.isSiteLocalAddress) continue
            val data = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            val port = data.optInt("port")
            if (data.optString("service") == "timepin/1" && port in 1..65535)
                found.add("http://" + packet.address.hostAddress + ":" + port)
        }
    }
    return found.sorted()
}

internal fun MainActivity.connectToServer(initial: String) {
    detailSheet("连接日历服务器") { box, dialog ->
        box.addView(text("服务器地址", 14, muted))
        val url = field("例如 https://calendar.example.com", initial).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(url)
        box.addView(text("使用公网 HTTP 地址时，账号和日历数据会明文传输。", 13, muted))
        val status = text("", 13, muted)
        box.addView(status)
        lateinit var submit: android.widget.TextView
        submit = sheetPrimaryButton("连接服务器") {
            val address = url.text.toString().trim()
            if (address.isEmpty()) url.error = "请输入服务器地址"
            else {
                submit.isEnabled = false
                dialog.setCancelable(false)
                status.text = "正在验证服务器…"
                work(Store.net, { runCatching { Account.connect(this, address) } }) { result ->
                    dialog.setCancelable(true)
                    result.onSuccess {
                        dialog.dismiss()
                        reload()
                        message("服务器已连接")
                    }.onFailure {
                        submit.isEnabled = true
                        status.text = it.message ?: "连接失败，请检查地址和网络"
                    }
                }
            }
        }
        box.addView(submit)
    }
}

internal fun MainActivity.findServer() {
    val box = padded()
    val status = android.widget.TextView(this).apply { text="正在寻找同一局域网的 TimePin 服务器…" }
    box.addView(status)
    val dialog = calendarDialog().setTitle("附近的日历服务器").setView(box)
        .setNegativeButton("取消", null).setNeutralButton("手动输入") { _,_ ->
            connectToServer(prefs.getString("last-url","").orEmpty())
        }.show()
    work(Store.net, { discoverCalendarServers() }) { urls ->
        if (dialog.isShowing) {
            status.text = if(urls.isEmpty()) "未发现服务器。请检查 Wi-Fi，或手动输入地址。" else "选择要连接的服务器"
            urls.forEach { url -> box.addView(calendarButton(url) {
                if (box.isEnabled) {
                    box.isEnabled = false
                    dialog.setCancelable(false)
                    work(Store.net, { runCatching { Account.connect(this, url) } }) { result ->
                        dialog.setCancelable(true)
                        box.isEnabled = true
                        result.onSuccess {
                            dialog.dismiss()
                            reload()
                            message("服务器已连接")
                        }.onFailure { status.text = it.message ?: "连接失败，请重试" }
                    }
                }
            }) }
        }
    }
}
