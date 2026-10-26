package com.captainido.catalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        private val HEBREW: Locale = Locale.forLanguageTag("he")
        private val QUOTES = listOf(
            "אל תיגע לי בבטן. ברצינות.",
            "אני לא שמן, אני פלאפי.",
            "תזכורת: האוכל שלי מאחר.",
            "קבעת שעה? חמוד. אני אחליט.",
            "פרררר... עכשיו תביא חטיף.",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var greetingText: TextView
    private lateinit var heroQuote: TextView
    private lateinit var dots: LinearLayout
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var countdownText: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var repeatSwitch: MaterialSwitch
    private lateinit var callSwitch: MaterialSwitch
    private lateinit var callTitle: TextView
    private lateinit var callSubtitle: TextView
    private lateinit var warningCard: MaterialCardView
    private lateinit var warningText: TextView
    private lateinit var rotator: PhotoRotator
    private var meow: MediaPlayer? = null
    private var binding = false
    private var chooserShowing = false

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst() && !c.getString(1).isNullOrBlank()) {
                AlarmStore.setCallContact(this, c.getString(0) ?: Names.DEFAULT_CALLEE, c.getString(1))
                AlarmStore.setCallEnabled(this, true)
                refresh()
            }
        }
    }

    /** Set when the user taps the permission warning; if Android no longer shows the dialog, open Settings. */
    private var openSettingsIfDenied = false

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (openSettingsIfDenied && result.values.any { !it }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        openSettingsIfDenied = false
        autoFindCallee()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        greetingText = findViewById(R.id.greetingText)
        heroQuote = findViewById(R.id.heroQuote)
        dots = findViewById(R.id.dots)
        timeText = findViewById(R.id.timeText)
        dateText = findViewById(R.id.dateText)
        countdownText = findViewById(R.id.countdownText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        repeatSwitch = findViewById(R.id.repeatSwitch)
        callSwitch = findViewById(R.id.callSwitch)
        callTitle = findViewById(R.id.callTitle)
        callSubtitle = findViewById(R.id.callSubtitle)
        warningCard = findViewById(R.id.warningCard)
        warningText = findViewById(R.id.warningText)

        buildDots()
        rotator = PhotoRotator(
            PhotoRotator.Slide(findViewById(R.id.slideA), findViewById(R.id.slideABg), findViewById(R.id.slideAPhoto)),
            PhotoRotator.Slide(findViewById(R.id.slideB), findViewById(R.id.slideBBg), findViewById(R.id.slideBPhoto)),
            onChange = ::selectDot,
        )
        heroQuote.text = "המעורר האישי של ${Names.HUMAN} · לחץ עליי 🐾"
        findViewById<View>(R.id.heroCard).setOnClickListener { meow() }

        timeText.setOnClickListener { pickTime(lockedDate = null) }
        findViewById<MaterialButton>(R.id.pickTimeButton).setOnClickListener { pickTime(lockedDate = null) }
        dateText.setOnClickListener { pickDate() }
        findViewById<MaterialButton>(R.id.pickDateButton).setOnClickListener { pickDate() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setEnabled(this, checked)
            AlarmScheduler.schedule(this)
            if (checked && !AlarmStore.isEnabled(this)) snack("הזמן שנבחר כבר עבר – בחר שעה חדשה ⏳")
            refresh()
        }
        repeatSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setRepeating(this, checked)
            AlarmScheduler.schedule(this)
            refresh()
        }
        callSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setCallEnabled(this, checked)
            if (checked) askMissingPermissions()
            refresh()
        }
        findViewById<MaterialButton>(R.id.pickContactButton).setOnClickListener { openContactPicker() }
        findViewById<MaterialButton>(R.id.testButton).setOnClickListener { startDemo() }

        askMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        autoFindCallee()
        refresh()
        rotator.start()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        rotator.stop()
        super.onPause()
    }

    override fun onDestroy() {
        meow?.release()
        meow = null
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshCountdown()
            handler.postDelayed(this, 30_000)
        }
    }

    // ---- Permissions & contact ----

    private fun askMissingPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (AlarmStore.isCallEnabled(this@MainActivity)) {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_CONTACTS)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray()) else openSettingsIfDenied = false
    }

    /** Looks Lilach up in the address book; asks which one if several contacts match. */
    private fun autoFindCallee() {
        if (chooserShowing || !AlarmStore.isCallEnabled(this) || AlarmStore.callNumber(this).isNotBlank()) return
        val matches = ContactFinder.find(this, Names.DEFAULT_CALLEE)
        when {
            matches.isEmpty() -> return
            matches.size == 1 -> AlarmStore.setCallContact(this, matches[0].name, matches[0].number)
            else -> MaterialAlertDialogBuilder(this)
                .also { chooserShowing = true }
                .setTitle("למי ${Names.CAT} יתקשר?")
                .setItems(matches.map { "${it.name}   ‎${it.number}" }.toTypedArray()) { _, i ->
                    AlarmStore.setCallContact(this, matches[i].name, matches[i].number)
                    refresh()
                }
                .setOnDismissListener { chooserShowing = false }
                .show()
        }
    }

    private fun openContactPicker() {
        runCatching {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }.onFailure { snack("לא נמצאה אפליקציית אנשי קשר") }
    }

    private fun callReady() =
        AlarmStore.isCallEnabled(this) && AlarmStore.callNumber(this).isNotBlank() &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun startDemo() {
        val launch = { withCall: Boolean ->
            AlarmService.start(this, withCall)
            startActivity(Intent(this, AlarmActivity::class.java))
        }
        if (!callReady()) {
            launch(false)
            return
        }
        val callee = AlarmStore.callName(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("הדגמה")
            .setMessage("בהשכמה אמיתית ${Names.CAT} מתקשר ל$callee באותו רגע. להדגים גם את השיחה?")
            .setPositiveButton("רק את ${Names.CAT}") { _, _ -> launch(false) }
            .setNegativeButton("גם להתקשר ל$callee") { _, _ -> launch(true) }
            .show()
    }

    // ---- Date & time ----

    private fun pickDate() {
        val current = Calendar.getInstance().apply { timeInMillis = AlarmStore.alarmAt(this@MainActivity) }
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val selection = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("באיזה תאריך ${Names.CAT} יעיר?")
            .setSelection(maxOf(selection, today))
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setStart(today)
                    .setValidator(DateValidatorPointForward.from(today))
                    .build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
            current.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
            // Straight on to the time for that date.
            pickTime(lockedDate = current)
        }
        picker.show(supportFragmentManager, "date")
    }

    /**
     * With [lockedDate] the alarm goes on that exact date; without it the time goes to its
     * nearest upcoming occurrence (later today if still ahead, otherwise tomorrow), unless the
     * user earlier chose a specific future date, which is kept.
     */
    private fun pickTime(lockedDate: Calendar?) {
        val current = Calendar.getInstance().apply { timeInMillis = AlarmStore.alarmAt(this@MainActivity) }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
            .setHour(current.get(Calendar.HOUR_OF_DAY))
            .setMinute(current.get(Calendar.MINUTE))
            .setTitleText(if (lockedDate != null) "באיזו שעה ב${shortDate(lockedDate.timeInMillis)}?" else "באיזו שעה?")
            .build()
        picker.addOnPositiveButtonClickListener {
            val now = System.currentTimeMillis()
            val keepDate = lockedDate
                ?: current.takeIf { AlarmStore.isDateLocked(this) && startOfDay(it.timeInMillis) > startOfDay(now) }
            val at = if (keepDate != null) {
                (keepDate.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, picker.hour)
                    set(Calendar.MINUTE, picker.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                AlarmScheduler.nextOccurrence(picker.hour, picker.minute, now)
            }
            if (at <= now) {
                snack("השעה הזו כבר עברה היום – בחר שעה מאוחרת יותר ⏳")
                return@addOnPositiveButtonClickListener
            }
            AlarmStore.setDateLocked(this, keepDate != null)
            AlarmStore.setAlarmAt(this, at)
            AlarmStore.setEnabled(this, true)
            AlarmScheduler.schedule(this)
            refresh()
            AlarmScheduler.nextTrigger(this)?.let {
                snack("סגור! ${Names.CAT} יעיר את ${Names.HUMAN} ${countdown(it - System.currentTimeMillis())} 😼")
            }
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun startOfDay(time: Long) = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- Rendering ----

    private fun refresh() {
        binding = true
        greetingText.text = greeting()
        val next = AlarmScheduler.nextTrigger(this) ?: AlarmStore.alarmAt(this)
        timeText.text = SimpleDateFormat("HH:mm", Locale.US).format(next)
        dateText.text = describeDate(next)
        enabledSwitch.isChecked = AlarmStore.isEnabled(this)
        repeatSwitch.isChecked = AlarmStore.isRepeating(this)

        val callee = AlarmStore.callName(this)
        val number = AlarmStore.callNumber(this)
        callTitle.text = "התקשרות ל$callee עם ההשכמה"
        callSubtitle.text = when {
            number.isBlank() && !ContactFinder.canRead(this) -> "צריך גישה לאנשי הקשר כדי למצוא את ${Names.DEFAULT_CALLEE}"
            number.isBlank() -> "לא מצאתי את ${Names.DEFAULT_CALLEE} באנשי הקשר – בחר ידנית"
            else -> "‎$number · ברמקול, באותו רגע"
        }
        callSwitch.isChecked = AlarmStore.isCallEnabled(this)
        binding = false

        refreshCountdown()
        refreshWarnings()
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (hour) {
            in 5..11 -> "בוקר טוב"
            in 12..16 -> "צהריים טובים"
            in 17..21 -> "ערב טוב"
            else -> "לילה טוב"
        }
        return "$hello, ${Names.HUMAN} 👋"
    }

    private fun refreshCountdown() {
        val next = AlarmScheduler.nextTrigger(this)
        countdownText.text = if (!AlarmStore.isEnabled(this) || next == null) {
            "😴 המעורר כבוי"
        } else {
            "⏰ ${countdown(next - System.currentTimeMillis())}"
        }
    }

    private fun shortDate(time: Long): String {
        val target = startOfDay(time)
        val today = startOfDay(System.currentTimeMillis())
        val tomorrow = startOfDay(today + 36 * 3600_000L)
        return when (target) {
            today -> "היום"
            tomorrow -> "מחר"
            else -> SimpleDateFormat("d בMMMM", HEBREW).format(time)
        }
    }

    private fun describeDate(time: Long): String {
        val day = SimpleDateFormat("EEEE, d בMMMM yyyy", HEBREW).format(time)
        val prefix = when (shortDate(time)) {
            "היום" -> "היום · "
            "מחר" -> "מחר · "
            else -> ""
        }
        val repeat = if (AlarmStore.isRepeating(this)) " · כל יום" else ""
        return "$prefix$day$repeat"
    }

    private fun countdown(ms: Long): String {
        val totalMin = (ms + 59_999) / 60_000
        val days = totalMin / (60 * 24)
        val hours = (totalMin / 60) % 24
        val mins = totalMin % 60
        val parts = buildList {
            if (days > 0) add(if (days == 1L) "יום" else "$days ימים")
            if (hours > 0) add(if (hours == 1L) "שעה" else "$hours שעות")
            if (mins > 0 && days == 0L) add(if (mins == 1L) "דקה" else "$mins דקות")
        }
        return if (parts.isEmpty()) "עוד רגע" else "בעוד " + parts.joinToString(" ו-")
    }

    /** Shows the first thing that would stop Oliver from working; tap to fix it. */
    private fun refreshWarnings() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pkg = Uri.parse("package:$packageName")
        val callOn = AlarmStore.isCallEnabled(this)
        val callee = AlarmStore.callName(this)
        val problem: Pair<String, () -> Unit>? = when {
            !nm.areNotificationsEnabled() -> "⚠️ ההתראות כבויות – ${Names.CAT} לא יוכל להעיר. לחץ כאן כדי לאשר." to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                "⚠️ צריך הרשאת \"מסך מלא\" כדי ש${Names.CAT} יקפוץ על מסך הנעילה. לחץ כאן." to {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                }
            Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() ->
                "⚠️ צריך הרשאת \"התראות ותזכורות\" כדי ש${Names.CAT} יעיר בדיוק בזמן. לחץ כאן." to {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                }
            callOn && (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
                AlarmStore.callNumber(this).isBlank() && !ContactFinder.canRead(this)) ->
                "⚠️ כדי להתקשר ל$callee, ${Names.CAT} צריך הרשאות לשיחות ולאנשי קשר. לחץ כאן." to {
                    openSettingsIfDenied = true
                    askMissingPermissions()
                }
            callOn && AlarmStore.callNumber(this).isBlank() ->
                "⚠️ לא מצאתי את ${Names.DEFAULT_CALLEE} באנשי הקשר. לחץ כאן כדי לבחור." to { openContactPicker() }
            else -> null
        }
        if (problem == null) {
            warningCard.visibility = View.GONE
            return
        }
        warningCard.visibility = View.VISIBLE
        warningText.text = problem.first
        warningCard.setOnClickListener {
            runCatching { problem.second() }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        }
    }

    // ---- Hero ----

    private fun buildDots() {
        val d = resources.displayMetrics.density
        repeat(Names.PHOTOS.size) {
            dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.dot)
                layoutParams = LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply {
                    marginStart = (5 * d).toInt()
                }
            })
        }
    }

    private fun selectDot(index: Int) {
        val d = resources.displayMetrics.density
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val active = i == index
            dot.alpha = if (active) 1f else 0.45f
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = ((if (active) 18 else 6) * d).toInt()
            }
        }
        dots.requestLayout()
    }

    /** Tapping Oliver plays his meow at a random pitch and shows the next photo. */
    private fun meow() {
        rotator.next()
        heroQuote.text = QUOTES.random()
        meow?.release()
        meow = MediaPlayer.create(this, R.raw.cat_alarm)?.apply {
            val mood = floatArrayOf(0.8f, 1f, 1.4f, 1.8f).random()
            runCatching { playbackParams = PlaybackParams().setSpeed(mood).setPitch(mood) }
            start()
        }
    }

    private fun snack(text: String) =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show()
}
