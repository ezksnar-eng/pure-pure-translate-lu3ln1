package com.pure.bubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val blue = 0xFF00639B.toInt()
    private val cardBg = 0xFFEAF4FF.toInt()

    private lateinit var srcSp: Spinner
    private lateinit var tgtSp: Spinner
    private lateinit var modeSp: Spinner
    private lateinit var engineSp: Spinner
    private lateinit var geminiBox: LinearLayout
    private lateinit var openaiBox: LinearLayout
    private lateinit var gKey: EditText
    private lateinit var gModel: EditText
    private lateinit var oBase: EditText
    private lateinit var oKey: EditText
    private lateinit var oModel: EditText
    private lateinit var glossary: EditText
    private lateinit var coverSb: SeekBar
    private lateinit var bubbleSb: SeekBar
    private lateinit var startBtn: Button

    private val modes = listOf(
        Mode.AUTO to "ترجمة تلقائية",
        Mode.REALTIME to "ترجمة في الوقت الحقيقي",
        Mode.MANGA to "طور المانجا والمانهوا والويبتون",
        Mode.NOVEL to "طور الروايات والنصوص",
        Mode.FULL to "ترجمة الشاشة الكاملة (عند الضغط)",
        Mode.PARTIAL to "ترجمة جزئية (تحدد منطقة)"
    )

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, r.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                startBtn.postDelayed({ refreshButton() }, 600)
                Toast.makeText(
                    this,
                    "تم. افتح أي تطبيق وراح تلاقي الفقاعة الصغيرة عائمة (ضغطة مطولة = القائمة).",
                    Toast.LENGTH_LONG
                ).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "لازم توافق على تسجيل الشاشة حتى تشتغل الترجمة", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = 0xFF1B1B1F.toInt()) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun card(color: Int = cardBg): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(color, 28)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun LinearLayout.addWithTop(v: View, topDp: Int = 12) {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(topDp)
        addView(v, lp)
    }

    private fun spinnerOf(items: List<String>, selected: Int): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected)
        }

    private fun seek(min: Int, value: Int, max: Int = 100): SeekBar = SeekBar(this).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
    }

    private fun field(hint: String, value: String, secret: Boolean = false, multiline: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textSize = 14f
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            } else {
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (e: Throwable) {
            showError("خطأ بالواجهة", e.stackTraceToString())
        }
    }

    /** يعرض نص الخطأ على الشاشة (قابل للتحديد) بدل ما يسكّر التطبيق. */
    private fun showError(title: String, body: String) {
        val tv = TextView(this).apply {
            text = "$title\n\n$body"
            textSize = 12f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun buildUi() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val s = Prefs.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(label("بيور ترجمة", 26f, true))
        root.addWithTop(
            label("ترجمة فورية فوق أي شاشة. ضغطة مطولة على الفقاعة تفتح قائمة الأطوار.", 13f, color = Color.GRAY), 2
        )

        // ---- اللغات + بدء
        val langCard = card()
        srcSp = spinnerOf(Langs.sources.map { it.name }, Langs.sources.indexOfFirst { it.code == s.src }.coerceAtLeast(0))
        tgtSp = spinnerOf(Langs.targets.map { it.name }, Langs.targets.indexOfFirst { it.code == s.tgt }.coerceAtLeast(0))
        langCard.addView(label("من لغة", 14f, color = blue))
        langCard.addView(srcSp)
        langCard.addWithTop(label("إلى لغة", 14f, color = blue), 8)
        langCard.addView(tgtSp)
        langCard.addWithTop(label("الطور", 14f, color = blue), 8)
        modeSp = spinnerOf(modes.map { it.second }, modes.indexOfFirst { it.first == s.mode }.coerceAtLeast(0))
        langCard.addView(modeSp)

        startBtn = Button(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundBg(blue, 40)
            setOnClickListener { onStartClicked() }
        }
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60))
        btnLp.topMargin = dp(16)
        langCard.addView(startBtn, btnLp)
        root.addWithTop(langCard, 18)

        // ---- محرك الترجمة
        root.addWithTop(label("محرك الترجمة", 18f, true), 24)
        val engCard = card(0xFFF7F8FC.toInt())
        engineSp = spinnerOf(
            Engines.list.map { it.second },
            Engines.list.indexOfFirst { it.first == s.engine }.coerceAtLeast(0)
        )
        engCard.addView(engineSp)

        geminiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gKey = field("مفتاح Gemini (من aistudio.google.com)", s.geminiKey, secret = true)
        gModel = field("اسم الموديل", s.geminiModel)
        geminiBox.addWithTop(label("مفتاح Gemini المجاني يعطي ترجمة أدق وبسياق الشاشة.", 12f, color = Color.GRAY), 8)
        geminiBox.addView(gKey)
        geminiBox.addView(gModel)
        engCard.addView(geminiBox)

        openaiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        oBase = field("رابط الخدمة (Base URL)", s.oaiBase)
        oKey = field("المفتاح (API Key)", s.oaiKey, secret = true)
        oModel = field("اسم الموديل", s.oaiModel)
        openaiBox.addWithTop(
            label("أي خدمة متوافقة مع OpenAI: OpenAI، Groq، DeepSeek، Mistral، أو Ollama/LM Studio على شبكتك.", 12f, color = Color.GRAY), 8
        )
        openaiBox.addView(oBase)
        openaiBox.addView(oKey)
        openaiBox.addView(oModel)
        engCard.addView(openaiBox)

        engCard.addWithTop(
            label("إذا فشل المحرك المختار، التطبيق يحول تلقائياً لـ Google ثم للترجمة بدون انترنت.", 12f, color = Color.GRAY), 10
        )
        root.addWithTop(engCard, 10)

        engineSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateEngineBoxes()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateEngineBoxes()

        // ---- القاموس
        root.addWithTop(label("قاموس الكلمات", 18f, true), 24)
        val glCard = card(0xFFF7F8FC.toInt())
        glCard.addView(label("سطر لكل كلمة بالشكل:  الاسم=الترجمة  (تُفرض على الذكاء الاصطناعي، وتُطبق كلياً على بقية المحركات)", 12f, color = Color.GRAY))
        glossary = field("Naruto=ناروتو\nLevel up=ارتقاء بالمستوى", s.glossary, multiline = true)
        glCard.addWithTop(glossary, 6)
        root.addWithTop(glCard, 10)

        // ---- الشفافية
        root.addWithTop(label("المظهر", 18f, true), 24)
        val optCard = card(0xFFF7F8FC.toInt())
        optCard.addView(label("شفافية صندوق الترجمة (أقل من 100 يخلي التطبيق يكتشف تغيّر النص تحته)"))
        coverSb = seek(60, s.coverAlpha)
        optCard.addView(coverSb)
        optCard.addWithTop(label("شفافية الفقاعة العائمة"), 14)
        bubbleSb = seek(30, s.bubbleAlpha)
        optCard.addView(bubbleSb)
        root.addWithTop(optCard, 10)

        root.addWithTop(
            label(
                "الاستخدام:\n" +
                        "• ضغطة على الفقاعة: إيقاف مؤقت / تشغيل (وبطور الشاشة الكاملة: ترجمة / إخفاء).\n" +
                        "• ضغطة مطولة: قائمة الأطوار واللغات والإغلاق.\n" +
                        "• اسحب الفقاعة لأي مكان.\n" +
                        "• أول مرة لكل لغة بدون انترنت يحتاج تحميل نموذج صغير.",
                13f, color = Color.DKGRAY
            ), 20
        )

        val crash = java.io.File(filesDir, App.CRASH_FILE)
        if (crash.exists()) {
            val txt = runCatching { crash.readText() }.getOrDefault("")
            crash.delete()
            if (txt.isNotBlank()) {
                root.addWithTop(label("آخر انهيار سُجّل (صوّره وابعثه):", 14f, true, 0xFFB3261E.toInt()), 20)
                root.addView(TextView(this).apply {
                    text = txt.take(3000)
                    textSize = 10f
                    setTextIsSelectable(true)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                })
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
        refreshButton()
    }

    private fun updateEngineBoxes() {
        val e = Engines.list[engineSp.selectedItemPosition.coerceAtLeast(0)].first
        geminiBox.visibility = if (e == Engines.GEMINI) View.VISIBLE else View.GONE
        openaiBox.visibility = if (e == Engines.OPENAI) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshButton()
    }

    private fun refreshButton() {
        if (!::startBtn.isInitialized) return
        val running = OverlayService.running
        startBtn.text = if (running) "إيقاف" else "بدء"
        startBtn.background = roundBg(if (running) 0xFFB3261E.toInt() else blue, 40)
    }

    private fun saveSettings() {
        val s = AppSettings(
            src = Langs.sources[srcSp.selectedItemPosition].code,
            tgt = Langs.targets[tgtSp.selectedItemPosition].code,
            mode = modes[modeSp.selectedItemPosition].first,
            engine = Engines.list[engineSp.selectedItemPosition].first,
            geminiKey = gKey.text.toString().trim(),
            geminiModel = gModel.text.toString().trim().ifEmpty { "gemini-flash-latest" },
            oaiBase = oBase.text.toString().trim(),
            oaiKey = oKey.text.toString().trim(),
            oaiModel = oModel.text.toString().trim(),
            glossary = glossary.text.toString(),
            coverAlpha = coverSb.progress + 60,
            bubbleAlpha = bubbleSb.progress + 30
        )
        Prefs.save(this, s)
    }

    private fun onStartClicked() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            startBtn.postDelayed({ refreshButton() }, 500)
            return
        }

        saveSettings()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «الظهور فوق التطبيقات» ثم ارجع واضغط بدء", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
