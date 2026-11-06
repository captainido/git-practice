package com.oliver.pet

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class CatEvent {
    MRRP, HAPPY_MEOW, ANNOYED_MEOW, HISS,
    TAIL_SWISH, EAR_TWITCH, BLINK,
    WARNING, LEAVE, RETURN,
}

enum class Mood { WAITING, CONTENT, PURRING, WARNING, ANNOYED, ANGRY, AWAY }

/**
 * Oliver's behavior, as pure logic (no Android), so it can be unit-tested.
 * All times are in milliseconds, speeds in reference-image pixels per second.
 */
class PettingEngine(private val rnd: Random = Random.Default) {

    companion object {
        const val TAP_MAX_MS = 230L
        const val TAP_MAX_TRAVEL = 30f
        const val RAPID_TAP_COUNT = 5
        const val RAPID_TAP_WINDOW_MS = 1500L
        const val GENTLE_MAX_SPEED = 1500f
        const val ROUGH_SPEED = 2600f
        const val PURR_THRESHOLD = 0.25f
    }

    // --- public state -------------------------------------------------------
    var pleasure = 0f; private set          // 0..1, builds while petting gently
    var annoyance = 0f; private set         // 0..1
    var overstim = 0f; private set          // 0..1, too much petting
    var purrLevel = 0f; private set         // 0..1, smoothed
    var touching = false; private set
    var currentZone = Zone.NONE; private set
    var awayUntil = 0L; private set
    val isAway get() = awayUntil > 0L

    val mood: Mood
        get() = when {
            isAway -> Mood.AWAY
            annoyance > 0.6f -> Mood.ANGRY
            annoyance > 0.25f -> Mood.ANNOYED
            warned -> Mood.WARNING
            purrLevel > 0.2f -> Mood.PURRING
            pleasure > 0.05f -> Mood.CONTENT
            else -> Mood.WAITING
        }

    // --- internal -----------------------------------------------------------
    private var lastT = -1L
    private var downT = 0L
    private var travel = 0f
    private var lastSpeed = 0f
    private var roughFor = 0L
    private val taps = ArrayDeque<Long>()
    private var lastAngerT = -100_000L
    private var overstimLimitMs = nextLimit()
    private var warned = false
    private var nextHappyMeowT = 0L
    private var lastEarTwitchT = 0L

    private fun nextLimit() = 34_000L + rnd.nextLong(14_000L)

    fun onDown(zone: Zone, t: Long, big: Boolean = false): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        if (isAway) return ev
        touching = true
        downT = t
        travel = 0f
        roughFor = 0L
        currentZone = zone
        when (zone) {
            Zone.TAIL -> {
                annoyance = min(1f, annoyance + 0.45f)
                purrLevel *= 0.3f
                pleasure = max(0f, pleasure - 0.35f)
                ev += CatEvent.TAIL_SWISH
                ev += if (annoyance >= 0.95f) CatEvent.HISS else CatEvent.ANNOYED_MEOW
            }
            Zone.EYES -> ev += CatEvent.BLINK
            Zone.EARS -> if (t - lastEarTwitchT > 600) { lastEarTwitchT = t; ev += CatEvent.EAR_TWITCH }
            else -> {}
        }
        if (big && zone != Zone.NONE) ev += hurt(t, 0.3f)
        return ev
    }

    /** [dist] travelled since last move (ref px), [dt] ms since last move. */
    fun onMove(zone: Zone, t: Long, dist: Float, dt: Long, big: Boolean = false): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        if (isAway || !touching) return ev
        if (zone != currentZone) {
            if (zone == Zone.TAIL) ev += onDown(zone, t)
            if (zone == Zone.EARS && t - lastEarTwitchT > 600) { lastEarTwitchT = t; ev += CatEvent.EAR_TWITCH }
            currentZone = zone
        }
        travel += dist
        if (dt > 0) lastSpeed = lastSpeed * 0.6f + (dist * 1000f / dt) * 0.4f
        if (lastSpeed > ROUGH_SPEED || big) {
            roughFor += dt
            if (roughFor > 350) { roughFor = 0; ev += hurt(t, 0.35f) }
        } else roughFor = max(0L, roughFor - dt)
        return ev
    }

    fun onUp(t: Long): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        if (!touching) return ev
        touching = false
        if (isAway) return ev
        val isTap = t - downT <= TAP_MAX_MS && travel < TAP_MAX_TRAVEL
        if (isTap && currentZone != Zone.NONE) {
            taps.addLast(t)
            while (taps.isNotEmpty() && t - taps.first() > RAPID_TAP_WINDOW_MS) taps.removeFirst()
            if (taps.size >= RAPID_TAP_COUNT) {
                taps.clear()
                ev += hurt(t, 0.7f)
            } else if (currentZone.isPleasant && taps.size == 1 && annoyance < 0.25f) {
                ev += CatEvent.MRRP
            }
        }
        lastSpeed = 0f
        currentZone = Zone.NONE
        return ev
    }

    private fun hurt(t: Long, amount: Float): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        val repeat = t - lastAngerT < 10_000L
        lastAngerT = t
        annoyance = min(1f, annoyance + amount)
        pleasure = max(0f, pleasure - 0.5f)
        purrLevel *= 0.3f
        ev += CatEvent.EAR_TWITCH
        ev += CatEvent.TAIL_SWISH
        ev += if (repeat || annoyance >= 0.95f) CatEvent.HISS else CatEvent.ANNOYED_MEOW
        return ev
    }

    /** Advance time. Call every frame. */
    fun tick(t: Long): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        if (lastT < 0) { lastT = t; nextHappyMeowT = t + 15_000L; return ev }
        val dt = (t - lastT).coerceIn(0L, 200L)
        lastT = t
        val s = dt / 1000f

        if (isAway) {
            if (t >= awayUntil) {
                awayUntil = 0L
                overstim = 0f; pleasure = 0f; annoyance = 0f; purrLevel = 0f; warned = false
                overstimLimitMs = nextLimit()
                ev += CatEvent.RETURN
            }
            return ev
        }

        val gentle = touching && currentZone.isPleasant && lastSpeed < GENTLE_MAX_SPEED && annoyance < 0.5f
        if (gentle) {
            // Holding still (scratching) also counts, a bit less than stroking.
            val moving = if (lastSpeed > 60f) 1f else 0.6f
            pleasure = min(1f, pleasure + s * 0.9f * currentZone.pleasure * moving)
        } else {
            pleasure = max(0f, pleasure - s * (if (touching) 0.05f else 0.12f))
        }
        annoyance = max(0f, annoyance - s * 0.12f)

        // Overstimulation grows only during continuous contact, recovers on breaks.
        if (touching && currentZone != Zone.NONE) overstim += dt.toFloat() / overstimLimitMs
        else overstim = max(0f, overstim - s / 25f)
        if (overstim >= 0.85f && !warned) {
            warned = true
            ev += CatEvent.WARNING; ev += CatEvent.TAIL_SWISH; ev += CatEvent.ANNOYED_MEOW
        } else if (overstim < 0.6f) warned = false
        if (overstim >= 1f) {
            awayUntil = t + 8_000L + rnd.nextLong(4_000L)
            touching = false
            purrLevel = 0f
            ev += CatEvent.LEAVE
            return ev
        }

        var target = if (pleasure > PURR_THRESHOLD && annoyance < 0.3f)
            ((pleasure - PURR_THRESHOLD) / (1f - PURR_THRESHOLD)).coerceIn(0.15f, 1f) else 0f
        if (warned) target = min(target, 0.3f)      // fed up: the purr fades before he leaves
        val k = if (target > purrLevel) 1.5f else 0.8f
        purrLevel += (target - purrLevel) * min(1f, s * k)
        if (purrLevel < 0.02f && target == 0f) purrLevel = 0f

        if (purrLevel > 0.5f && t >= nextHappyMeowT) {
            ev += CatEvent.HAPPY_MEOW
            nextHappyMeowT = t + 14_000L + rnd.nextLong(14_000L)
        } else if (purrLevel <= 0.5f && nextHappyMeowT < t + 4_000L) {
            nextHappyMeowT = t + 4_000L + rnd.nextLong(6_000L)
        }
        return ev
    }
}
