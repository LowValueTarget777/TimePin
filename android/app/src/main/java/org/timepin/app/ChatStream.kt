package org.timepin.app

import java.io.InputStream
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A single chat request. Cancellation closes the transport and incomplete tool calls are discarded. */
class ChatStream(private val update: (String) -> Unit = {}) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var cancelled = false
        private set
    fun attach(conn: HttpURLConnection) { connection = conn; check(!cancelled) { "已停止接收回复" } }
    fun detach() { connection = null }
    fun cancel() {
        cancelled = true
        val conn = connection
        if (conn != null) Thread { conn.disconnect() }.start()
    }

    fun read(input: InputStream, provider: Boolean): JSONObject {
        val text = StringBuilder()
        val thought = StringBuilder()
        val tools = JSONArray()
        var finish = ""
        var kind = ""
        val data = StringBuilder()
        var total = 0
        val bounded = object : java.io.FilterInputStream(input) {
            var bytesRead = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) bytesRead += n
                require(bytesRead <= 4 * 1024 * 1024) { "回复过长" }
                return n
            }
        }
        val reader = bounded.bufferedReader(Charsets.UTF_8)
        var lastPaint = -100L
        fun append(fragment: String) {
            if (fragment.isEmpty()) return
            text.append(fragment)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPaint >= 32) {
                update(text.toString())
                lastPaint = now
            }
        }
        while (!cancelled) {
            val line = reader.readLine() ?: error("流式回复中断，请重试")
            total += line.length + 1
            require(total <= 2 * 1024 * 1024) { "回复过长" }
            when {
                line.startsWith("event:") -> kind = line.substringAfter(':').trim()
                line.startsWith("data:") -> data.append(line.substringAfter(':').removePrefix(" ")).append('\n')
                line.isEmpty() && data.isNotEmpty() -> {
                    val frame = data.toString().trimEnd()
                    data.setLength(0)
                    if (!provider) {
                        val value = JSONTokener(frame).nextValue()
                        when (kind) {
                            "result" -> return value as JSONObject
                            "error" -> error(value.toString())
                            "status" -> { text.setLength(0); update(value.toString()) }
                            "delta" -> { append(value as String) }
                        }
                    } else if (frame == "[DONE]") {
                        require(finish == if (tools.length()>0) "tool_calls" else "stop") { "模型回复不完整" }
                        for (call in tools.objects()) {
                            val fn = call.getJSONObject("function")
                            require(call.getString("type") == "function" && call.getString("id").isNotBlank()
                                && fn.getString("name").isNotBlank() && fn.has("arguments")) { "模型工具调用不完整" }
                        }
                        val answer = obj("role" to "assistant", "content" to text.toString())
                        if (tools.length()>0) answer.put("tool_calls",tools)
                        if (thought.isNotEmpty()) answer.put("reasoning_content",thought.toString())
                        return obj("choices" to JSONArray().put(obj("message" to answer)))
                    } else {
                        val chunk = JSONObject(frame)
                        require(!chunk.has("error")) { "模型返回错误" }
                        for (choice in chunk.array("choices").objects()) {
                            if (choice.optInt("index") != 0) continue
                            require(finish.isEmpty()) { "无效的模型片段" }
                            if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason")
                            val d = choice.optJSONObject("delta") ?: continue
                            if (!d.isNull("content")) { append(d.getString("content")) }
                            if (!d.isNull("reasoning_content")) thought.append(d.getString("reasoning_content"))
                            for (p in d.array("tool_calls").objects()) {
                                val i=p.getInt("index")
                                require(i in 0..tools.length() && i<128)
                                if (i==tools.length()) tools.put(obj("id" to "", "type" to "", "function" to obj()))
                                val call=tools.getJSONObject(i)
                                for (k in listOf("id","type")) if (!p.isNull(k)) call.put(k,call.optString(k)+p.getString(k))
                                val fn=call.getJSONObject("function")
                                p.optJSONObject("function")?.let { part ->
                                    for (k in listOf("name","arguments")) if (!part.isNull(k)) fn.put(k,fn.optString(k)+part.getString(k))
                                }
                            }
                        }
                    }
                    kind = ""
                }
            }
        }
        error("已停止接收回复")
    }
}
