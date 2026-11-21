package com.example.efootballai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var panel: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    companion object {
        @Volatile private var active: OverlayService? = null

        fun updateMetrics(metrics: CaptureService.Metrics) {
            active?.mainHandler?.post {
                active?.render(metrics)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        panel = TextView(this).apply {
            text = "EFOOTBALL AI\nWaiting for screen analysis..."
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE6192735.toInt())
            setPadding(16, 12, 16, 12)
            elevation = 12f
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 150
        }

        panel?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (params.x - (event.rawX - lastTouchX).toInt()).coerceAtLeast(0)
                    params.y = (params.y + (event.rawY - lastTouchY).toInt()).coerceAtLeast(0)
                    windowManager?.updateViewLayout(view, params)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                else -> true
            }
        }

        windowManager?.addView(panel, params)
    }

    private fun render(metrics: CaptureService.Metrics) {
        panel?.text = buildString {
            append("EFOOTBALL AI")
            append('\n')
            append(if (metrics.running) "CAPTURE: LIVE" else "CAPTURE: OFF")
            append('\n')
            append("VISION: ")
            append(if (metrics.visionReady) "READY" else "UNAVAILABLE")
            append('\n')
            append("PEOPLE: ")
            append(metrics.people)
            append("  BALLS: ")
            append(metrics.footballs)
            append('\n')
            append("TACTICS: TRACKING NOT READY")
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        panel?.let { windowManager?.removeView(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
