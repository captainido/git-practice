package com.oliver.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var catView: CatView
    private lateinit var haptics: HapticsController
    private lateinit var sound: SoundController
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val prefs = getSharedPreferences("oliver", MODE_PRIVATE)
        haptics = HapticsController(this).apply { enabled = prefs.getBoolean("vibe", true) }
        sound = SoundController(this)

        catView = CatView(this).also {
            it.haptics = haptics
            it.sound = sound
            it.moodText = { m ->
                getString(
                    when (m) {
                        Mood.WAITING -> R.string.mood_waiting
                        Mood.CONTENT -> R.string.mood_content
                        Mood.PURRING -> R.string.mood_purring
                        Mood.WARNING -> R.string.mood_warning
                        Mood.ANNOYED -> R.string.mood_annoyed
                        Mood.ANGRY -> R.string.mood_angry
                        Mood.AWAY -> R.string.mood_away
                    }
                )
            }
        }

        val d = resources.displayMetrics.density
        fun pill(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0x99000000.toInt()) }
        }

        val record = pill(getString(R.string.btn_record)).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, RecordActivity::class.java)) }
        }
        val vibe = pill("").apply {
            fun refresh() { text = getString(if (haptics.enabled) R.string.btn_vibe_on else R.string.btn_vibe_off) }
            refresh()
            setOnClickListener {
                haptics.enabled = !haptics.enabled
                prefs.edit().putBoolean("vibe", haptics.enabled).apply()
                refresh()
            }
        }
        hint = TextView(this).apply {
            text = getString(R.string.hint)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (8 * d).toInt())
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, (28 * d).toInt())
            addView(hint)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(record)
                addView(View(this@MainActivity), LinearLayout.LayoutParams((12 * d).toInt(), 1))
                addView(vibe)
            })
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(catView, FrameLayout.LayoutParams(-1, -1))
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })

        // Fade the hint out after a while; it's only needed the first time.
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(800).start() }, 9000)
    }

    override fun onResume() {
        super.onResume()
        sound.load()              // picks up new recordings
        catView.setPaused(false)
    }

    override fun onPause() {
        catView.setPaused(true)
        haptics.stopAll()
        sound.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }
}
