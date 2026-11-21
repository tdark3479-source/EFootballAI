package com.example.efootballai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    data class Metrics(
        val running: Boolean,
        val frames: Long,
        val fps: Double,
        val width: Int,
        val height: Int,
        val intervalMs: Long,
        val people: Int,
        val footballs: Int,
        val visionReady: Boolean,
        val visionDiagnostic: String
    )

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var vision: VisionDetector? = null

    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    private var frameCount = 0L
    private var lastFrameAt = 0L
    private var lastInferenceAt = 0L
    private var lastPublishAt = 0L

    private var width = 0
    private var height = 0
    private var people = 0
    private var footballs = 0
    private var visionReady = false
    private var visionDiagnostic = "NOT STARTED"

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "capture_channel"
        private const val TAG = "EFootballAI"
        private const val INFERENCE_INTERVAL_MS = 500L
        private const val METRICS_INTERVAL_MS = 500L
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

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(displayMetrics)

        val scale = minOf(1.0, 720.0 / displayMetrics.widthPixels.toDouble())
        width = (displayMetrics.widthPixels * scale).toInt().coerceAtLeast(320)
        height = (displayMetrics.heightPixels * scale).toInt().coerceAtLeast(320)

        frameCount = 0L
        lastFrameAt = 0L
        lastInferenceAt = 0L
        lastPublishAt = 0L
        people = 0
        footballs = 0
        visionReady = false
        visionDiagnostic = "INITIALIZING MODEL"
        stopping.set(false)

        worker = HandlerThread("efootball-frame-worker").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        try {
            projection = manager.getMediaProjection(resultCode, data)
            val activeProjection = projection ?: throw IllegalStateException("MediaProjection could not be created")

            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, handler)

            virtualDisplay = activeProjection.createVirtualDisplay(
                "EFootballAI-Capture", width, height, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
            )
            running.set(true)

            handler?.post {
                try {
                    Log.i(TAG, "Vision initialization started")
                    vision = VisionDetector(applicationContext)
                    visionReady = true
                    visionDiagnostic = "READY: model loaded"
                    Log.i(TAG, "Vision detector initialized")
                } catch (error: Throwable) {
                    visionReady = false
                    visionDiagnostic = diagnosticFor("INIT FAILED", error)
                    Log.e(TAG, "Vision initialization failed: $visionDiagnostic", error)
                }
                publishMetrics(0.0, 0L)
            }

            reader?.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!running.get()) return@setOnImageAvailableListener
                    frameCount++
                    val now = System.currentTimeMillis()
                    val interval = if (lastFrameAt == 0L) 0L else now - lastFrameAt
                    lastFrameAt = now

                    if (visionReady && now - lastInferenceAt >= INFERENCE_INTERVAL_MS) {
                        lastInferenceAt = now
                        val bitmap = imageToBitmap(image)
                        try {
                            val result = vision?.detect(bitmap)
                            if (result != null) {
                                people = result.people
                                footballs = result.footballs

                                Log.i(TAG, "VISION QUERY | People: $people | Sports balls: $footballs | Model ready: $visionReady")
                            }
                        } catch (error: Throwable) {
                            visionDiagnostic = diagnosticFor("INFERENCE FAILED", error)
                            Log.e(TAG, "Object detection failed: $visionDiagnostic", error)
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    if (now - lastPublishAt >= METRICS_INTERVAL_MS) {
                        val measuredFps = if (interval > 0L) 1000.0 / interval else 0.0
                        publishMetrics(measuredFps, interval)
                        lastPublishAt = now
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Frame processing failed", error)
                } finally {
                    image.close()
                }
            }, handler)
        } catch (error: Exception) {
            Log.e(TAG, "Capture startup failed", error)
            stopCapture()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val imageWidth = image.width
        val imageHeight = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val pixels = IntArray(imageWidth * imageHeight)

        for (y in 0 until imageHeight) {
            val rowStart = y * rowStride
            for (x in 0 until imageWidth) {
                val offset = rowStart + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                val alpha = buffer.get(offset + 3).toInt() and 0xFF
                pixels[y * imageWidth + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
    }

    private fun diagnosticFor(stage: String, error: Throwable): String {
        val detail = error.message?.replace('\n', ' ')?.take(180).orEmpty()
        return if (detail.isBlank()) "$stage: ${error.javaClass.simpleName}" else "$stage: ${error.javaClass.simpleName}: $detail"
    }

    private fun publishMetrics(fps: Double, interval: Long) {
        val snapshot = Metrics(
            running = running.get(), frames = frameCount, fps = fps,
            width = width, height = height, intervalMs = interval,
            people = people, footballs = footballs, visionReady = visionReady,
            visionDiagnostic = visionDiagnostic
        )
        onMetrics?.invoke(snapshot)
        OverlayService.updateMetrics(snapshot)
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        reader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        vision?.close()
        vision = null
        visionReady = false
        val oldProjection = projection
        projection = null
        if (oldProjection != null) {
            try {
                oldProjection.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Projection cleanup warning", error)
            }
        }
        worker?.quitSafely()
        worker = null
        handler = null
        publishMetrics(0.0, 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen analysis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return builder.setContentTitle("eFootball AI Companion")
            .setContentText("Local screen analysis is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
