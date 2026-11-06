package com.oliver.pet

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import kotlin.random.Random

enum class SoundCategory(val dir: String, val defaultRes: Int, val labelRes: Int) {
    HAPPY("happy", R.raw.meow_happy, R.string.cat_happy),
    MRRP("mrrp", R.raw.mrrp, R.string.cat_mrrp),
    ANNOYED("annoyed", R.raw.meow_annoyed, R.string.cat_annoyed),
    PURR("purr", R.raw.purr, R.string.cat_purr),
    HISS("hiss", R.raw.hiss, R.string.cat_hiss);

    fun folder(ctx: Context) = File(ctx.filesDir, "sounds/$dir").apply { mkdirs() }
    fun recordings(ctx: Context): List<File> =
        folder(ctx).listFiles { f -> f.isFile && f.length() > 0 }?.sortedBy { it.name } ?: emptyList()
}

/** Plays Oliver's voice: user recordings if present, otherwise the built-in sounds. */
class SoundController(private val ctx: Context) {

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
    private val ids = HashMap<SoundCategory, List<Int>>()
    private var purr: MediaPlayer? = null
    private var purrVol = 0f
    private var lastVoiceAt = 0L

    fun load() {
        pool.autoPause()
        ids.values.flatten().forEach { pool.unload(it) }
        ids.clear()
        for (c in SoundCategory.entries) {
            if (c == SoundCategory.PURR) continue
            val recs = c.recordings(ctx)
            ids[c] = if (recs.isNotEmpty()) recs.map { pool.load(it.absolutePath, 1) }
            else listOf(pool.load(ctx, c.defaultRes, 1))
        }
        releasePurr()
        val recs = SoundCategory.PURR.recordings(ctx)
        purr = try {
            if (recs.isNotEmpty()) MediaPlayer().apply {
                setAudioAttributes(attrs); setDataSource(recs.random().absolutePath); prepare()
            } else MediaPlayer.create(ctx, R.raw.purr, attrs, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) {
            MediaPlayer.create(ctx, R.raw.purr)
        }
        purr?.isLooping = true
        purr?.setVolume(0f, 0f)
    }

    /** Play a voice line. Meows don't overlap each other (a cat has one mouth). */
    fun play(c: SoundCategory, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastVoiceAt < 450) return
        lastVoiceAt = now
        val list = ids[c] ?: return
        val rate = 0.93f + Random.nextFloat() * 0.14f
        val vol = 0.85f + Random.nextFloat() * 0.15f
        pool.play(list.random(), vol, vol, 1, 0, rate)
        // Ducking: the purr gets quieter while he meows.
        purr?.setVolume(purrVol * 0.3f, purrVol * 0.3f)
    }

    fun setPurr(level: Float) {
        val p = purr ?: return
        val v = (level * 0.9f).coerceIn(0f, 0.9f)
        purrVol = v
        if (v > 0.01f) {
            if (!p.isPlaying) p.start()
            val duck = if (System.currentTimeMillis() - lastVoiceAt < 900) 0.3f else 1f
            p.setVolume(v * duck, v * duck)
        } else if (p.isPlaying) {
            p.pause()
        }
    }

    fun pause() { purr?.takeIf { it.isPlaying }?.pause(); pool.autoPause() }

    private fun releasePurr() { purr?.release(); purr = null }

    fun release() { releasePurr(); pool.release() }
}
