package com.yungerman.catcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable

/**
 * Tells *our* cat apart from cat-like things (a gray fluffy slipper scored 73% on the detector
 * but only 0.15 here). Compares an image "fingerprint" (MobileNetV3 embedding) of a candidate
 * against reference photos of the cat bundled in assets/ref.
 */
class CatIdentifier(context: Context) : Closeable {

    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .build()
    )

    private val references: List<Embedding> = context.assets.list(REF_DIR).orEmpty().map { name ->
        context.assets.open("$REF_DIR/$name").use { stream -> embed(BitmapFactory.decodeStream(stream)) }
    }

    /** Highest cosine similarity (about -1..1) between [crop] and any reference photo of the cat. */
    fun similarity(crop: Bitmap): Float {
        val embedding = embed(crop)
        return references.maxOfOrNull { ImageEmbedder.cosineSimilarity(embedding, it).toFloat() } ?: 0f
    }

    private fun embed(bitmap: Bitmap): Embedding =
        embedder.embed(BitmapImageBuilder(bitmap).build()).embeddingResult().embeddings().first()

    override fun close() = embedder.close()

    private companion object {
        const val MODEL_ASSET = "mobilenet_v3_small.tflite"
        const val REF_DIR = "ref"
    }
}
