package com.oliver.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Oliver's real photo through a deformable mesh so the fur "gives" under the finger,
 * and animates breathing, purr tremble, blinking/squinting eyes, ear twitches and tail swishes.
 */
class CatView(context: Context) : View(context) {

    var haptics: HapticsController? = null
    var sound: SoundController? = null
    val engine = PettingEngine()

    private val photo: Bitmap = BitmapFactory.decodeResource(
        resources, R.drawable.oliver, BitmapFactory.Options().apply { inScaled = false }
    )
    private val bgBlur: Bitmap = Bitmap.createScaledBitmap(photo, 27, 48, true)
    private val bw = photo.width.toFloat()
    private val bh = photo.height.toFloat()
    private val rs = bw / BodyZones.REF_W       // bitmap px per reference px

    // ---- mesh -------------------------------------------------------------------------------
    private val mw = 30
    private val mh = 54
    private val n = (mw + 1) * (mh + 1)
    private val baseX = FloatArray(n)
    private val baseY = FloatArray(n)
    private val dX = FloatArray(n)
    private val dY = FloatArray(n)
    private val vX = FloatArray(n)
    private val vY = FloatArray(n)
    private val wCat = FloatArray(n)
    private val wTail = FloatArray(n)
    private val wEar = FloatArray(n)
    private val wBreath = FloatArray(n)
    private val wHead = FloatArray(n)
    private val verts = FloatArray(n * 2)

    // ---- layout -----------------------------------------------------------------------------
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    // ---- animation state --------------------------------------------------------------------
    private var lastFrame = 0L
    private var breathPhase = 0.0
    private var swishAmp = 0f
    private var earAmp = 0f
    private var leanX = 0f
    private var leanY = 0f
    private var blinkStart = -1L
    private var nextBlink = 0L
    private var eyesHeld = false
    private var angerFlash = 0f
    private var hissFlash = 0f
    private var awayAnim = 0f                 // 0 = here, 1 = gone
    private var paused = false
    private val rnd = Random(SystemClock.uptimeMillis())

    // ---- touch ------------------------------------------------------------------------------
    private var touchX = 0f                   // bitmap px
    private var touchY = 0f
    private var touching = false
    private var lastMoveT = 0L
    private var tickTravel = 0f
    private var lastHeartT = 0L
    private val bigTouchPx = 70f * resources.displayMetrics.density

    private class Particle(var x: Float, var y: Float, var vy: Float, var life: Float, val text: String, val size: Float)
    private val particles = ArrayList<Particle>()

    // ---- paints -----------------------------------------------------------------------------
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lidEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint().apply { color = 0x66000000 }
    private val lidColor: Int
    private val lidPath = Path()
    private val clipPath = Path()
    private val rect = RectF()

    var moodText: (Mood) -> String = { it.name }

    init {
        for (j in 0..mh) for (i in 0..mw) {
            val k = j * (mw + 1) + i
            val x = bw * i / mw
            val y = bh * j / mh
            baseX[k] = x; baseY[k] = y
            val rx = x / rs; val ry = y / rs
            wCat[k] = BodyZones.catWeight(rx, ry)
            wTail[k] = falloff(hypot(rx - 130f, ry - 1078f), 70f, 45f)
            wEar[k] = max(
                falloff(hypot(rx - 490f, ry - 385f), 55f, 30f),
                falloff(hypot(rx - 750f, ry - 490f), 60f, 30f)
            )
            val headD = hypot(rx - BodyZones.HEAD_CX, ry - BodyZones.HEAD_CY)
            wHead[k] = falloff(headD, 170f, 90f) * wCat[k]
            val bodyBand = smooth((ry - 700f) / 120f) * smooth((1260f - ry) / 120f)
            wBreath[k] = wCat[k] * bodyBand * (1f - wTail[k])
        }
        lidColor = sampleColor(560f, 470f)
        lidPaint.color = lidColor
        lidEdge.color = Color.rgb(35, 36, 44)
        nextBlink = SystemClock.uptimeMillis() + 2500
        isHapticFeedbackEnabled = false
    }

    private fun falloff(d: Float, r0: Float, feather: Float) =
        if (d <= r0) 1f else max(0f, 1f - (d - r0) / feather)

    private fun smooth(v: Float): Float { val t = v.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun sampleColor(refX: Float, refY: Float): Int {
        var r = 0; var g = 0; var b = 0; var c = 0
        for (dy in -6..6 step 3) for (dx in -6..6 step 3) {
            val p = photo.getPixel(((refX + dx) * rs).toInt().coerceIn(0, photo.width - 1), ((refY + dy) * rs).toInt().coerceIn(0, photo.height - 1))
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); c++
        }
        return Color.rgb(r / c, g / c, b / c)
    }

    private fun darken(c: Int, f: Float) =
        Color.rgb((Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w / bw, h / bh)
        ox = (w - bw * scale) / 2f
        oy = (h - bh * scale) / 2f
        val s = max(w / bw, h / bh)
        cover.set((w - bw * s) / 2f, (h - bh * s) / 2f, (w + bw * s) / 2f, (h + bh * s) / 2f)
    }

    fun setPaused(p: Boolean) {
        paused = p
        if (p) { touching = false; engine.onUp(SystemClock.uptimeMillis()) }
        else { lastFrame = 0L; invalidate() }
    }

    // =========================================================================================
    // Touch
    // =========================================================================================
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val bx = (e.x - ox) / scale
        val by = (e.y - oy) / scale
        val zone = BodyZones.zoneAt(bx / rs, by / rs)
        val big = e.touchMajor > bigTouchPx
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                touchX = bx; touchY = by
                lastMoveT = now
                tickTravel = 0f
                eyesHeld = zone == Zone.EYES
                handle(engine.onDown(zone, now, big))
                if (zone != Zone.NONE && !engine.isAway) haptics?.furTick(0.4f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                val ddx = bx - touchX
                val ddy = by - touchY
                val dist = hypot(ddx, ddy)
                val dt = (now - lastMoveT).coerceAtLeast(1L)
                if (!engine.isAway && engine.annoyance < 0.7f) pushFur(bx, by, ddx, ddy)
                handle(engine.onMove(zone, now, dist / rs, dt, big))
                // Fur texture ticks proportional to distance travelled over the cat.
                if (zone != Zone.NONE && !engine.isAway) {
                    tickTravel += dist / rs
                    if (tickTravel > 16f) {
                        tickTravel = 0f
                        val speed = dist / rs * 1000f / dt
                        haptics?.furTick(0.25f + min(0.6f, speed / 2500f))
                    }
                }
                eyesHeld = zone == Zone.EYES
                if (zone.isPleasant && engine.purrLevel > 0.15f && now - lastHeartT > 260 && dist > 1f) {
                    lastHeartT = now
                    particles += Particle(bx, by - 30f * rs, -(90f + rnd.nextFloat() * 60f), 1f,
                        if (rnd.nextInt(4) == 0) "💕" else "💗", 26f + rnd.nextFloat() * 14f)
                }
                touchX = bx; touchY = by
                lastMoveT = now
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                eyesHeld = false
                handle(engine.onUp(now))
            }
        }
        return true
    }

    /** Drag the fur under the finger in the stroke direction (Gaussian brush). */
    private fun pushFur(x: Float, y: Float, ddx: Float, ddy: Float) {
        val sigma = 55f * rs
        val reach = sigma * 3f
        val inv = 1f / (2f * sigma * sigma)
        val maxD = 34f * rs
        val i0 = max(0, ((x - reach) / bw * mw).toInt()); val i1 = min(mw, ((x + reach) / bw * mw).toInt() + 1)
        val j0 = max(0, ((y - reach) / bh * mh).toInt()); val j1 = min(mh, ((y + reach) / bh * mh).toInt() + 1)
        for (j in j0..j1) for (i in i0..i1) {
            val k = j * (mw + 1) + i
            val ex = baseX[k] + dX[k] - x
            val ey = baseY[k] + dY[k] - y
            val g = exp(-(ex * ex + ey * ey) * inv) * wCat[k]
            if (g < 0.01f) continue
            dX[k] += ddx * g * 0.55f
            dY[k] += ddy * g * 0.55f
            val m = hypot(dX[k], dY[k])
            if (m > maxD) { dX[k] *= maxD / m; dY[k] *= maxD / m }
        }
    }

    // =========================================================================================
    // Events → sound / vibration / animation
    // =========================================================================================
    private fun handle(events: List<CatEvent>) {
        for (ev in events) when (ev) {
            CatEvent.MRRP -> { sound?.play(SoundCategory.MRRP); haptics?.mrrp(); earAmp = max(earAmp, 0.5f) }
            CatEvent.HAPPY_MEOW -> sound?.play(SoundCategory.HAPPY)
            CatEvent.ANNOYED_MEOW -> { sound?.play(SoundCategory.ANNOYED); haptics?.annoyed(); angerFlash = 1f }
            CatEvent.HISS -> { sound?.play(SoundCategory.HISS, force = true); haptics?.hiss(); angerFlash = 1f; hissFlash = 1f }
            CatEvent.TAIL_SWISH -> { swishAmp = 0.38f; haptics?.tailSwish() }
            CatEvent.EAR_TWITCH -> earAmp = 1f
            CatEvent.BLINK -> blinkStart = SystemClock.uptimeMillis()
            CatEvent.WARNING -> earAmp = 1f
            CatEvent.LEAVE -> {
                touching = false
                sound?.play(SoundCategory.ANNOYED, force = true)
                haptics?.setPurr(0f); haptics?.leave()
                sound?.setPurr(0f)
            }
            CatEvent.RETURN -> { sound?.play(SoundCategory.MRRP, force = true); haptics?.mrrp() }
        }
    }

    // =========================================================================================
    // Frame
    // =========================================================================================
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val tSec = now / 1000.0

        handle(engine.tick(now))
        val purr = engine.purrLevel
        if (!engine.isAway) { haptics?.setPurr(purr); sound?.setPurr(purr) }

        // springs (slightly under-damped so fur jiggles back)
        val kSpring = if (touching) 70f else 140f
        val damp = 13f
        for (k in 0 until n) {
            vX[k] += (-kSpring * dX[k] - damp * vX[k]) * dt
            vY[k] += (-kSpring * dY[k] - damp * vY[k]) * dt
            dX[k] += vX[k] * dt
            dY[k] += vY[k] * dt
        }

        // head leans into the hand when petting the head happily
        val headZone = touching && engine.currentZone in HEAD_ZONES
        var tx = 0f; var ty = 0f
        if (headZone && engine.annoyance < 0.3f) {
            val hx = BodyZones.HEAD_CX * rs; val hy = BodyZones.HEAD_CY * rs
            val dx = touchX - hx; val dy = touchY - hy
            val d = max(1f, hypot(dx, dy))
            val amt = 14f * rs * (0.3f + 0.7f * engine.pleasure)
            tx = dx / d * amt; ty = dy / d * amt
        }
        leanX += (tx - leanX) * min(1f, dt * 4f)
        leanY += (ty - leanY) * min(1f, dt * 4f)

        // breathing: faster & deeper while purring
        val breathHz = 0.33 + 0.12 * purr
        breathPhase += 2 * PI * breathHz * dt
        val breath = sin(breathPhase).toFloat() * (0.007f + 0.004f * purr)
        val tremble = sin(tSec * 2 * PI * 25).toFloat() * 0.7f * rs * purr

        // tail: gentle sway when content, decaying swish when annoyed
        swishAmp *= exp(-dt / 0.6f)
        val sway = 0.06f + 0.10f * purr
        val tailAngle = (sway * sin(tSec * 2 * PI * 0.45) + swishAmp * sin(tSec * 2 * PI * 3.2)).toFloat()
        val px = BodyZones.TAIL_PIVOT_X * rs; val py = BodyZones.TAIL_PIVOT_Y * rs

        earAmp *= exp(-dt / 0.25f)
        val earOff = -(sin(tSec * 2 * PI * 11).toFloat() * earAmp * 7f * rs)

        // away animation
        val awayTarget = if (engine.isAway) 1f else 0f
        awayAnim += (awayTarget - awayAnim) * min(1f, dt * 3f)

        val bcx = 470f * rs; val bcy = BodyZones.BREATH_CY * rs
        for (k in 0 until n) {
            var x = baseX[k] + dX[k]
            var y = baseY[k] + dY[k]
            val wb = wBreath[k]
            if (wb > 0f) {
                x += (baseX[k] - bcx) * breath * wb * 1.2f
                y += (baseY[k] - bcy) * breath * wb + tremble * wb
            }
            val wh = wHead[k]
            if (wh > 0f) { x += leanX * wh; y += leanY * wh + tremble * 0.5f * wh }
            val we = wEar[k]
            if (we > 0f) y += earOff * we
            val wt = wTail[k]
            if (wt > 0f) {
                val a = tailAngle * wt
                val c = cos(a); val s = sin(a)
                val rx = x - px; val ry = y - py
                x = px + rx * c - ry * s
                y = py + rx * s + ry * c
            }
            verts[k * 2] = ox + x * scale
            verts[k * 2 + 1] = oy + y * scale
        }

        // ---- draw ----
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bgBlur, null, cover, bgPaint)
        canvas.drawRect(rect, dim)

        canvas.save()
        canvas.translate(awayAnim * width * 0.25f, 0f)
        meshPaint.alpha = (255 * (1f - awayAnim * 0.85f)).toInt()
        canvas.drawBitmapMesh(photo, mw, mh, verts, 0, null, 0, meshPaint)
        if (awayAnim < 0.5f) drawEyes(canvas, now, purr)
        canvas.restore()

        drawParticles(canvas, dt)
        drawOverlay(canvas, dt)

        if (!paused) postInvalidateOnAnimation()
    }

    private val cover = RectF()

    private companion object {
        val HEAD_ZONES = setOf(Zone.FOREHEAD, Zone.CHEEKS, Zone.CHIN, Zone.EARS)
    }

    /** Screen position of a reference point after mesh deformation (nearest-vertex). */
    private fun mapRef(rx: Float, ry: Float, out: FloatArray) {
        val bx = rx * rs; val by = ry * rs
        val fi = bx / bw * mw; val fj = by / bh * mh
        val i = fi.toInt().coerceIn(0, mw - 1); val j = fj.toInt().coerceIn(0, mh - 1)
        val u = fi - i; val v = fj - j
        fun vx(ii: Int, jj: Int) = verts[(jj * (mw + 1) + ii) * 2]
        fun vy(ii: Int, jj: Int) = verts[(jj * (mw + 1) + ii) * 2 + 1]
        out[0] = (vx(i, j) * (1 - u) + vx(i + 1, j) * u) * (1 - v) + (vx(i, j + 1) * (1 - u) + vx(i + 1, j + 1) * u) * v
        out[1] = (vy(i, j) * (1 - u) + vy(i + 1, j) * u) * (1 - v) + (vy(i, j + 1) * (1 - u) + vy(i + 1, j + 1) * u) * v
    }

    private val tmp = FloatArray(2)

    private fun drawEyes(canvas: Canvas, now: Long, purr: Float) {
        if (now >= nextBlink && blinkStart < 0) { blinkStart = now; nextBlink = now + 2500 + rnd.nextLong(4500) }
        var blink = 0f
        if (blinkStart >= 0) {
            val p = (now - blinkStart) / 170f
            if (p >= 1f) blinkStart = -1 else blink = sin(PI * p).toFloat()
        }
        val base = when {
            eyesHeld -> 1f
            engine.mood == Mood.ANGRY || engine.mood == Mood.ANNOYED -> 0.3f
            else -> 0.62f * purr + 0.12f * engine.pleasure
        }
        val c = max(blink, base).coerceIn(0f, 1f) * 0.9f
        if (c < 0.03f) return
        val k = rs * scale                                    // ref px -> screen px
        for (eye in arrayOf(BodyZones.EYE_L, BodyZones.EYE_R)) {
            // follow the mesh: offset between deformed and undeformed eye center
            mapRef(eye.cx, eye.cy, tmp)
            val cx = tmp[0]; val cy = tmp[1]
            val offX = cx - (ox + eye.cx * k)
            val offY = cy - (oy + eye.cy * k)
            val rx = eye.rx * 0.94f * k
            val ry = eye.ry * 0.98f * k
            val lidY = cy - ry + 2 * ry * c
            val shift = lidY - (cy - ry)                      // how far the fur slides down

            val layer = canvas.saveLayer(cx - rx - 8, cy - ry - 8, cx + rx + 8, cy + ry + 8, null)
            // 1) upper lid = the real fur from above the eye, slid down over it
            canvas.save()
            lidPath.reset()
            lidPath.moveTo(cx - rx - 8, cy - ry - 8)
            lidPath.lineTo(cx + rx + 8, cy - ry - 8)
            lidPath.lineTo(cx + rx + 8, lidY - ry * 0.3f * c)
            lidPath.quadTo(cx, lidY + ry * 0.35f * c, cx - rx - 8, lidY - ry * 0.3f * c)
            lidPath.close()
            canvas.clipPath(lidPath)
            canvas.translate(ox + offX, oy + offY + shift)
            canvas.scale(scale, scale)
            val sx = (eye.cx - eye.rx * 1.3f) * rs
            val sy = (eye.cy - eye.ry * 1.3f) * rs - shift / scale
            srcRect.set(sx.toInt(), sy.toInt(), (sx + eye.rx * 2.6f * rs).toInt() + 1, (sy + eye.ry * 2.6f * rs + shift / scale).toInt() + 1)
            dstRect.set(srcRect)
            canvas.drawBitmap(photo, srcRect, dstRect, meshPaint)
            canvas.restore()
            // 2) dark lash line along the lid edge
            lidEdge.strokeWidth = max(2f, 3f * k)
            lidEdge.alpha = (110 + 90 * c).toInt()
            lidPath.reset()
            lidPath.moveTo(cx - rx - 6, lidY - ry * 0.3f * c)
            lidPath.quadTo(cx, lidY + ry * 0.35f * c, cx + rx + 6, lidY - ry * 0.3f * c)
            canvas.drawPath(lidPath, lidEdge)
            // 3) feather everything into an ellipse so no hard circle edge is visible
            canvas.save()
            canvas.scale(1f, ry / rx, cx, cy)
            featherPaint.shader = RadialGradient(cx, cy, rx, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, rx + 1, featherPaint)
            canvas.restore()
            canvas.restoreToCount(layer)
        }
    }

    private val srcRect = Rect()
    private val dstRect = Rect()
    private val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private fun drawParticles(canvas: Canvas, dt: Float) {
        val d = resources.displayMetrics.density
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt * 0.9f
            if (p.life <= 0f) { it.remove(); continue }
            p.y += p.vy * dt
            textPaint.textSize = p.size * d
            textPaint.alpha = (255 * min(1f, p.life * 2f)).toInt()
            canvas.drawText(p.text, ox + p.x * scale, oy + p.y * scale, textPaint)
        }
        textPaint.alpha = 255
    }

    private fun drawOverlay(canvas: Canvas, dt: Float) {
        val d = resources.displayMetrics.density
        // anger symbol near the head
        angerFlash = max(0f, angerFlash - dt * 0.8f)
        hissFlash = max(0f, hissFlash - dt * 1.2f)
        if ((angerFlash > 0f || engine.annoyance > 0.35f) && !engine.isAway) {
            mapRef(760f, 400f, tmp)
            val pulse = 1f + 0.12f * sin(SystemClock.uptimeMillis() / 90.0).toFloat()
            textPaint.textSize = 34f * d * pulse
            textPaint.alpha = (255 * max(angerFlash, engine.annoyance).coerceIn(0f, 1f)).toInt()
            canvas.drawText("💢", tmp[0], tmp[1], textPaint)
            textPaint.alpha = 255
        }
        if (hissFlash > 0f) {
            mapRef(575f, 800f, tmp)
            textPaint.textSize = 40f * d
            textPaint.alpha = (255 * min(1f, hissFlash * 1.5f)).toInt()
            textPaint.color = Color.rgb(255, 80, 80)
            canvas.drawText("חחחח!", tmp[0], tmp[1], textPaint)
            textPaint.color = Color.WHITE
            textPaint.alpha = 255
        }
        if (engine.purrLevel > 0.3f && !engine.isAway) {
            mapRef(470f, 860f, tmp)
            textPaint.textSize = 18f * d
            textPaint.alpha = (200 * engine.purrLevel).toInt()
            val wob = sin(SystemClock.uptimeMillis() / 160.0).toFloat() * 3f * d
            canvas.drawText("~ גררר ~", tmp[0] + wob, tmp[1], textPaint)
            textPaint.alpha = 255
        }

        // mood pill + happiness bar at the top
        val top = 52f * d
        val pillW = min(width - 32f * d, 340f * d)
        val left = (width - pillW) / 2f
        rect.set(left, top, left + pillW, top + 62f * d)
        canvas.drawRoundRect(rect, 31f * d, 31f * d, barBg)
        textPaint.color = Color.WHITE
        textPaint.textSize = 19f * d
        canvas.drawText(moodText(engine.mood), width / 2f, top + 28f * d, textPaint)
        val happy = (engine.pleasure - engine.annoyance).coerceIn(-1f, 1f)
        val barL = left + 24f * d; val barR = left + pillW - 24f * d; val barY = top + 44f * d
        val mid = (barL + barR) / 2f
        barFg.color = 0x44FFFFFF
        canvas.drawRoundRect(barL, barY, barR, barY + 7f * d, 4f * d, 4f * d, barFg)
        barFg.color = if (happy >= 0) Color.rgb(255, 120, 170) else Color.rgb(255, 70, 60)
        val endX = mid + (barR - mid) * happy
        canvas.drawRoundRect(min(mid, endX), barY, max(mid, endX), barY + 7f * d, 4f * d, 4f * d, barFg)
        if (engine.overstim > 0.6f && !engine.isAway) {
            // a tiny "patience" warning dot
            barFg.color = Color.rgb(255, 190, 60)
            canvas.drawCircle(barR + 10f * d, barY + 3.5f * d, 4f * d * (0.8f + 0.2f * abs(sin(SystemClock.uptimeMillis() / 200.0).toFloat())), barFg)
        }
    }
}
