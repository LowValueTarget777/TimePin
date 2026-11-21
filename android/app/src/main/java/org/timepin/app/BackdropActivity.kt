package org.timepin.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Draft choices only affect the requested surface after Save. */
class BackdropActivity : Activity() {
    private val widget by lazy { intent.getIntExtra("widgetId",0) }
    private lateinit var draft: Backdrop
    private lateinit var file: File
    private var photo: Bitmap? = null
    private var busy = false
    private lateinit var preview: ImageView
    private lateinit var content: LinearLayout
    private lateinit var applyButton: Button
    private val ink get() = if (calendarDark()) 0xfff1f4ef.toInt() else 0xff233d51.toInt()
    private val base get() = when { calendarDark() -> 0xff131b1a.toInt(); calendarMode() == "frost" -> 0xffeaf0f7.toInt(); else -> 0xfff4f4ee.toInt() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int, border: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1),border)
    }
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink)
    }
    override fun onCreate(state: Bundle?) {
        installCalendarTheme()
        super.onCreate(state)
        if (widget != 0 && widget !in Widgets.installed(this)) { finish(); return }
        window.statusBarColor = base
        window.navigationBarColor = window.statusBarColor
        if (!calendarDark()) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        draft = Backdrops.read(this,widget).let { it.copy(mode = state?.getString("mode") ?: it.mode,
            color = state?.getString("color") ?: it.color) }
        file = File(cacheDir, state?.getString("draft") ?: "frost-${UUID.randomUUID()}.jpg")
        if (file.isFile) photo = BitmapFactory.decodeFile(file.path)
        draw()
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("mode",draft.mode); out.putString("color",draft.color); out.putString("draft",file.name)
    }
    override fun onDestroy() {
        if (isFinishing && ::file.isInitialized) file.delete()
        super.onDestroy()
    }
    private fun draw() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(base)
            if (calendarMode() == "frost") {
                val saved = Backdrops.read(this@BackdropActivity)
                if (saved.mode == "solid") {
                    val tint = Color.parseColor(saved.color)
                    setBackgroundColor(if (Color.luminance(tint) < .42) blend(tint,base,.78f) else tint)
                } else background = android.graphics.drawable.BitmapDrawable(resources,
                    Backdrops.art(this@BackdropActivity,saved,180,320,true)).apply { gravity = Gravity.FILL }
            }
            setPadding(dp(18),dp(10),dp(18),dp(10))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(calendarBack("返回") { finish() },dp(48),dp(48))
        val heading = if (widget == 0) "雾光背景" else if (Widgets.installed(this)[widget] == "month") "月历组件背景" else "近期日程背景"
        top.addView(label(heading,20).apply { setTypeface(typeface,Typeface.BOLD); setPadding(dp(12),0,0,0) })
        body.addView(top)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = shape(Color.WHITE,20); clipToOutline = true
            contentDescription = "背景预览"
        }
        val sample = FrameLayout(this).apply {
            background = windowSurface(20)
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1,-1))
            val date = java.time.LocalDate.now()
            addView(label("${date.monthValue}月${date.dayOfMonth}日",18).apply {
                background = windowSurface(14)
                setTypeface(typeface,Typeface.BOLD)
                setPadding(dp(14),dp(8),dp(14),dp(8))
            }, FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })
        }
        content.addView(sample,LinearLayout.LayoutParams(-1,dp(96)).apply { setMargins(0,dp(10),0,dp(12)) })
        val choices = listOf("follow" to "跟随应用背景","frost" to "雾光","photo" to "自选图片","solid" to "纯色")
        val group = RadioGroup(this).apply { background = windowSurface(16); setPadding(dp(10),dp(4),dp(10),dp(4)) }
        for ((value,title) in choices.filter { it.first != "follow" || widget != 0 }) {
            group.addView(RadioButton(this).apply {
                text = title; setTextColor(ink); isChecked = value == draft.mode
                setOnClickListener { if (value != draft.mode) { draft = draft.copy(mode=value); draw() } }
            },-1,dp(43))
        }
        content.addView(group)
        if (draft.mode == "photo") {
            content.addView(calendarButton(if (photo != null || draft.photo.isNotEmpty()) "更换图片" else "从设备选择图片") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),31)
            })
        } else if (draft.mode == "solid") {
            val swatches = LinearLayout(this)
            val field = EditText(this).apply {
                isSingleLine = true; hint = "#RRGGBB"; setText(draft.color)
                setTextColor(ink); contentDescription = "自定义颜色"
            }
            Backdrops.colors.forEachIndexed { i,hex ->
                val color = Color.parseColor(hex)
                val tile = Button(this).apply {
                    stateListAnimator = null; backgroundTintList = null; foreground = pressEffect(12)
                    background = shape(color,12,0x667a8b9b); contentDescription = Backdrops.colorNames[i]
                    setPadding(0,0,0,0); isAllCaps = false; setTextColor(onColor(color))
                    setOnClickListener { field.setText(hex) }
                }
                swatches.addView(tile,LinearLayout.LayoutParams(0,dp(39),1f).apply { setMargins(dp(2),dp(12),dp(2),dp(4)) })
            }
            fun mark() {
                for (i in Backdrops.colors.indices) (swatches.getChildAt(i) as Button).text =
                    if (Backdrops.colors[i].equals(draft.color,true)) "✓" else ""
            }
            mark(); content.addView(swatches)
            val channels = listOf("红色","绿色","蓝色")
            val channelColors = if (calendarDark()) listOf("#F5A6AF","#91D6BD","#A4C7F0")
                else listOf("#A54C58","#37806A","#4877AC")
            val sliders = channels.mapIndexed { i, name ->
                val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(label(name,14))
                val slider = SeekBar(this).apply {
                    max = 255; contentDescription = name
                    val tint = ColorStateList.valueOf(Color.parseColor(channelColors[i]))
                    progressTintList = tint; thumbTintList = tint
                }
                line.addView(slider,LinearLayout.LayoutParams(0,dp(48),1f))
                content.addView(line)
                slider
            }
            fun reflectColor() {
                if (!draft.color.matches(Regex("#[0-9a-fA-F]{6}"))) return
                val rgb = Color.parseColor(draft.color)
                listOf(Color.red(rgb),Color.green(rgb),Color.blue(rgb)).forEachIndexed { i, channel ->
                    sliders[i].progress = channel
                }
            }
            reflectColor()
            sliders.forEach { slider ->
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) field.setText("#%02X%02X%02X".format(sliders[0].progress, sliders[1].progress, sliders[2].progress))
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            content.addView(field)
            field.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    draft = draft.copy(color=s.toString().trim()); reflectColor(); mark(); updatePreview()
                }
                override fun beforeTextChanged(s: CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s: CharSequence?,start:Int,before:Int,count:Int) {}
            })
        }
        applyButton = calendarButton("保存背景",primary = true) { save() }
        body.addView(applyButton,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(8) })
        setContentView(body)
        updatePreview()
    }
    private fun updatePreview() {
        val follows = draft.mode == "follow"
        val spec = if (follows) Backdrops.read(this) else draft
        val valid = when (spec.mode) {
            "solid" -> Regex("#[0-9a-fA-F]{6}").matches(spec.color)
            "photo" -> spec.photo.isNotEmpty() || !follows && photo != null
            else -> true
        }
        applyButton.isEnabled = valid && !busy
        applyButton.alpha = if (applyButton.isEnabled) 1f else .48f
        if (valid && spec.mode == "solid") preview.setImageDrawable(ColorDrawable(Color.parseColor(spec.color)))
        else if (valid) preview.setImageBitmap(Backdrops.art(this,spec,320,126,widget != 0,
            if (follows) 0 else widget, if (spec.mode == "photo" && !follows) photo else null))
        else preview.setImageDrawable(null)
    }
    @Deprecated("Activity result API")
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        val uri = data?.data ?: return
        if (request != 31 || result != RESULT_OK || uri.scheme != "content") return
        busy = true; updatePreview()
        Store.io.execute {
            val loaded = runCatching {
                val bytes = Backdrops.encode(AccountProfiles.sampledImage(this,uri))
                file.writeBytes(bytes)
                requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                loaded.onSuccess { bitmap ->
                    photo = bitmap
                    draft = draft.copy(mode="photo"); draw()
                }.onFailure { toast("无法读取这张图片"); updatePreview() }
            }
        }
    }
    private fun toast(msg: String) = Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
    private fun save() {
        if (busy) return
        busy = true; updatePreview()
        val chosen = draft
        val import = chosen.mode == "photo" && photo != null
        Store.io.execute {
            val done = runCatching {
                val args = JSONObject()
                if (widget != 0) args.put("widgetId",widget)
                if (import) args.put("image",Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
                else {
                    args.put("mode",chosen.mode)
                    if (chosen.mode == "solid") args.put("color",chosen.color)
                }
                DeviceCommands(this).run(if (import) "background.photo" else "background.update",args)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                done.onSuccess { setResult(RESULT_OK); finish() }
                    .onFailure { toast(it.message ?: "保存失败"); updatePreview() }
            }
        }
    }
}
