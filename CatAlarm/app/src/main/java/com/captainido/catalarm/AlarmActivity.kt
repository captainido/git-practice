package com.captainido.catalarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** The wake-up screen: Oliver, impatient, only stops when petted enough. */
class AlarmActivity : AppCompatActivity() {

    companion object {
        private const val PETS_TO_DISMISS = 10
        private const val SNOOZE_ESCAPES = 2

        private val NAGS = listOf(
            "${Names.HUMAN}! קום! הקערה ריקה כבר שעה!",
            "אני, ${Names.CAT}, לא אאכיל את עצמי 😾",
            "${Names.HUMAN}, שמתי לב שאתה עדיין במיטה. מאכזב.",
            "עוד דקה ואני מפיל משהו מהשולחן 🏺",
            "ליטוף = סליחה. בלי ליטוף = בלי סליחה.",
            "אני יודע איפה אתה ישן, ${Names.HUMAN}. 👀",
            "זה לא שעון מעורר. זה אולטימטום.",
            "בוקר טוב לך. לי עוד לא, כי אין אוכל.",
            "הכרית שלך נראית נוחה. בשבילי.",
            "אני אשב לך על הפנים. אל תבחן אותי.",
        )
        private val PET_REACTIONS = listOf(
            "פררר...", "עוד!", "יותר שמאלה.", "לא שם!", "אוקיי, זה נחמד",
            "אולי אסלח לך...", "המשך, ${Names.HUMAN}.", "פרררררר 😽", "מה, זה הכל?"
        )
        private val ESCAPES = listOf("חה! לא כל כך מהר 😹", "נודניק? לא אצל ${Names.CAT} 😼")
        private val HEARTS = listOf("❤️", "🐾", "😻", "💕", "✨", "🐟")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fx: FrameLayout
    private lateinit var photoFrame: FrameLayout
    private lateinit var bubble: LinearLayout
    private lateinit var caption: TextView
    private lateinit var counter: TextView
    private lateinit var clock: TextView
    private lateinit var petHint: TextView
    private lateinit var callStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var snoozeButton: MaterialButton
    private lateinit var rotator: PhotoRotator
    private var pets = 0
    private var escapes = 0
    private var finished = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        fx = findViewById(R.id.fxLayer)
        photoFrame = findViewById(R.id.photoFrame)
        bubble = findViewById(R.id.bubble)
        caption = findViewById(R.id.captionText)
        counter = findViewById(R.id.counterText)
        clock = findViewById(R.id.clockText)
        petHint = findViewById(R.id.petHint)
        callStatus = findViewById(R.id.callStatus)
        progress = findViewById(R.id.petProgress)
        snoozeButton = findViewById(R.id.snoozeButton)

        val topPanel = findViewById<View>(R.id.topPanel)
        val bottomPanel = findViewById<View>(R.id.bottomPanel)
        val topPad = topPanel.paddingTop
        val bottomPad = bottomPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topPanel.updatePadding(top = topPad + bars.top)
            bottomPanel.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }

        findViewById<View>(R.id.bubbleAvatar).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }

        rotator = PhotoRotator(
            PhotoRotator.Slide(findViewById(R.id.slideA), findViewById(R.id.slideABg), findViewById(R.id.slideAPhoto)),
            PhotoRotator.Slide(findViewById(R.id.slideB), findViewById(R.id.slideBBg), findViewById(R.id.slideBPhoto)),
            intervalMs = 3500,
        )
        photoFrame.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) pet(e.x, e.y)
            true
        }
        snoozeButton.setOnClickListener { trySnooze() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = say("לאן אתה חושב שאתה הולך? 😾")
        })

        AlarmService.onStopped = { if (!finished) finish() }
        AlarmService.onCallPlaced = { onCallUpdate() }

        progress.max = PETS_TO_DISMISS
        updateCounter()
        onCallUpdate()
        startShow()
    }

    /** Shows the call status, and dials from here if the background call was blocked. */
    private fun onCallUpdate() {
        if (AlarmService.callFailed && !AlarmService.callPlaced) dialFromScreen()
        if (AlarmService.callPlaced) {
            callStatus.text = "📞 ${Names.CAT} מתקשר ל${AlarmStore.callName(this)}..."
            callStatus.visibility = View.VISIBLE
        }
    }

    private fun dialFromScreen() {
        val number = AlarmStore.callNumber(this)
        if (number.isBlank() || checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            AlarmService.markCallPlaced()
        }
    }

    private fun startShow() {
        rotator.start()
        petHint.animate().scaleX(1.06f).scaleY(1.06f).setDuration(600).withEndAction(object : Runnable {
            override fun run() {
                if (finished) return
                val grow = petHint.scaleX < 1.03f
                petHint.animate().scaleX(if (grow) 1.06f else 1f).scaleY(if (grow) 1.06f else 1f)
                    .setDuration(600).withEndAction(this).start()
            }
        }).start()
        handler.post(nagLoop)
        handler.post(clockLoop)
        handler.post(pawRain)
        handler.postDelayed(bubbleShake, 2500)
    }

    private val nagLoop = object : Runnable {
        override fun run() {
            val lines = NAGS.toMutableList()
            if (AlarmService.callPlaced) lines += "התקשרתי ל${AlarmStore.callName(this@AlarmActivity)}. עכשיו אתה בצרות 😼"
            say(lines.random())
            handler.postDelayed(this, 3200)
        }
    }

    private val clockLoop = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            handler.postDelayed(this, 10_000)
        }
    }

    /** Every few seconds Oliver's speech bubble gets impatient. */
    private val bubbleShake = object : Runnable {
        override fun run() {
            bubble.animate().translationX(16f).setDuration(60).withEndAction {
                bubble.animate().translationX(0f).setDuration(400).setInterpolator(OvershootInterpolator(8f)).start()
            }.start()
            handler.postDelayed(this, Random.nextLong(2500, 5000))
        }
    }

    private val pawRain = object : Runnable {
        override fun run() {
            if (fx.width > 60) {
                val paw = emoji(if (Random.nextInt(6) == 0) "🐟" else "🐾", 22f)
                paw.alpha = 0.45f
                paw.x = Random.nextInt(fx.width - 60).toFloat()
                paw.y = -100f
                paw.animate()
                    .translationY(fx.height + 100f)
                    .rotation(Random.nextFloat() * 720 - 360)
                    .setDuration(Random.nextLong(4000, 6500))
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { fx.removeView(paw) }
                    .start()
            }
            handler.postDelayed(this, 800)
        }
    }

    private fun pet(x: Float, y: Float) {
        if (finished) return
        pets++
        photoFrame.animate().cancel()
        photoFrame.scaleX = 0.97f
        photoFrame.scaleY = 0.97f
        photoFrame.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(3f)).start()
        floatHeart(x, y)
        progress.setProgressCompat(pets, true)
        if (pets >= PETS_TO_DISMISS) {
            dismissAlarm()
            return
        }
        if (pets % 3 == 0) rotator.next()
        handler.removeCallbacks(nagLoop)
        say(PET_REACTIONS.random())
        handler.postDelayed(nagLoop, 2500)
        updateCounter()
    }

    private fun floatHeart(x: Float, y: Float) {
        val heart = emoji(HEARTS.random(), 40f)
        heart.x = x - 40
        heart.y = y - 60
        heart.animate()
            .translationYBy(-450f)
            .translationXBy(Random.nextFloat() * 200 - 100)
            .alpha(0f)
            .scaleX(1.8f).scaleY(1.8f)
            .setDuration(1100)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { fx.removeView(heart) }
            .start()
    }

    private fun trySnooze() {
        if (finished) return
        if (escapes < SNOOZE_ESCAPES) {
            // The snooze button dodges the first couple of times.
            say(ESCAPES[escapes])
            escapes++
            val dodge = 70 * resources.displayMetrics.density
            snoozeButton.animate()
                .translationX(if (Random.nextBoolean()) dodge else -dodge)
                .translationY(-Random.nextFloat() * dodge * 2)
                .rotation(Random.nextFloat() * 16 - 8)
                .setDuration(220)
                .start()
            return
        }
        finished = true
        clearCallbacks()
        AlarmService.snooze(this)
        Toast.makeText(this, "${Names.CAT} יחזור בעוד ${AlarmScheduler.SNOOZE_MINUTES} דקות. עצבני יותר. 😾", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dismissAlarm() {
        finished = true
        clearCallbacks()
        AlarmService.dismiss(this)
        handler.removeCallbacksAndMessages(null)
        caption.text = "טוב, ${Names.HUMAN}. סלחתי לך. עכשיו – אוכל! 😼🍗"
        petHint.text = "בוקר טוב ☀️"
        counter.text = ""
        snoozeButton.visibility = View.GONE
        val cx = fx.width / 2f
        val cy = fx.height / 2f
        repeat(14) { handler.postDelayed({ floatHeart(cx + Random.nextInt(-200, 200), cy + Random.nextInt(-200, 200)) }, it * 70L) }
        handler.postDelayed({ finish() }, 2200)
    }

    private fun updateCounter() {
        counter.text = "$pets / $PETS_TO_DISMISS ליטופים"
    }

    private fun say(text: String) {
        caption.text = text
        caption.alpha = 0f
        caption.animate().alpha(1f).setDuration(250).start()
    }

    private fun emoji(text: String, sizeSp: Float): TextView =
        TextView(this).also {
            it.text = text
            it.textSize = sizeSp
            fx.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun clearCallbacks() {
        AlarmService.onStopped = null
        AlarmService.onCallPlaced = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rotator.stop()
        if (!finished) clearCallbacks()
        super.onDestroy()
    }
}
