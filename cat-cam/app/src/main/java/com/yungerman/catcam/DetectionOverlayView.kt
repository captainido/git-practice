package com.yungerman.catcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws the detected cat's box over the live preview (which uses scaleType fillCenter). */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var box: CatCamState.CatBox? = null
    private var confirmed = false
    private var mirrored = false

    private val density = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val tagRect = RectF()

    fun show(box: CatCamState.CatBox?, confirmed: Boolean, mirrored: Boolean) {
        this.box = box
        this.confirmed = confirmed
        this.mirrored = mirrored
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = box ?: return
        // Map the normalized frame onto the view the same way PreviewView's fillCenter does.
        val frameW = b.frameAspect
        val scale = max(width / frameW, height / 1f)
        val dx = (width - frameW * scale) / 2
        val dy = (height - scale) / 2
        val left = if (mirrored) 1 - b.rect.right else b.rect.left
        val right = if (mirrored) 1 - b.rect.left else b.rect.right
        rect.set(dx + left * frameW * scale, dy + b.rect.top * scale, dx + right * frameW * scale, dy + b.rect.bottom * scale)

        val color = if (confirmed) COLOR_CONFIRMED else COLOR_CHECKING
        strokePaint.color = color
        val corner = 12 * density
        canvas.drawRoundRect(rect, corner, corner, strokePaint)

        val label = "חתול ${Math.round(b.score * 100)}%"
        val pad = 6 * density
        val tagH = textPaint.textSize + 2 * pad
        val tagW = textPaint.measureText(label) + 2 * pad
        val tagTop = (rect.top - tagH - 4 * density).coerceAtLeast(0f)
        tagRect.set(rect.left, tagTop, rect.left + tagW, tagTop + tagH)
        tagPaint.color = color
        canvas.drawRoundRect(tagRect, tagH / 2, tagH / 2, tagPaint)
        canvas.drawText(label, tagRect.left + pad, tagRect.bottom - pad - textPaint.descent() / 2, textPaint)
    }

    private companion object {
        val COLOR_CONFIRMED = Color.rgb(61, 220, 132)
        val COLOR_CHECKING = Color.rgb(245, 166, 35)
    }
}
