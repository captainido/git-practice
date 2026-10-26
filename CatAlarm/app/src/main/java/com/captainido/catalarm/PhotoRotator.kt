package com.captainido.catalarm

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView

/**
 * Cross-fades between two stacked slides. Each slide shows the whole photo (fitCenter)
 * on top of a blurred copy of itself, so every photo fits the screen without cropping.
 */
class PhotoRotator(
    private var front: Slide,
    private var back: Slide,
    private val intervalMs: Long = 4500,
    private val onChange: ((Int) -> Unit)? = null,
) {
    class Slide(val root: View, val background: ImageView, val photo: ImageView)

    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var zoom: ObjectAnimator? = null

    fun start() {
        stop()
        show(front, index)
        front.root.alpha = 1f
        back.root.alpha = 0f
        zoomIn(front)
        onChange?.invoke(index)
        handler.postDelayed(tick, intervalMs)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        zoom?.cancel()
    }

    /** Jump to the next photo right away (e.g. when the user taps). */
    fun next() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            index = (index + 1) % Names.PHOTOS.size
            show(back, index)
            back.root.animate().alpha(1f).setDuration(900).start()
            front.root.animate().alpha(0f).setDuration(900).start()
            zoomIn(back)
            val previous = front
            front = back
            back = previous
            onChange?.invoke(index)
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun show(slide: Slide, i: Int) {
        slide.background.setImageResource(Names.PHOTO_BGS[i])
        slide.photo.setImageResource(Names.PHOTOS[i])
    }

    /** Gentle zoom on the photo only, so it never gets cut off by much. */
    private fun zoomIn(slide: Slide) {
        zoom?.cancel()
        zoom = ObjectAnimator.ofPropertyValuesHolder(
            slide.photo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
        ).apply {
            duration = intervalMs + 900
            interpolator = LinearInterpolator()
            start()
        }
    }
}
