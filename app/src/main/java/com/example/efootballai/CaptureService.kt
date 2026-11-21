package com.example.efootballai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    data class Metrics(
        val running: Boolean,
        val frames: Long,
        val fps: Double,
        val width: Int,
        val height: Int,
        val intervalMs: Long
    )

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)
    private var frameCount = 0L
    private var lastReport = System.currentTimeMillis()
    private var lastFrameAt = 0L
    private var width = 0
    private var height = 0

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "capture_channel"
        var onMetrics: ((Metrics) -> Unit)? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(41, notification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (running.get()) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        // Downscale capture to reduce CPU and memory pressure.
        val scale = minOf(1.0, 720.0 / metrics.widthPixels.toDouble())
        width = (metrics.widthPixels * scale).toInt().coerceAtLeast(320)
        height = (metrics.heightPixels * scale).toInt().coerceAtLeast(320)

        worker = HandlerThread("efootball-frame-worker").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "EFootballAI-Capture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )

        running.set(true)
        reader?.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frameCount++
                val now = System.currentTimeMillis()
                val interval = if (lastFrameAt == 0L) 0L else now - lastFrameAt
                lastFrameAt = now

                // Intentionally no pixel upload or touch injection. This starter
                // records frame timing only; vision inference is the next module.
                if (now - lastReport >= 1000L) {
                    val elapsed = (now - lastReport).coerceAtLeast(1L)
                    val fps = frameCount * 1000.0 / elapsed
                    onMetrics?.invoke(Metrics(true, frameCount, fps, width, height, interval))
                    lastReport = now
                }
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            stopSelf()
            return
        }
        reader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        onMetrics?.invoke(Metrics(false, frameCount, 0.0, width, height, 0L))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen analysis", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("eFootball AI Companion")
            .setContentText("Local screen analysis is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
