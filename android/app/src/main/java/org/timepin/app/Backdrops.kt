package org.timepin.app

import android.content.Context
import android.graphics.*
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Backdrop(val mode: String, val color: String = "#DCE7F4", val photo: String = "") {
    fun detail() = obj("mode" to mode, "color" to color, "hasPhoto" to photo.isNotEmpty())
}

/** Device-private selections. Widget IDs and the app have separate remembered photos. */
internal object Backdrops {
    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "backdrops")
    private fun file(c: Context, id: Int, key: String) = File(dir(c), "$id-$key.jpg")
    private val artCache = android.util.LruCache<String, Bitmap>(8)
    val colors = listOf("#DCE7F4", "#B8D8D0", "#D8C9EB", "#F2D8C4", "#294658", "#192333")
    val colorNames = listOf("冰蓝","薄荷","丁香","杏色","深海","墨黑")

    fun read(c: Context, id: Int = 0): Backdrop {
        val p = prefs(c)
        return Backdrop(p.getString("back-mode-$id", if (id == 0) "solid" else "follow")!!,
            p.getString("back-color-$id", colors.first())!!, p.getString("back-photo-$id", "")!!)
    }

    fun resolve(c: Context, id: Int): Backdrop = read(c, id).let { if (it.mode == "follow") read(c) else it }

    fun encode(bitmap: Bitmap): ByteArray {
        val scale = minOf(1f, 640f / max(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()), true)
        return ByteArrayOutputStream().use {
            check(small.compress(Bitmap.CompressFormat.JPEG, 76, it))
            it.toByteArray()
        }
    }

    @Synchronized fun save(c: Context, id: Int, args: JSONObject) {
        val old = read(c, id)
        val mode = args.getString("mode")
        require(mode in setOf("frost", "photo", "solid") || mode == "follow" && id > 0) { "背景模式无效" }
        val color = args.optString("color", old.color).uppercase()
        require(Regex("#[0-9A-F]{6}").matches(color)) { "请输入六位颜色值" }
        var key = old.photo
        if (args.has("image")) {
            require(mode == "photo") { "请选择照片背景" }
            val data = Base64.decode(args.getString("image"), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth in 1..1280 && bounds.outHeight in 1..1280) { "图片长边需在 1280 像素以内" }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size)) { "无法读取图片" }
            val bytes = encode(bitmap)
            key = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            dir(c).mkdirs()
            val atomic = AtomicFile(file(c, id, key))
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        require(mode != "photo" || key.isNotEmpty() && file(c,id,key).isFile) { "请先选择一张照片" }
        check(prefs(c).edit().putString("back-mode-$id",mode).putString("back-color-$id",color)
            .putString("back-photo-$id",key).commit()) { "背景保存失败" }
        if (old.photo.isNotEmpty() && old.photo != key) file(c,id,old.photo).delete()
    }

    @Synchronized fun drop(c: Context, id: Int) {
        val key = read(c,id).photo
        check(prefs(c).edit().remove("back-mode-$id").remove("back-color-$id").remove("back-photo-$id").commit())
        if (key.isNotEmpty()) file(c,id,key).delete()
    }

    /** Bake transparent corners into the texture for launchers that draw without outline clipping. */
    @Synchronized fun widgetTexture(c: Context, spec: Backdrop, source: Int, widthDp: Int, heightDp: Int): Bitmap {
        val key = "widget/$source/$spec/$widthDp/$heightDp"
        artCache.get(key)?.let { return it }
        val w = 160
        val h = (w.toFloat() * heightDp / widthDp).roundToInt().coerceIn(28, 320)
        val texture = art(c, spec, w, h, pane = true, id = source)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radiusDp = c.resources.getDimension(R.dimen.widget_corner_radius) / c.resources.displayMetrics.density
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rounded ->
            Canvas(rounded).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(),
                radiusDp * w / widthDp, radiusDp * h / heightDp, paint)
            artCache.put(key, rounded)
        }
    }

    /** Crop once per aspect and source. A miniature filtered texture supplies the glass blur. */
    @Synchronized fun art(c: Context, spec: Backdrop, w: Int, h: Int, pane: Boolean, id: Int = 0,
                         draft: Bitmap? = null): Bitmap {
        val key = "$id/$spec/$w/$h/$pane"
        if (draft == null) artCache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (spec.mode == "solid") canvas.drawColor(Color.parseColor(spec.color))
        else {
            val image = if (spec.mode == "photo") draft ?: BitmapFactory.decodeFile(file(c,id,spec.photo).path) ?: frostArtwork else frostArtwork
            val scale = max(w.toFloat()/image.width,h.toFloat()/image.height)
            val dx = (image.width - w/scale) / 2
            val dy = (image.height - h/scale) / 2
            val crop = Rect(dx.toInt(),dy.toInt(),(image.width-dx).roundToInt(),(image.height-dy).roundToInt())
            // Downsample before upscaling. Never capture the launcher window or read its wallpaper.
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            if (pane) {
                val blurWidth = minOf(24,w)
                val tiny = Bitmap.createBitmap(blurWidth,max(1,h*blurWidth/w),Bitmap.Config.ARGB_8888)
                Canvas(tiny).drawBitmap(image,crop,Rect(0,0,tiny.width,tiny.height),paint)
                canvas.drawBitmap(tiny,null,Rect(0,0,w,h),paint)
                paint.shader = LinearGradient(0f,0f,w.toFloat(),h.toFloat(),0xbaf8fcff.toInt(),0x91f0f4ff.toInt(),Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
                canvas.drawColor(0xe8ffffff.toInt(),PorterDuff.Mode.DST_IN)
            } else {
                canvas.drawBitmap(image,crop,Rect(0,0,w,h),paint)
                if (spec.mode == "photo") canvas.drawColor(0x66f1f7ff)
            }
        }
        if (draft == null) artCache.put(key,out)
        return out
    }
}
