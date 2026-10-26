package com.captainido.catalarm

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** The wake-up screen: an impatient cat that only stops when petted enough. */
class AlarmActivity : Activity() {

    companion object {
        private const val PETS_TO_DISMISS = 10
        private const val SNOOZE_ESCAPES = 2

        private val NAGS = listOf(
            "מיאו! הקערה ריקה כבר שעה!",
            "קום! אני לא אאכיל את עצמי 😾",
            "שמתי לב שאתה עדיין במיטה. מאכזב.",
            "עוד דקה ואני מפיל משהו מהשולחן 🏺",
            "ליטוף = סליחה. בלי ליטוף = בלי סליחה.",
            "אני יודע איפה אתה ישן. 👀",
            "זה לא שעון מעורר. זה אולטימטום.",
            "בוקר טוב לך. לי עוד לא, כי אין אוכל.",
            "הכרית שלך נראית נוחה. בשבילי.",
            "אני אשב לך על הפנים. אל תבחן אותי.",
            "לטף אותי כדי לכבות. אלה החוקים. 🐾"
        )
        private val PET_REACTIONS = listOf(
            "פררר...", "עוד!", "יותר שמאלה.", "לא שם!", "אוקיי, זה נחמד",
            "אולי אסלח לך...", "המשך, עבד.", "פרררררר 😽", "מה, זה הכל?"
        )
        private val ESCAPES = listOf("חה! לא כל כך מהר 😹", "נודניק? לא בחתול שלי 😼")
        private val HEARTS = listOf("❤️", "🐾", "😻", "💕", "✨", "🐟")
        private val BACKGROUNDS = intArrayOf(0xFFFFD6A5.toInt(), 0xFFFFADAD.toInt(), 0xFFCAFFBF.toInt(), 0xFFA0C4FF.toInt(), 0xFFFDFFB6.toInt())
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var cat: ImageView
    private lateinit var caption: TextView
    private lateinit var counter: TextView
    private lateinit var clock: TextView
    private lateinit var snoozeButton: Button
    private var petsLeft = PETS_TO_DISMISS
    private var escapes = 0
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        root = findViewById(R.id.root)
        cat = findViewById(R.id.catImage)
        caption = findViewById(R.id.captionText)
        counter = findViewById(R.id.counterText)
        clock = findViewById(R.id.clockText)
        snoozeButton = findViewById(R.id.snoozeButton)

        cat.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 40 * resources.displayMetrics.density)
        }
        cat.clipToOutline = true
        cat.setOnClickListener { pet() }
        snoozeButton.setOnClickListener { trySnooze() }

        if (Build.VERSION.SDK_INT >= 33) {
            // The cat does not accept "back" as an answer.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                say("לאן אתה חושב שאתה הולך? 😾")
            }
        }

        AlarmService.onStopped = { if (!finished) finish() }

        updateCounter()
        startShow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        say("לאן אתה חושב שאתה הולך? 😾")
    }

    private fun startShow() {
        ObjectAnimator.ofFloat(cat, View.ROTATION, -7f, 7f).apply {
            duration = 280
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        ValueAnimator.ofArgb(*BACKGROUNDS).apply {
            duration = 6000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
        handler.post(nagLoop)
        handler.post(clockLoop)
        handler.post(pawRain)
        handler.postDelayed(pounce, 3500)
    }

    private val nagLoop = object : Runnable {
        override fun run() {
            say(NAGS.random())
            handler.postDelayed(this, 3200)
        }
    }

    private val clockLoop = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 10_000)
        }
    }

    /** Every few seconds the cat lunges at the screen. */
    private val pounce = object : Runnable {
        override fun run() {
            cat.animate().scaleX(1.25f).scaleY(1.25f).setDuration(180).withEndAction {
                cat.animate().scaleX(1f).scaleY(1f).setDuration(450)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
            handler.postDelayed(this, Random.nextLong(2500, 5000))
        }
    }

    private val pawRain = object : Runnable {
        override fun run() {
            if (root.width > 0) {
                val paw = emoji(if (Random.nextInt(6) == 0) "🐟" else "🐾", 28f)
                paw.alpha = 0.55f
                paw.x = Random.nextInt(root.width - 60).toFloat()
                paw.y = -100f
                paw.animate()
                    .translationY(root.height + 100f)
                    .rotation(Random.nextFloat() * 720 - 360)
                    .setDuration(Random.nextLong(3000, 5500))
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { root.removeView(paw) }
                    .start()
            }
            handler.postDelayed(this, 450)
        }
    }

    private fun pet() {
        if (finished) return
        petsLeft--
        cat.animate().cancel()
        cat.scaleX = 0.88f
        cat.scaleY = 0.88f
        cat.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(OvershootInterpolator(4f)).start()
        floatHeart()
        if (petsLeft <= 0) {
            dismissAlarm()
            return
        }
        handler.removeCallbacks(nagLoop)
        say(PET_REACTIONS.random())
        handler.postDelayed(nagLoop, 2500)
        updateCounter()
    }

    private fun floatHeart() {
        val heart = emoji(HEARTS.random(), 40f)
        heart.x = cat.x + cat.width * Random.nextFloat()
        heart.y = cat.y + cat.height * 0.4f
        heart.animate()
            .translationYBy(-500f)
            .translationXBy(Random.nextFloat() * 200 - 100)
            .alpha(0f)
            .scaleX(1.8f).scaleY(1.8f)
            .setDuration(1100)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { root.removeView(heart) }
            .start()
    }

    private fun trySnooze() {
        if (finished) return
        if (escapes < SNOOZE_ESCAPES) {
            // The snooze button runs away the first couple of times.
            say(ESCAPES[escapes])
            escapes++
            val maxX = (root.width - snoozeButton.width) / 2f
            val maxY = root.height / 2.5f
            snoozeButton.animate()
                .translationX(Random.nextFloat() * 2 * maxX - maxX)
                .translationY(-Random.nextFloat() * maxY)
                .rotation(Random.nextFloat() * 40 - 20)
                .setDuration(250)
                .start()
            return
        }
        finished = true
        AlarmService.onStopped = null
        AlarmService.snooze(this)
        Toast.makeText(this, "החתול יחזור בעוד ${AlarmScheduler.SNOOZE_MINUTES} דקות. עצבני יותר. 😾", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dismissAlarm() {
        finished = true
        AlarmService.onStopped = null
        AlarmService.dismiss(this)
        handler.removeCallbacksAndMessages(null)
        caption.text = "טוב. סלחתי לך. עכשיו - אוכל! 😼🍗"
        counter.text = "בוקר טוב ☀️"
        snoozeButton.visibility = View.GONE
        repeat(12) { handler.postDelayed({ floatHeart() }, it * 70L) }
        handler.postDelayed({ finish() }, 2200)
    }

    private fun updateCounter() {
        counter.text = "לטף את החתול עוד $petsLeft פעמים כדי לכבות 🐾"
    }

    private fun say(text: String) {
        caption.text = text
        caption.scaleX = 0.7f
        caption.scaleY = 0.7f
        caption.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator()).start()
    }

    private fun emoji(text: String, sizeSp: Float): TextView =
        TextView(this).also {
            it.text = text
            it.textSize = sizeSp
            root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (AlarmService.onStopped != null && !finished) AlarmService.onStopped = null
        super.onDestroy()
    }
}
