package org.timepin.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class Vault(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("connections", Context.MODE_PRIVATE)

    private fun key(): SecretKey =
        synchronized(Vault::class.java) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.getKey("timepin.credentials", null) as? SecretKey
                ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                                "timepin.credentials",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes("GCM")
                            .setEncryptionPaddings("NoPadding")
                            .build()
                    )
                    generateKey()
                }
        }

    fun set(name: String, data: JSONObject?) {
        if (data == null) {
            check(prefs.edit().remove(name).commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    fun get(name: String): JSONObject? {
        val encrypted = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(name.toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
}

class HttpFailure(val status: Int, msg: String) : Exception(msg)

fun baseUrl(value: String, calendarServer: Boolean = false): String {
    val s = value.trim().trimEnd('/')
    val u = URI(s)
    require(u.host != null && u.userInfo == null && u.query == null && u.fragment == null) {
        "接口地址无效"
    }
    require(
        u.scheme == "https" ||
            (u.scheme == "http" &&
                (calendarServer || u.host in listOf("localhost", "[::1]") ||
                    u.host.endsWith(".local") ||
                    u.host.matches(
                        Regex(
                            "(127|10)\\.[0-9.]+|192\\.168\\.[0-9.]+|172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9.]+"
                        )
                    )))
    ) {
        "公网服务请使用 HTTPS，局域网可用 HTTP"
    }
    return s
}

data class WireReply(val body: JSONObject, val cookie: String = "")

fun request(
    base: String,
    path: String,
    input: JSONObject? = null,
    cookie: String = "",
    bearer: String = "",
    stream: ChatStream? = null,
    calendarServer: Boolean = false,
    connectTimeoutMs: Int = 10000,
    readTimeoutMs: Int = 60000,
): WireReply {
    val conn = URI(baseUrl(base, calendarServer) + path).toURL().openConnection() as HttpURLConnection
    try {
        stream?.attach(conn)
        conn.instanceFollowRedirects = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", if (stream != null) "text/event-stream" else "application/json")
        if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
        if (bearer.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer " + bearer)
        if (input != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = input.toString().toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val status = conn.responseCode
        if (status !in 200..299) throw HttpFailure(status, "接口返回 HTTP " + status)
        if (stream != null && conn.contentType.orEmpty().startsWith("text/event-stream"))
            return WireReply(conn.inputStream.use { stream.read(it, path.endsWith("/chat/completions")) })
        val bytes =
            conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    require(out.size() + n <= 32 * 1024 * 1024) { "响应过大" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        val token = conn.getHeaderField("Set-Cookie")?.substringBefore(';') ?: ""
        return WireReply(JSONObject(String(bytes, Charsets.UTF_8)), token)
    } finally {
        stream?.detach()
        conn.disconnect()
    }
}

data class Account(val origin: String, val user: String, val cookie: String, val endpoint: String = origin) {
    fun api(path: String, data: JSONObject? = null, stream: ChatStream? = null) =
        request(endpoint, "/api/v1/" + path, data, cookie = cookie, stream = stream, calendarServer = true).body

    fun json() = obj("origin" to origin, "user" to user, "cookie" to cookie, "endpoint" to endpoint)

    companion object {
        fun selectedServer(ctx: Context): String =
            read(ctx)?.endpoint ?: ctx.getSharedPreferences("display", Context.MODE_PRIVATE).getString("last-url", "").orEmpty()

        fun connect(ctx: Context, url: String): String {
            val origin = baseUrl(url, calendarServer = true)
            val prior = read(ctx)
            val challenge = java.util.UUID.randomUUID().toString().replace("-", "")
            val compare = prior != null && origin != prior.origin && origin != prior.endpoint
            val path = "/api/v1/instance" + if (compare) "?challenge=$challenge" else ""
            val info = request(origin, path, calendarServer = true).body
            require(info.optString("service") == "timepin/1") {
                "该地址不是 TimePin 日历服务器"
            }
            if (prior != null && prior.endpoint != origin) {
                if (compare) {
                    val proof = info.optString("proof")
                    require(proof.matches(Regex("[0-9a-f]{64}")) &&
                        proof == request(prior.endpoint, path, calendarServer = true).body.optString("proof")) {
                        "无法确认两个地址属于同一实例，请在原地址和新地址均可访问时重试"
                    }
                }
                Sync.changeEndpoint(ctx, prior, origin)
            }
            check(ctx.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putString("last-url", origin).commit())
            return origin
        }
        fun read(ctx: Context) =
            Vault(ctx).get("account")?.let {
                Account(it.getString("origin"), it.getString("user"), it.getString("cookie"), it.optString("endpoint", it.getString("origin")))
            }

        fun login(ctx: Context, url: String, name: String, password: String) =
            authenticate(ctx, url, name, password, null)

        fun register(ctx: Context, url: String, name: String, password: String, invite: String) =
            authenticate(ctx, url, name, password, invite)

        private fun authenticate(ctx: Context, url: String, name: String, password: String, invite: String?): Account {
            val base = baseUrl(url, calendarServer = true)
            val prior = read(ctx)
            require(prior == null || prior.endpoint == base && invite == null) { "请先退出当前账号" }
            val data = obj("name" to name, "password" to password)
            if (!invite.isNullOrBlank()) data.put("invite", invite)
            val r = request(base, if (invite == null) "/api/v1/login" else "/api/v1/register", data, calendarServer = true)
            require(r.cookie.startsWith("timepin_session="))
            val a = Account(prior?.origin ?: base, r.body.getString("id"), r.cookie, base)
            if (prior != null && prior.user != a.user) {
                runCatching { a.api("logout", JSONObject()) }
                error("请先退出当前账号")
            }
            Vault(ctx).set("account", a.json())
            ctx.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putString("last-url", base).apply()
            val loginName = r.body.optString("name", name)
            val profile = r.body.optJSONObject("profile") ?: obj(
                "loginName" to loginName, "displayName" to loginName,
                "revision" to "", "avatarVersion" to "")
            AccountProfiles.remember(ctx, a, profile)
            Sync.schedule(ctx)
            return a
        }
    }
}
