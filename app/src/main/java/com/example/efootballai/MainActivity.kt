package com.example.efootballai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val captureRequest = 4101
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private var waitingForOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "EFOOTBALL AI"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val subtitle = TextView(this).apply {
            text = "Live tactical companion · v0.2"
            textSize = 14f
            setTextColor(0xFF9FB3C8.toInt())
            setPadding(0, 6, 0, 24)
        }

        status = TextView(this).apply {
            text = "Status: Standby"
            textSize = 17f
            setTextColor(0xFF8BE0A3.toInt())
            setPadding(0, 12, 0, 12)
        }

        metrics = TextView(this).apply {
            text = "Capture: OFF\nFrames received: 0\nVision model: Not started"
            textSize = 15f
            setTextColor(0xFFE6EDF3.toInt())
            setPadding(0, 8, 0, 20)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(metrics)
        root.addView(makeButton("Start screen analysis") { requestCapture() })
        root.addView(makeButton("Stop analysis") {
            stopService(Intent(this, CaptureService::class.java))
            status.text = "Status: Stopped"
        })
        root.addView(makeButton("Enable floating panel") { requestOverlayAndShow() })
        root.addView(makeButton("Hide floating panel") {
            stopService(Intent(this, OverlayService::class.java))
        })

        val privacy = TextView(this).apply {
            text = "\nPrivacy: Frames are processed locally. This build displays capture and generic object-detection metrics. Tactical recommendations are still under development."
            textSize = 12f
            setTextColor(0xFF9FB3C8.toInt())
        }
        root.addView(privacy)

        setContentView(ScrollView(this).apply { addView(root) })

        CaptureService.onMetrics = { m ->
            runOnUiThread {
                status.text = if (m.running) "Status: Capturing" else "Status: Standby"
                metrics.text = buildString {
                    append("Capture: ")
                    append(if (m.running) "ON" else "OFF")
                    append("\nFrames received: ")
                    append(m.frames)
                    append("\nEstimated capture rate: ")
                    append("%.1f".format(m.fps))
                    append(" FPS")
                    append("\nResolution: ")
                    append(m.width)
                    append(" × ")
                    append(m.height)
                    append("\nLast frame interval: ")
                    append(m.intervalMs)
                    append(" ms")
                    append("\nDetected people: ")
                    append(m.people)
                    append("\nDetected sports balls: ")
                    append(m.footballs)
                    append("\nVision model: ")
                    append(if (m.visionReady) "READY" else "UNAVAILABLE")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            startService(Intent(this, OverlayService::class.java))
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Activity result API kept minimal for starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
            else startService(service)
            status.text = "Status: Starting capture"
        }
    }

    private fun requestOverlayAndShow() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            waitingForOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        CaptureService.onMetrics = null
        super.onDestroy()
    }
}
