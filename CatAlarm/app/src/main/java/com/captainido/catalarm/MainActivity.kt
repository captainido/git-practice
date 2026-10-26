package com.captainido.catalarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timeText: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var repeatCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var permWarning: TextView
    private lateinit var catQuote: TextView
    private var meow: MediaPlayer? = null
    private var binding = false

    private val quotes = listOf(
        "אל תיגע לי בבטן. ברצינות.",
        "אני לא שמן, אני פלאפי.",
        "תזכורת: האוכל שלי מאחר.",
        "קבעת שעה? חמוד. אני אחליט.",
        "פרררר... עכשיו תביא חטיף."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timeText = findViewById(R.id.timeText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        repeatCheck = findViewById(R.id.repeatCheck)
        statusText = findViewById(R.id.statusText)
        permWarning = findViewById(R.id.permWarning)
        catQuote = findViewById(R.id.catQuote)
        val catFace = findViewById<ImageView>(R.id.catFace)

        catFace.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        catFace.clipToOutline = true
        catFace.setOnClickListener { meowAt(it) }

        timeText.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                AlarmStore.setTime(this, h, m)
                AlarmStore.setEnabled(this, true)
                AlarmScheduler.schedule(this)
                refresh()
                Toast.makeText(this, "סגור. החתול ער ומוכן 😼", Toast.LENGTH_SHORT).show()
            }, AlarmStore.hour(this), AlarmStore.minute(this), true).show()
        }
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setEnabled(this, checked)
            AlarmScheduler.schedule(this)
            refresh()
        }
        repeatCheck.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setRepeating(this, checked)
            refresh()
        }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            AlarmService.start(this)
            startActivity(Intent(this, AlarmActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        meow?.release()
        meow = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    private fun refresh() {
        binding = true
        timeText.text = String.format("%02d:%02d", AlarmStore.hour(this), AlarmStore.minute(this))
        enabledSwitch.isChecked = AlarmStore.isEnabled(this)
        repeatCheck.isChecked = AlarmStore.isRepeating(this)
        binding = false
        refreshStatus()
        refreshPermissions()
    }

    private fun refreshStatus() {
        if (!AlarmStore.isEnabled(this)) {
            statusText.text = "😴 החתול ישן. הפעל את המעורר כדי להעיר אותו."
            return
        }
        val diff = AlarmScheduler.nextTrigger(AlarmStore.hour(this), AlarmStore.minute(this)) - System.currentTimeMillis()
        val totalMin = (diff + 59_999) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        val inText = when {
            h == 0L -> "$m דקות"
            m == 0L -> "$h שעות"
            else -> "$h שעות ו-$m דקות"
        }
        statusText.text = "😼 החתול יעיר אותך בעוד $inText"
    }

    /** Shows the first missing permission the alarm needs, tap to fix it. */
    private fun refreshPermissions() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pkg = Uri.parse("package:$packageName")
        val problem: Pair<String, Intent>? = when {
            !nm.areNotificationsEnabled() -> "⚠️ ההתראות כבויות - החתול לא יוכל להעיר אותך. לחץ כאן כדי לאשר." to
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() -> "⚠️ צריך הרשאת \"מסך מלא\" כדי שהחתול יקפוץ על מסך הנעילה. לחץ כאן." to
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() -> "⚠️ צריך הרשאת \"התראות ותזכורות\" כדי שהחתול יעיר בדיוק בזמן. לחץ כאן." to
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            else -> null
        }
        if (problem == null) {
            permWarning.visibility = View.GONE
            return
        }
        permWarning.visibility = View.VISIBLE
        permWarning.text = problem.first
        permWarning.setOnClickListener {
            runCatching { startActivity(problem.second) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        }
    }

    /** Tapping the cat plays a random-pitched snippet of the recording. */
    private fun meowAt(view: View) {
        view.animate().cancel()
        view.rotation = 0f
        view.animate().rotationBy(360f).setDuration(600).setInterpolator(OvershootInterpolator()).start()
        catQuote.text = quotes.random()
        meow?.release()
        meow = MediaPlayer.create(this, R.raw.cat_alarm)?.apply {
            val mood = floatArrayOf(0.7f, 1f, 1.5f, 2f).random()
            runCatching { playbackParams = PlaybackParams().setSpeed(mood).setPitch(mood) }
            start()
        }
        handler.postDelayed({ meow?.runCatching { if (isPlaying) stop() } }, 2500)
    }
}
