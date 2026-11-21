package com.example.efootballai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage

data class VisionSummary(
    val people: Int,
    val footballs: Int
)

class VisionDetector(context: Context) {

    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(0.35f)
            .setMaxResults(25)
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(2)
                    .build()
            )
            .build()

        detector = ObjectDetector.createFromFileAndOptions(
            context,
            "efficientdet_lite0_int8.tflite",
            options
        )
    }

    fun detect(bitmap: Bitmap): VisionSummary {
        val results = detector.detect(TensorImage.fromBitmap(bitmap))

        var people = 0
        var footballs = 0

        for (detection in results) {
            val category = detection.categories.firstOrNull()
                ?: continue

            when (category.label.lowercase()) {
                "person" -> people++
                "sports ball" -> footballs++
            }
        }

        return VisionSummary(people, footballs)
    }

    fun close() {
        detector.close()
    }
}
