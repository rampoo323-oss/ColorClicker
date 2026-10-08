package com.example.colorclicker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val REQ = 1

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p); gravity = Gravity.CENTER
        }
        col.addView(TextView(this).apply {
            text = "1) فعّل خدمة النقر\n2) اضغط ابدأ واسمح بتسجيل الشاشة\n3) افتح التطبيق المطلوب واستخدم اللوحة العائمة"
            textSize = 16f
        })
        col.addView(Button(this).apply {
            text = "تفعيل خدمة النقر"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply { text = "ابدأ"; setOnClickListener { begin() } })
        setContentView(col)
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (ClickService.instance == null) {
            Toast.makeText(this, "فعّل خدمة النقر أولاً", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        super.onActivityResult(rq, rc, data)
        if (rq == REQ && rc == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java).putExtra("code", rc).putExtra("data", data)
            )
            moveTaskToBack(true)
        }
    }
}
