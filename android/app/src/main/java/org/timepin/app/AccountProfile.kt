package org.timepin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONObject

internal object AccountProfiles {
    private fun prefs(c: Context) = c.getSharedPreferences("personal-profile", Context.MODE_PRIVATE)

    fun read(c: Context, a: Account): JSONObject {
        val p = prefs(c)
        if (p.getString("origin", null) == a.origin && p.getString("user", null) == a.user)
            p.getString("profile", null)?.let {
                return JSONObject(it)
            }
        return obj(
            "loginName" to "",
            "displayName" to "我的账号",
            "revision" to "",
            "avatarVersion" to "",
        )
    }

    fun remember(c: Context, a: Account, profile: JSONObject) {
        if (Account.read(c) == a)
            prefs(c)
                .edit()
                .putString("origin", a.origin)
                .putString("user", a.user)
                .putString("profile", profile.toString())
                .apply()
    }

    fun clear(c: Context) = prefs(c).edit().clear().apply()

    fun sync(c: Context, a: Account, state: JSONObject): Boolean {
        val login = state.getJSONObject("user").getString("name")
        val summary =
            state.optJSONObject("profile")
                ?: obj(
                    "loginName" to login,
                    "displayName" to login,
                    "revision" to "",
                    "avatarVersion" to "",
                )
        val old = read(c, a)
        if (
            old.optString("revision") == summary.optString("revision") &&
                old.optString("loginName") == login
        )
            return false
        val version = summary.optString("avatarVersion")
        val profile =
            when {
                version.isEmpty() -> summary
                old.optString("avatarVersion") == version && old.has("avatar") ->
                    summary.put("avatar", old.getString("avatar"))
                else -> a.api("manage", obj("op" to "profile.detail"))
            }
        remember(c, a, profile)
        return true
    }

    fun update(c: Context, a: Account, revision: String, field: String, value: String) {
        require(Account.read(c) == a) { "账号已切换，请重新打开资料" }
        require(revision.isNotEmpty()) { "请连接支持账号资料的服务器后重试" }
        try {
            a.api(
                "manage",
                obj(
                    "op" to "profile.update",
                    "operationId" to uid(),
                    "revision" to revision,
                    field to value,
                ),
            )
        } catch (e: HttpFailure) {
            if (e.status != 409) throw e
            remember(c, a, a.api("manage", obj("op" to "profile.detail")))
            error("资料已在另一端修改，请重新打开后编辑")
        }
        remember(c, a, a.api("manage", obj("op" to "profile.detail")))
    }

    internal fun sampledImage(c: Context, uri: Uri): Bitmap =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(c.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1024) decoder.setTargetSampleSize((longest + 1023) / 1024)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else legacyBitmap(c, uri)

    fun photo(c: Context, uri: Uri): String {
        val bitmap = sampledImage(c, uri)
        val side = minOf(bitmap.width, bitmap.height)
        val cropped =
            Bitmap.createBitmap(
                bitmap,
                (bitmap.width - side) / 2,
                (bitmap.height - side) / 2,
                side,
                side,
            )
        val thumb = Bitmap.createScaledBitmap(cropped, 192, 192, true)
        return ByteArrayOutputStream().use {
            check(thumb.compress(Bitmap.CompressFormat.JPEG, 85, it))
            Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun legacyBitmap(c: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val options = BitmapFactory.Options()
        while (
            maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1024
        ) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        return c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法读取这张图片")
    }
}
