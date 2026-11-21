package org.timepin.app

import android.app.Application
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class TimePin : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Reminders.enabled(this)) Reminders.watch(this)
        Contract.defs =
            assets.open("schema.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("\$defs")
            }
    }
}

object Contract {
    lateinit var defs: JSONObject
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun command(c: JSONObject) = check(defs.getJSONObject("op." + c.getString("op")), c)

    fun check(s: JSONObject, v: Any) {
        if (s.has("\$ref")) {
            check(defs.getJSONObject(s.getString("\$ref").substringAfterLast('/')), v)
            return
        }
        s.optJSONArray("oneOf")?.let {
            require(it.objects().count { shape -> runCatching { check(shape, v) }.isSuccess } == 1)
            return
        }
        if (s.has("const"))
            require(
                s.get("const").toString() == v.toString() &&
                    (s.get("const") is Number) == (v is Number)
            )
        s.optJSONArray("enum")?.let { require(v in it.strings()) }
        when (s.optString("type")) {
            "object" -> {
                require(v is JSONObject)
                val props = s.getJSONObject("properties")
                require(s.array("required").strings().all(v::has))
                for (k in v.keys()) check(props.getJSONObject(k), v.get(k))
            }
            "array" -> {
                require(
                    v is JSONArray &&
                        v.length() in s.optInt("minItems", 0)..s.optInt("maxItems", Int.MAX_VALUE)
                )
                val seen = mutableSetOf<String>()
                for (i in 0 until v.length()) {
                    val x = v.get(i)
                    check(s.getJSONObject("items"), x)
                    if (s.optBoolean("uniqueItems")) require(seen.add(canonical(x)))
                }
            }
            "string" -> {
                require(
                    v is String &&
                        v.codePointCount(0, v.length) in
                            s.optInt("minLength", 0)..s.optInt("maxLength", Int.MAX_VALUE)
                )
                if (s.has("pattern"))
                    require(
                        patterns
                            .getOrPut(s.getString("pattern")) { Regex(s.getString("pattern")) }
                            .matches(v)
                    )
            }
            "integer" -> {
                require(
                    v is Number &&
                        v.toDouble() == v.toLong().toDouble() &&
                        v.toLong() in
                            s.optLong("minimum", Long.MIN_VALUE)..s.optLong(
                                    "maximum",
                                    Long.MAX_VALUE,
                                )
                )
            }
            "boolean" -> require(v is Boolean)
        }
    }
}

fun hash(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

fun result(code: String = "ok", vararg fields: Pair<String, Any?>) = obj("code" to code, *fields)

fun requireOk(r: JSONObject): JSONObject {
    check(r.optString("code", "ok") == "ok") { r.optString("detail", r.optString("code")) }
    return r
}
