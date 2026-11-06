package com.oliver.pet

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Record Oliver's real voice for each sound category. */
class RecordActivity : Activity() {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingCat: SoundCategory? = null
    private var player: MediaPlayer? = null
    private var pendingCat: SoundCategory? = null
    private lateinit var list: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private val stopRunnable = Runnable { stopRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(36), px(18), px(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(246, 243, 250))
            addView(list)
        })
        build()
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = getString(R.string.back)
            textSize = 16f
            setTextColor(Color.rgb(110, 70, 160))
            setPadding(0, 0, 0, px(10))
            setOnClickListener { finish() }
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.rec_title)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(40, 30, 60))
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.rec_explain)
            textSize = 14f
            setTextColor(Color.rgb(90, 85, 100))
            setPadding(0, px(6), 0, px(14))
        })
        for (c in SoundCategory.entries) list.addView(card(c))
    }

    private fun card(c: SoundCategory): View {
        val recs = c.recordings(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) }
            elevation = px(2).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@RecordActivity).apply {
                text = getString(c.labelRes); textSize = 18f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(40, 30, 60))
            })
            addView(TextView(this@RecordActivity).apply {
                text = if (recs.isEmpty()) getString(R.string.rec_default) else getString(R.string.rec_count, recs.size)
                textSize = 13f; setTextColor(Color.rgb(120, 115, 130))
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val isRec = recordingCat == c
        header.addView(Button(this).apply {
            text = getString(if (isRec) R.string.rec_stop else R.string.rec_start)
            isEnabled = recordingCat == null || isRec
            setOnClickListener { if (isRec) stopRecording() else startRecording(c) }
        })
        box.addView(header)
        recs.forEachIndexed { i, f ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(6), 0, 0)
                addView(TextView(this@RecordActivity).apply {
                    text = getString(R.string.rec_item, i + 1); textSize = 15f; setTextColor(Color.DKGRAY)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@RecordActivity).apply { text = getString(R.string.rec_play); setOnClickListener { play(f) } })
                addView(Button(this@RecordActivity).apply {
                    text = getString(R.string.rec_delete)
                    setOnClickListener { f.delete(); build() }
                })
            })
        }
        return box
    }

    private fun startRecording(c: SoundCategory) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCat = c
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        stopPlayer()
        val f = File(c.folder(this), "rec_${System.currentTimeMillis()}.m4a")
        try {
            recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            recordingFile = f
            recordingCat = c
            list.postDelayed(stopRunnable, 10_000)
        } catch (e: Exception) {
            recorder?.release(); recorder = null; f.delete()
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show()
        }
        build()
    }

    private fun stopRecording() {
        list.removeCallbacks(stopRunnable)
        val r = recorder ?: return
        try { r.stop() } catch (e: RuntimeException) { recordingFile?.delete() }  // too short
        r.release()
        recorder = null
        recordingCat = null
        recordingFile = null
        build()
    }

    private fun play(f: File) {
        stopPlayer()
        player = try {
            MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        } catch (e: Exception) { null }
    }

    private fun stopPlayer() { player?.release(); player = null }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val c = pendingCat; pendingCat = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && c != null) startRecording(c)
        else Toast.makeText(this, R.string.rec_need_permission, Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        stopRecording()
        stopPlayer()
        super.onPause()
    }
}
