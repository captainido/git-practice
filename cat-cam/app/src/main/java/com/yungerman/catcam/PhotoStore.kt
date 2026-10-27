package com.yungerman.catcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stamps photos with date and time and saves them to the phone gallery (Pictures/CatCam). */
object PhotoStore {
    private const val MAX_SIDE = 1920
    private const val ALBUM = "CatCam"

    val stampFormat get() = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.US)

    /** Scales, rotates upright and draws the label + timestamp across the bottom. */
    fun stamp(source: Bitmap, rotationDegrees: Int, time: Date, label: String): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(source.width, source.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        val bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val unit = minOf(w, h) / 100f // 1% of the short side keeps the stamp proportional
        val canvas = Canvas(bitmap)

        val bandTop = h - unit * 20
        canvas.drawRect(0f, bandTop, w, h, Paint().apply {
            shader = LinearGradient(0f, bandTop, 0f, h, Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
        })

        val margin = unit * 4
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 5.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(unit * 0.6f, 0f, 0f, Color.BLACK)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 255, 255, 255)
            textSize = unit * 3f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(unit * 0.5f, 0f, 0f, Color.BLACK)
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 77, 79) }

        val timeBaseline = h - margin
        val labelBaseline = timeBaseline - timePaint.textSize * 1.2f
        val dotRadius = labelPaint.textSize * 0.32f
        canvas.drawCircle(margin + dotRadius, labelBaseline - labelPaint.textSize * 0.35f, dotRadius, dotPaint)
        canvas.drawText(label.uppercase(Locale.US), margin + dotRadius * 3.2f, labelBaseline, labelPaint)
        canvas.drawText(stampFormat.format(time), margin, timeBaseline, timePaint)
        return bitmap
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, time: Date): Uri {
        val name = "CatCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.DATE_TAKEN, time.time)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("הגלריה לא זמינה")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: throw IOException("לא ניתן לכתוב לגלריה")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Newest photo this app saved, so the thumbnail survives app restarts. */
    fun latestPhoto(context: Context): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            } else null
        }
    }.getOrNull()
}
