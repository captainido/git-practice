package com.yungerman.catcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/**
 * Finds cat candidates with an on-device object detector (EfficientDet-Lite2, COCO classes).
 * The low score threshold is deliberate: it only proposes candidates, and [CatIdentifier]
 * decides whether a candidate really is our cat.
 */
class CatDetector(context: Context) : Closeable {

    /** [box] is normalized to 0..1 within the frame that was passed in. */
    data class Result(val score: Float, val box: RectF?)

    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.05f)
            .setMaxResults(3)
            .setCategoryAllowlist(listOf("cat"))
            .build()
    )

    /** [frame] must already be upright. */
    fun detect(frame: Bitmap): Result {
        val detections = detector.detect(BitmapImageBuilder(frame).build()).detections()
        val best = detections.maxByOrNull { it.categories().first().score() } ?: return Result(0f, null)
        val b = best.boundingBox()
        val box = RectF(
            (b.left / frame.width).coerceIn(0f, 1f),
            (b.top / frame.height).coerceIn(0f, 1f),
            (b.right / frame.width).coerceIn(0f, 1f),
            (b.bottom / frame.height).coerceIn(0f, 1f),
        )
        return Result(best.categories().first().score(), box)
    }

    override fun close() = detector.close()

    private companion object {
        const val MODEL_ASSET = "efficientdet_lite2.tflite"
    }
}
