package com.pure.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.hypot

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.pure.bubble.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "pure_bubble_channel"
        private const val NOTIF_ID = 77

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var source: CaptureSource? = null
    private var screenW = 0
    private var screenH = 0

    private var bubble: ImageView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleSize = 0
    private var layer: TranslationLayer? = null
    private var layerLp: WindowManager.LayoutParams? = null
    private var scrim: FrameLayout? = null
    private var regionView: RegionView? = null

    private var pipeline: Pipeline? = null
    private var translators: Translators? = null
    private var ocr: Ocr? = null
    private var busy = false

    private lateinit var settings: AppSettings

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ================================================================= start

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundNotification()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = Prefs.load(this)

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }
            p.registerCallback(cb, main)
            projection = p
            projectionCallback = cb

            val dm = realMetrics()
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            val src = CaptureSource(p, screenW, screenH, dm.densityDpi, main)
            source = src

            val tr = Translators()
            val oc = Ocr()
            translators = tr
            ocr = oc

            addLayer()
            addBubble()

            val pl = Pipeline(
                scope, src, layer!!, tr, oc,
                settingsProvider = { settings },
                excludeRects = { bubbleRects() },
                toast = { msg -> toast(msg) },
                onBusy = { b -> busy = b; styleBubble() }
            )
            pipeline = pl
            pl.start()
            applyMode(settings.mode, fromMenu = false)
        } catch (e: Exception) {
            toast("تعذر بدء الترجمة: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        return START_NOT_STICKY
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun bubbleRects(): List<Rect> {
        val lp = bubbleLp ?: return emptyList()
        return listOf(Rect(lp.x, lp.y, lp.x + bubbleSize, lp.y + bubbleSize))
    }

    // ================================================================= views

    private fun addLayer() {
        val v = TranslationLayer(this)
        v.coverAlpha = (settings.coverAlpha * 255 / 100).coerceIn(150, 255)
        val lp = WindowManager.LayoutParams(
            screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        layer = v
        layerLp = lp
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun styleBubble() {
        val b = bubble ?: return
        val pl = pipeline
        val color = when {
            pl != null && pl.paused -> 0xFF9AA0A6.toInt()
            busy -> 0xFF2D8CFF.toInt()
            else -> 0xFF2ECC71.toInt()
        }
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(2), color)
        }
    }

    private fun addBubble() {
        bubbleSize = dp(34)
        val b = ImageView(this).apply {
            setImageResource(R.drawable.bubble_avatar)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(2), dp(2), dp(2), dp(2))
            alpha = settings.bubbleAlpha / 100f
        }
        bubble = b
        styleBubble()

        val lp = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - bubbleSize - dp(6)
            y = screenH / 3
        }
        bubbleLp = lp

        b.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longFired = false
            val longRunnable = Runnable {
                if (!moved) {
                    longFired = true
                    b.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    openMenu()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        longFired = false
                        main.postDelayed(longRunnable, 450)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && hypot(dx, dy) > dp(8)) {
                            moved = true
                            main.removeCallbacks(longRunnable)
                        }
                        if (moved) {
                            lp.x = (startX + dx).toInt().coerceIn(0, screenW - lp.width)
                            lp.y = (startY + dy).toInt().coerceIn(0, screenH - lp.height)
                            runCatching { wm.updateViewLayout(b, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longRunnable)
                        if (!moved && !longFired) onBubbleTap()
                    }
                    MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRunnable)
                }
                return true
            }
        })

        wm.addView(b, lp)
    }

    // ================================================================= أطوار الترجمة

    private fun onBubbleTap() {
        val pl = pipeline ?: return
        when (settings.mode) {
            Mode.FULL -> {
                if (pl.hasBoxes()) pl.clearBoxes() else pl.requestFull()
            }
            Mode.PARTIAL -> {
                if (pl.region == null) startRegionSelect() else togglePause()
            }
            else -> togglePause()
        }
    }

    private fun togglePause() {
        val pl = pipeline ?: return
        pl.setPaused(!pl.paused)
        styleBubble()
        toast(if (pl.paused) "الترجمة متوقفة مؤقتاً" else "رجعت الترجمة")
    }

    private fun applyMode(mode: Mode, fromMenu: Boolean) {
        val pl = pipeline ?: return
        settings = settings.copy(mode = mode)
        Prefs.save(this, settings)
        pl.setPaused(false)
        pl.clearBoxes()
        pl.setRegion(null)
        pl.resetBaseline()
        when (mode) {
            Mode.FULL -> toast("اضغط الفقاعة لترجمة الشاشة")
            Mode.PARTIAL -> {
                if (fromMenu) main.postDelayed({ startRegionSelect() }, 350)
                else toast("اضغط الفقاعة وحدد المنطقة")
            }
            Mode.AUTO -> {
                pl.markAllDirty()
                toast("الترجمة التلقائية")
            }
            Mode.REALTIME -> {
                pl.markAllDirty()
                toast("الترجمة في الوقت الحقيقي")
            }
            Mode.MANGA -> {
                pl.markAllDirty()
                toast("طور المانجا والمانهوا")
            }
            Mode.NOVEL -> {
                pl.markAllDirty()
                toast("طور الروايات والنصوص")
            }
        }
        styleBubble()
    }

    private fun applyLanguages(src: String, tgt: String) {
        val pl = pipeline ?: return
        settings = settings.copy(src = src, tgt = tgt)
        Prefs.save(this, settings)
        pl.clearBoxes()
        pl.resetBaseline()
        if (settings.mode == Mode.PARTIAL) pl.region?.let { pl.setRegion(it) }
        else if (settings.mode.continuous) pl.markAllDirty()
    }

    private fun startRegionSelect() {
        val pl = pipeline ?: return
        if (regionView != null) return
        pl.setSuspended(true)
        val rv = RegionView(this) { rect ->
            regionView?.let { v -> runCatching { wm.removeView(v) } }
            regionView = null
            main.postDelayed({
                pl.setSuspended(false)
                if (rect != null) {
                    pl.setRegion(rect)
                    toast("تم تحديد المنطقة")
                } else {
                    toast("ما انحددت منطقة")
                }
            }, 300)
        }
        val lp = WindowManager.LayoutParams(
            screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(rv, lp)
        regionView = rv
    }

    // ================================================================= القائمة (ضغطة مطولة)

    private fun pill(text: String, icon: String?, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (selected) 0xFF00A650.toInt() else 0xFFF4F5F9.toInt())
            }
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            row.addView(TextView(this).apply {
                this.text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else 0xFF1B1B1F.toInt())
            }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 14.5f
            gravity = Gravity.CENTER_VERTICAL or (if (icon == null) Gravity.CENTER_HORIZONTAL else Gravity.START)
            setTextColor(if (selected) Color.WHITE else 0xFF1B1B1F.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        return row
    }

    private fun LinearLayout.addPill(v: View, h: Int = 50) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
        lp.topMargin = dp(8)
        addView(v, lp)
    }

    private fun roundButton(icon: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF1B1B1F.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF4F5F9.toInt())
            }
            setOnClickListener { onClick() }
        }

    private fun fillMenu(card: LinearLayout, page: Int, pickedSrc: String? = null) {
        card.removeAllViews()
        when (page) {
            0 -> {
                card.addPill(pill("ترجمة الشاشة الكاملة", "🖥", settings.mode == Mode.FULL) { chooseMode(Mode.FULL) }, 54)
                card.addPill(pill("ترجمة جزئية", "✂", settings.mode == Mode.PARTIAL) { chooseMode(Mode.PARTIAL) })
                card.addPill(pill("ترجمة تلقائية", "🔄", settings.mode == Mode.AUTO) { chooseMode(Mode.AUTO) })
                card.addPill(pill("ترجمة في الوقت الحقيقي", "⚡", settings.mode == Mode.REALTIME) { chooseMode(Mode.REALTIME) }, 54)
                card.addPill(pill("طور المانجا والمانهوا", "📖", settings.mode == Mode.MANGA) { chooseMode(Mode.MANGA) })
                card.addPill(pill("طور الروايات والنصوص", "📚", settings.mode == Mode.NOVEL) { chooseMode(Mode.NOVEL) })

                card.addView(View(this).apply { setBackgroundColor(0xFFE3E5EA.toInt()) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(12); bottomMargin = dp(4)
                    })

                val pair = settings.tgt.uppercase() + "  ←  " + settings.src.uppercase()
                card.addPill(pill(pair, null, false) {
                    card.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    fillMenu(card, 1)
                    repositionMenu(card)
                })

                val bottom = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
                val sz = dp(48)
                bottom.addView(roundButton("⏻") { stopSelf() },
                    LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(14) })
                bottom.addView(roundButton("↩") { closeMenu() }, LinearLayout.LayoutParams(sz, sz))
                card.addView(bottom, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) })
            }
            1, 2 -> {
                val list = if (page == 1) Langs.sources else Langs.targets
                card.addView(TextView(this).apply {
                    text = if (page == 1) "لغة النص الأصلي" else "ترجم إلى"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF1B1B1F.toInt())
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (l in list) {
                    val cur = if (page == 1) settings.src else settings.tgt
                    box.addPill(pill(l.name, null, l.code == cur) {
                        if (page == 1) {
                            fillMenu(card, 2, pickedSrc = l.code)
                            repositionMenu(card)
                        } else {
                            val s = pickedSrc ?: settings.src
                            closeMenu()
                            applyLanguages(s, l.code)
                        }
                    }, 44)
                }
                val sv = ScrollView(this).apply { addView(box) }
                card.addView(sv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(340), screenH / 2)
                ))
                card.addPill(pill("رجوع", "↩", false) {
                    fillMenu(card, 0)
                    repositionMenu(card)
                }, 44)
            }
        }
    }

    private fun chooseMode(m: Mode) {
        closeMenu()
        applyMode(m, fromMenu = true)
    }

    private fun repositionMenu(card: LinearLayout) {
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        card.measure(
            View.MeasureSpec.makeMeasureSpec(dp(236), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = card.measuredHeight
        val bl = bubbleLp ?: return
        lp.topMargin = (bl.y - dp(40)).coerceIn(dp(24), maxOf(dp(24), screenH - h - dp(24)))
        scrim?.updateViewLayout(card, lp)
    }

    private fun openMenu() {
        if (scrim != null) return
        val pl = pipeline ?: return
        pl.setSuspended(true)

        val sc = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            setOnClickListener { closeMenu() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(12).toFloat()
            isClickable = true
        }
        fillMenu(card, 0)

        val bl = bubbleLp!!
        val cardW = dp(236)
        val lp = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = if (bl.x + bubbleSize / 2 < screenW / 2) bl.x + bubbleSize + dp(8)
        else maxOf(dp(8), bl.x - cardW - dp(8))
        lp.topMargin = dp(24)
        sc.addView(card, lp)

        val wlp = WindowManager.LayoutParams(
            screenW, screenH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(sc, wlp)
        scrim = sc
        repositionMenu(card)
    }

    private fun closeMenu() {
        val sc = scrim ?: return
        runCatching { wm.removeView(sc) }
        scrim = null
        main.postDelayed({ pipeline?.setSuspended(false) }, 300)
    }

    // ================================================================= تدوير الشاشة

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(resizeRunnable)
        main.postDelayed(resizeRunnable, 400)
    }

    private val resizeRunnable = Runnable {
        val src = source ?: return@Runnable
        val dm = realMetrics()
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return@Runnable
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        closeMenu()
        regionView?.let { v -> runCatching { wm.removeView(v) } }
        regionView = null
        runCatching { src.resize(screenW, screenH, dm.densityDpi) }
        layerLp?.let {
            it.width = screenW
            it.height = screenH
            layer?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
        }
        bubbleLp?.let {
            it.x = it.x.coerceIn(0, screenW - bubbleSize)
            it.y = it.y.coerceIn(0, screenH - bubbleSize)
            bubble?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
        }
        pipeline?.onResize(screenW, screenH)
    }

    // ================================================================= notification

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "بيور ترجمة", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val notif = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("بيور ترجمة شغال")
            .setContentText("ضغطة مطولة على الفقاعة لتغيير الطور")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(stopIcon, "إيقاف", stopIntent).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ================================================================= destroy

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        pipeline?.stop()
        scope.cancel()
        scrim?.let { runCatching { wm.removeView(it) } }
        regionView?.let { runCatching { wm.removeView(it) } }
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { layer?.let { wm.removeView(it) } }
        scrim = null
        regionView = null
        bubble = null
        layer = null
        source?.release()
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        runCatching { projection?.stop() }
        translators?.close()
        ocr?.close()
        super.onDestroy()
    }
}
