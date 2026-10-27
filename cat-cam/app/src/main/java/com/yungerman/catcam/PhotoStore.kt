package com.yungerman.catcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rotates, timestamps and saves detection photos (app folder for sending + phone gallery). */
object PhotoStore {
    private const val MAX_SIDE = 1600
    private const val KEEP_FILES = 100

    fun prepare(source: Bitmap, rotationDegrees: Int, stamp: String): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(source.width, source.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        val bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmap)
        val textSize = bitmap.width / 22f
        val pad = textSize / 2
        val band = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRect(0f, bitmap.height - textSize - 2 * pad, bitmap.width.toFloat(), bitmap.height.toFloat(), band)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        canvas.drawText(stamp, pad, bitmap.height - pad - text.descent(), text)
        return bitmap
    }

    fun save(context: Context, bitmap: Bitmap, time: Date): File {
        val name = "cat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time) + ".jpg"
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "detections").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        saveToGallery(context, bitmap, name)
        prune(dir)
        return file
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // needs storage permission there; skip
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CatCam")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }

    /** Keeps only the newest files in the app folder (the gallery copies stay). */
    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
    }
}
