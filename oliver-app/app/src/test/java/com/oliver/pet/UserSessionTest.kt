package com.oliver.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.ViewGroup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Runs the real app (real drawing code, real behavior, real vibration calls) through a scripted
 * "user petting Oliver" session, saving frames + a timeline for review.
 * Frames go to the directory in system property `oliver.frames`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")          // 1080 x 2340, a typical phone
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UserSessionTest {

    private lateinit var view: CatView
    private lateinit var bmp: Bitmap
    private lateinit var canvas: Canvas
    private val outDir = File(System.getProperty("oliver.frames") ?: "build/session")
    private val timeline = StringBuilder()
    private val pendingEvents = mutableListOf<String>()
    private val allEvents = mutableListOf<CatEvent>()
    private var frameNo = 0
    private var savedNo = 0
    private var simMs = 0L
    private var finger: Pair<Float, Float>? = null
    private var caption = ""
    private var saveEvery = 2
    private var vibratingFrames = 0
    private var purrFramesWithVibration = 0
    private var maxPurr = 0f
    private val errors = mutableListOf<String>()
    private lateinit var vib: Vibrator

    private fun ensureView() {
        val ctx = RuntimeEnvironment.getApplication()
        vib = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        shadowOf(vib).setHasAmplitudeControl(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        view = root.getChildAt(0) as CatView
        view.onCatEvent = { e -> pendingEvents += e.name; allEvents += e }
        val w = 1080; val h = 2340
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp)
        outDir.deleteRecursively(); outDir.mkdirs()
    }

    private fun screen(rx: Float, ry: Float) = view.refToScreen(rx, ry)

    private fun frame() {
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        simMs += 16
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            view.draw(canvas)
        } catch (t: Throwable) {
            errors += "t=${simMs}ms draw crash: $t"
            throw t
        }
        val sv = shadowOf(vib)
        val vibrating = sv.isVibrating
        if (vibrating) vibratingFrames++
        val purr = view.engine.purrLevel
        if (purr > maxPurr) maxPurr = purr
        if (purr > 0.3f && vibrating) purrFramesWithVibration++
        frameNo++
        if (frameNo % saveEvery == 0 || pendingEvents.isNotEmpty() && frameNo % saveEvery == 1) {
            if (frameNo % saveEvery == 0) {
                val f = File(outDir, "f%05d.png".format(savedNo++))
                val small = Bitmap.createScaledBitmap(bmp, 540, 1170, true)
                f.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val fx = finger?.let { "%.0f,%.0f".format(it.first / 2, it.second / 2) } ?: "-"
                timeline.append(
                    "${f.name}\t$simMs\t$caption\t$fx\t${view.engine.mood}\t" +
                        "%.2f\t%.2f\t%.2f\t%.2f\t$vibrating\t${pendingEvents.joinToString(",")}\n".format(
                            purr, view.engine.pleasure, view.engine.annoyance, view.engine.overstim
                        )
                )
                pendingEvents.clear()
            }
        }
    }

    private fun touch(action: Int, rx: Float, ry: Float) {
        val (x, y) = screen(rx, ry)
        finger = if (action == MotionEvent.ACTION_UP) null else Pair(x, y)
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun idle(ms: Long) { repeat((ms / 16).toInt()) { frame() } }

    private fun tap(rx: Float, ry: Float, holdMs: Long = 80) {
        touch(MotionEvent.ACTION_DOWN, rx, ry); idle(holdMs); touch(MotionEvent.ACTION_UP, rx, ry)
    }

    /** Stroke back and forth along a path at ~[speed] ref px/s for [ms]. */
    private fun stroke(path: List<Pair<Float, Float>>, ms: Long, speed: Float = 450f, lift: Boolean = true) {
        var seg = 0; var dir = 1; var t = 0f
        var (x, y) = path[0]
        touch(MotionEvent.ACTION_DOWN, x, y)
        var elapsed = 0L
        while (elapsed < ms) {
            val a = path[seg]; val b = path[seg + dir]
            val len = hypot(b.first - a.first, b.second - a.second)
            t += speed * 0.016f / len
            if (t >= 1f) {
                t = 0f; seg += dir
                if (seg + dir !in path.indices) dir = -dir
            } else {
                x = a.first + (b.first - a.first) * t
                y = a.second + (b.second - a.second) * t
            }
            touch(MotionEvent.ACTION_MOVE, x, y)
            frame(); elapsed += 16
            if (view.engine.isAway) break
        }
        if (lift) touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test
    fun pettingSession() {
        ensureView()
        val cheekChin = listOf(420f to 560f, 450f to 640f, 540f to 700f, 620f to 700f, 700f to 600f)
        val back = listOf(230f to 680f, 245f to 860f, 265f to 1050f)

        caption = "1 · Oliver waiting"; idle(1500)
        caption = "2 · Single tap on the forehead"; tap(580f, 450f); idle(900)
        caption = "3 · Slow stroke on cheeks and chin"; stroke(cheekChin, 6000)
        caption = "4 · Stroking the back"; stroke(back, 3000); idle(600)
        caption = "5 · Touching the tail"; tap(130f, 1078f, 150); idle(2500)
        caption = "6 · 5 fast taps"
        repeat(5) { tap(240f, 850f, 60); idle(140) }
        idle(1200)
        caption = "7 · Again, 5 fast taps"
        repeat(5) { tap(240f, 850f, 60); idle(140) }
        caption = "8 · Recovering"; idle(9000)
        caption = "9 · Petting non-stop (x4 speed)"; saveEvery = 8
        stroke(cheekChin, 60_000)
        saveEvery = 2
        caption = "10 · Oliver walked away"; idle(13_000)
        caption = "11 · Back, gentle stroke"; stroke(cheekChin, 4000); idle(1000)

        File(outDir, "timeline.tsv").writeText(timeline.toString())
        val summary = buildString {
            appendLine("frames=$frameNo saved=$savedNo simSeconds=${simMs / 1000}")
            appendLine("events=" + allEvents.groupingBy { it }.eachCount())
            appendLine("maxPurr=$maxPurr vibratingFrames=$vibratingFrames purrFramesWithVibration=$purrFramesWithVibration")
            appendLine("errors=$errors")
        }
        File(outDir, "summary.txt").writeText(summary)
        println(summary)

        // What a user must experience:
        assertTrue("mrrp on single tap", CatEvent.MRRP in allEvents)
        assertTrue("purring reached", maxPurr > 0.5f)
        assertTrue("vibration while purring", purrFramesWithVibration > 30)
        assertTrue("tail annoys", CatEvent.TAIL_SWISH in allEvents)
        assertTrue("hiss on repeated rapid taps", CatEvent.HISS in allEvents)
        assertTrue("leaves after too much petting", CatEvent.LEAVE in allEvents)
        assertTrue("comes back", CatEvent.RETURN in allEvents)
    }
}
