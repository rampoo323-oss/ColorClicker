package com.example.colorclicker

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import java.nio.ByteBuffer

@Suppress("DEPRECATION")
class OverlayService : Service() {
    companion object {
        const val SCALE = 2        // تصغير الصورة للسرعة (1 = أدق للأهداف الصغيرة جداً)
        const val STEP = 2         // خطوة المسح
        const val COOLDOWN = 40L   // أقل فاصل بين نقرتين (ms)
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var panel: View? = null
    private var hexLabel: TextView? = null

    @Volatile private var alive = true
    @Volatile private var running = false
    @Volatile private var targetRgb = -1
    @Volatile private var tol = 25
    @Volatile private var pickX = -1
    @Volatile private var pickY = -1
    @Volatile private var exL = 0; @Volatile private var exT = 0
    @Volatile private var exR = 0; @Volatile private var exB = 0
    private var curW = 0; private var curH = 0
    private var tr = 0; private var tg = 0; private var tb = 0; private var lim = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (projection != null) return START_NOT_STICKY
        startFg()
        val data = i?.getParcelableExtra<Intent>("data")
        val code = i?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)
        setupCapture()
        buildPanel()
        Thread { loop() }.apply { priority = Thread.MAX_PRIORITY; start() }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cc", "Color Clicker", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cc").setContentTitle("Color Clicker")
            .setSmallIcon(android.R.drawable.ic_menu_compass).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setupCapture() {
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm)
        curW = dm.widthPixels; curH = dm.heightPixels
        val w = curW / SCALE; val h = curH / SCALE
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        if (vd == null) {
            vd = projection!!.createVirtualDisplay("cc", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else { vd!!.resize(w, h, dm.densityDpi); vd!!.surface = r.surface }
        reader = r
        old?.close()
    }

    private fun checkSize() {
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels != curW || dm.heightPixels != curH) ui.post { setupCapture() }
    }

    // ---------- اللوحة العائمة ----------
    private fun buildPanel() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(0xE6202020.toInt()); cornerRadius = dp(14).toFloat() }
        }
        val lp = WindowManager.LayoutParams(dp(150), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(120) }

        fun updateRect() {
            exL = lp.x / SCALE; exT = lp.y / SCALE
            exR = (lp.x + root.width) / SCALE; exB = (lp.y + root.height) / SCALE
        }

        val drag = TextView(this).apply {
            text = "⠿ اسحب"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, lp); updateRect()
                }
            }
            true
        }

        fun btn(t: String, a: (Button) -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { a(this) }
        }
        hexLabel = TextView(this).apply { text = "اللون: —"; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val tolLabel = TextView(this).apply { text = "الحساسية: $tol"; setTextColor(Color.WHITE) }
        val seek = SeekBar(this).apply {
            max = 75; progress = tol - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tol = p + 5; tolLabel.text = "الحساسية: $tol" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        root.addView(drag)
        root.addView(hexLabel)
        root.addView(btn("🎯 اختر اللون") { startPick() })
        root.addView(btn("▶ تشغيل") { b ->
            if (targetRgb < 0) { Toast.makeText(this, "اختر اللون أولاً", Toast.LENGTH_SHORT).show() }
            else { running = !running; b.text = if (running) "⏸ إيقاف" else "▶ تشغيل" }
        })
        root.addView(tolLabel)
        root.addView(seek)
        root.addView(btn("✖ إغلاق") { stopSelf() })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRect() }
        wm.addView(root, lp)
        panel = root
    }

    private fun startPick() {
        val v = View(this).apply { setBackgroundColor(0x01000000) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                pickX = e.rawX.toInt(); pickY = e.rawY.toInt()
                ui.post { try { wm.removeView(v) } catch (_: Exception) {} }
            }
            true
        }
        wm.addView(v, lp)
        Toast.makeText(this, "المس اللون المطلوب على الشاشة", Toast.LENGTH_SHORT).show()
    }

    // ---------- حلقة الالتقاط والنقر ----------
    private fun loop() {
        var lastClick = 0L; var lastCheck = 0L
        while (alive) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCheck > 500) { lastCheck = now; checkSize() }
            val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
            if (img == null) { SystemClock.sleep(2); continue }
            try {
                val buf = img.planes[0].buffer; val rs = img.planes[0].rowStride
                val w = img.width; val h = img.height
                if (pickX >= 0) {
                    val cx = (pickX / SCALE).coerceIn(1, w - 2); val cy = (pickY / SCALE).coerceIn(1, h - 2)
                    var r = 0; var g = 0; var b = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val i = (cy + dy) * rs + (cx + dx) * 4
                        r += buf.get(i).toInt() and 255; g += buf.get(i + 1).toInt() and 255; b += buf.get(i + 2).toInt() and 255
                    }
                    targetRgb = ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
                    pickX = -1
                    val hex = String.format("#%06X", targetRgb)
                    ui.post { hexLabel?.text = "اللون: $hex" }
                }
                if (running && targetRgb >= 0) {
                    tr = (targetRgb shr 16) and 255; tg = (targetRgb shr 8) and 255; tb = targetRgb and 255
                    lim = tol * tol
                    val p = find(buf, rs, w, h)
                    if (p != null) {
                        val wait = COOLDOWN - (SystemClock.uptimeMillis() - lastClick)
                        if (wait > 0) SystemClock.sleep(wait)
                        ClickService.instance?.tap((p.x * SCALE + SCALE / 2).toFloat(), (p.y * SCALE + SCALE / 2).toFloat())
                        lastClick = SystemClock.uptimeMillis()
                    }
                }
            } catch (e: Exception) {
            } finally { try { img.close() } catch (e: Exception) {} }
        }
    }

    private fun hit(buf: ByteBuffer, rs: Int, x: Int, y: Int): Boolean {
        val i = y * rs + x * 4
        val dr = (buf.get(i).toInt() and 255) - tr
        val dg = (buf.get(i + 1).toInt() and 255) - tg
        val db = (buf.get(i + 2).toInt() and 255) - tb
        return dr * dr + dg * dg + db * db <= lim
    }

    // يبحث عن كتلة (2x2 نقاط متطابقة) لتقليل الأخطاء، ثم يحسب مركزها
    private fun find(buf: ByteBuffer, rs: Int, w: Int, h: Int): Point? {
        var y = 0
        while (y < h - STEP) {
            var x = 0
            while (x < w - STEP) {
                if (!(x in exL..exR && y in exT..exB) &&
                    hit(buf, rs, x, y) && hit(buf, rs, x + STEP, y) &&
                    hit(buf, rs, x, y + STEP) && hit(buf, rs, x + STEP, y + STEP)) {
                    var sx = 0L; var sy = 0L; var n = 0
                    for (yy in maxOf(0, y - 12)..minOf(h - 1, y + 12))
                        for (xx in maxOf(0, x - 12)..minOf(w - 1, x + 12))
                            if (hit(buf, rs, xx, yy)) { sx += xx; sy += yy; n++ }
                    return Point((sx / n).toInt(), (sy / n).toInt())
                }
                x += STEP
            }
            y += STEP
        }
        return null
    }

    override fun onDestroy() {
        alive = false; running = false
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vd?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
