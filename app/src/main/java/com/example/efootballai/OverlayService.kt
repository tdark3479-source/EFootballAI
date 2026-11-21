package com.example.efootballai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var panel: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        panel = TextView(this).apply {
            text = "AI COMPANION\\nAnalysis standby"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD15202B.toInt())
            setPadding(18, 14, 18, 14)
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        windowManager?.addView(panel, params)
    }

    override fun onDestroy() {
        panel?.let { windowManager?.removeView(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
