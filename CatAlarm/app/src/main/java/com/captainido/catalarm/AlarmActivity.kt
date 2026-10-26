package com.captainido.catalarm

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/** The full-screen ringing alarm: time, Oliver, and two clear actions — Stop and Snooze. */
class AlarmActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var callStatus: TextView
    private lateinit var rotator: PhotoRotator
    private val pulses = mutableListOf<AnimatorSet>()
    private var dialedFromScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        clock = findViewById(R.id.clockText)
        date = findViewById(R.id.dateText)
        callStatus = findViewById(R.id.callStatus)

        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            content.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom)
            insets
        }

        findViewById<View>(R.id.avatar).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }

        findViewById<MaterialButton>(R.id.snoozeButton).apply {
            text = "נודניק · ${Format.minutes(AlarmRepo.snoozeMinutes(this@AlarmActivity))}"
            setOnClickListener { snooze() }
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener { stop() }

        // Like the system clock, Back does not silence the alarm.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        rotator = PhotoRotator(
            PhotoRotator.Slide(findViewById(R.id.slideA), findViewById(R.id.slideABg), findViewById(R.id.slideAPhoto)),
            PhotoRotator.Slide(findViewById(R.id.slideB), findViewById(R.id.slideBBg), findViewById(R.id.slideBPhoto)),
            intervalMs = 6000,
        )

        AlarmService.onStopped = { finish() }
        AlarmService.onCallChanged = { updateCall() }
        updateCall()
    }

    override fun onStart() {
        super.onStart()
        bindLabel()
        // The service may still be starting when this screen opens; close only if nothing rings shortly after.
        handler.postDelayed({
            bindLabel()
            if (!AlarmService.isRinging) finish()
        }, 1500)
        rotator.start()
        startPulse()
        handler.post(clockLoop)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        rotator.stop()
        pulses.forEach { it.cancel() }
        pulses.clear()
        super.onStop()
    }

    override fun onDestroy() {
        AlarmService.onStopped = null
        AlarmService.onCallChanged = null
        super.onDestroy()
    }

    private fun bindLabel() {
        val alarm = AlarmRepo.get(this, AlarmService.ringingId)
        findViewById<TextView>(R.id.labelText).text = when {
            AlarmService.ringingId == AlarmService.PREVIEW_ID -> "תצוגה מקדימה"
            alarm != null && alarm.label.isNotBlank() -> alarm.label
            else -> "התראה"
        }
    }

    private val clockLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clock.text = Format.time(now)
            date.text = Format.longDate(now)
            handler.postDelayed(this, 5_000)
        }
    }

    /** Two soft gold rings expand from Oliver's portrait, out of phase. */
    private fun startPulse() {
        listOf(R.id.pulse1, R.id.pulse2).forEachIndexed { i, id ->
            val ring = findViewById<View>(id)
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.7f).apply { repeatCount = ValueAnimator.INFINITE },
                    ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.7f).apply { repeatCount = ValueAnimator.INFINITE },
                    ObjectAnimator.ofFloat(ring, View.ALPHA, 0.6f, 0f).apply { repeatCount = ValueAnimator.INFINITE },
                )
                duration = resources.getInteger(R.integer.motion_ambient).toLong() * 2
                interpolator = DecelerateInterpolator()
                startDelay = i * resources.getInteger(R.integer.motion_ambient).toLong()
            }
            set.start()
            pulses += set
        }
    }

    private fun updateCall() {
        if (AlarmService.callFailed && !AlarmService.callPlaced && !dialedFromScreen) dialFromScreen()
        if (AlarmService.callPlaced) {
            callStatus.text = "מתקשר ל${AlarmRepo.callName(this)} ברקע"
            callStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            callStatus.compoundDrawablesRelative[0]?.setTint(getColor(R.color.gold))
            callStatus.visibility = View.VISIBLE
        }
    }

    /** Fallback when the background call was refused: dial from this (foreground) screen. */
    private fun dialFromScreen() {
        dialedFromScreen = true
        val number = AlarmRepo.callNumber(this)
        if (number.isBlank() || checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            AlarmService.markCallPlaced()
        }
    }

    private fun stop() {
        AlarmService.onStopped = null
        AlarmService.stop(this)
        finish()
    }

    private fun snooze() {
        AlarmService.onStopped = null
        AlarmService.snooze(this)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (AlarmRepo.volumeKeys(this)) {
                AlarmRepo.VOLUME_KEYS_SNOOZE -> { snooze(); return true }
                AlarmRepo.VOLUME_KEYS_STOP -> { stop(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
