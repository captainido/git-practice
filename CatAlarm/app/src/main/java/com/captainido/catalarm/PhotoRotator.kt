package com.captainido.catalarm

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView

/** Cross-fades between two stacked ImageViews, with a slow zoom on the visible photo. */
class PhotoRotator(
    private var front: ImageView,
    private var back: ImageView,
    private val photos: IntArray,
    private val intervalMs: Long = 4500,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var zoom: ObjectAnimator? = null

    fun start() {
        stop()
        front.setImageResource(photos[index])
        front.alpha = 1f
        back.alpha = 0f
        zoomIn(front)
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
            index = (index + 1) % photos.size
            back.setImageResource(photos[index])
            back.animate().alpha(1f).setDuration(900).start()
            front.animate().alpha(0f).setDuration(900).start()
            zoomIn(back)
            val previous = front
            front = back
            back = previous
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun zoomIn(view: View) {
        zoom?.cancel()
        zoom = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
        ).apply {
            duration = intervalMs + 900
            interpolator = LinearInterpolator()
            start()
        }
    }
}
