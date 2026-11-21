package org.timepin.app

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View

/** Stationary artwork shared by the wallpaper and the frosted panes of one activity. */
internal val frostArtwork by lazy {
    Bitmap.createBitmap(240, 432, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / 360f, bitmap.height / 648f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        fun gradient(x: Float, y: Float, xx: Float, yy: Float, vararg colors: Long) =
            LinearGradient(x, y, xx, yy, colors.map { it.toInt() }.toIntArray(), null, Shader.TileMode.CLAMP)

        paint.shader = gradient(0f, 0f, 270f, 648f, 0xffc3ddf1, 0xffcee9e8, 0xffd9d6f4, 0xffcadaee)
        canvas.drawRect(0f, 0f, 360f, 648f, paint)

        // Each fold has a lit edge and a shaded back face. Only the bare wallpaper
        // keeps the sharp edge; panels sample the low-pass texture below.
        fun fold(path: Path, fill: Shader) {
            paint.style = Paint.Style.FILL
            paint.shader = fill
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.35f
            paint.color = 0xbcffffff.toInt()
            canvas.drawPath(path, paint)
            paint.color = Color.WHITE
        }
        fold(Path().apply {
            moveTo(145f, -10f)
            cubicTo(210f, 56f, 105f, 147f, 213f, 211f)
            cubicTo(262f, 239f, 342f, 230f, 390f, 330f)
            lineTo(390f, -10f); close()
        }, gradient(110f, 25f, 370f, 197f, 0xfff6fbff, 0xff97bbdf, 0xffb4dbe8, 0xff80bcbf))
        fold(Path().apply {
            moveTo(386f, 53f)
            cubicTo(263f, 94f, 346f, 174f, 198f, 201f)
            cubicTo(58f, 224f, 81f, 336f, -18f, 362f)
            lineTo(-18f, 440f)
            cubicTo(115f, 415f, 80f, 282f, 231f, 242f)
            cubicTo(326f, 216f, 314f, 144f, 386f, 134f); close()
        }, gradient(70f, 220f, 251f, 333f, 0xffe7f7fb, 0xffabcfe0, 0xffb1d6de, 0xffe8f2ff))
        fold(Path().apply {
            moveTo(-15f, 391f)
            cubicTo(75f, 500f, 172f, 369f, 259f, 440f)
            cubicTo(312f, 483f, 272f, 576f, 385f, 616f)
            lineTo(385f, 670f); lineTo(-15f, 670f); close()
        }, gradient(85f, 402f, 235f, 650f, 0xfff1eafa, 0xffb8aeda, 0xffa7bce6, 0xffd7e4f7))
        fold(Path().apply {
            moveTo(-24f, 567f)
            cubicTo(76f, 568f, 62f, 482f, 174f, 498f)
            cubicTo(255f, 509f, 277f, 592f, 380f, 563f)
            lineTo(380f, 597f)
            cubicTo(254f, 641f, 229f, 555f, 157f, 543f)
            cubicTo(64f, 524f, 65f, 617f, -24f, 609f); close()
        }, gradient(52f, 535f, 230f, 610f, 0xffe6eeff, 0xffb0b9de, 0xfff3eafa, 0xffb5cbe5))
    }
}

internal class FrostGlass(private val app: MainActivity) {
    val selection = Backdrops.read(app)
    private val metrics = app.resources.displayMetrics
    private val artwork = Backdrops.art(app, selection, 240,
        (240f * metrics.heightPixels / metrics.widthPixels).toInt().coerceIn(100,600), pane = false)
    // Mip levels keep the backdrop diffuse even when a high-density launcher is in use.
    private val diffuse = listOf(120, 60, 30, 15).fold(artwork) { src, width ->
        Bitmap.createScaledBitmap(src, width, (width * artwork.height / artwork.width).coerceAtLeast(1), true)
    }
    val wallpaper = BitmapDrawable(app.resources, artwork).apply { gravity = Gravity.FILL; isFilterBitmap = true }

    fun pane(radius: Int): GradientDrawable = object : GradientDrawable(
        Orientation.TL_BR, if (selection.mode == "solid" && Color.luminance(Color.parseColor(selection.color)) < .42f)
            intArrayOf(0xedffffff.toInt(), 0xc6fafcff.toInt(), 0xd5edf6ff.toInt())
        else intArrayOf(0xb8ffffff.toInt(), 0x64fafcff, 0x85edf6ff.toInt())
    ) {
        private val shader = BitmapShader(diffuse, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.shader = shader }
        private val matrix = Matrix()
        private val frame = RectF()
        private val location = IntArray(2)
        private val rootLocation = IntArray(2)
        private val rounding = app.dp(radius).toFloat()
        private val lastViewport = Rect()
        init {
            cornerRadius = rounding
            setStroke(app.dp(1), 0xcaffffff.toInt())
        }
        override fun draw(canvas: Canvas) {
            val host = callback as? View
            if (host != null && app.root.width > 0) {
                app.root.getLocationOnScreen(rootLocation)
                host.getLocationOnScreen(location)
                val x = rootLocation[0] - location[0] + host.scrollX
                val y = rootLocation[1] - location[1] + host.scrollY
                val width = app.root.width
                val height = app.root.height
                if (x != lastViewport.left || y != lastViewport.top
                    || width != lastViewport.width() || height != lastViewport.height()) {
                    lastViewport.set(x, y, x + width, y + height)
                    matrix.setScale(width.toFloat() / diffuse.width, height.toFloat() / diffuse.height)
                    matrix.postTranslate(x.toFloat(), y.toFloat())
                    shader.setLocalMatrix(matrix)
                }
                frame.set(bounds)
                canvas.drawRoundRect(frame, rounding, rounding, paint)
            }
            super.draw(canvas)
        }
    }
}

internal fun MainActivity.glaze(radius: Int, vararg colors: Long) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors.map { it.toInt() }.toIntArray()).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0xc8ffffff.toInt())
    }
