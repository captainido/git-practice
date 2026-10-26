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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        private val HEBREW: Locale = Locale.forLanguageTag("he")
        private val DELAYS = mapOf(R.id.delay0 to 0, R.id.delay1 to 1, R.id.delay3 to 3, R.id.delay5 to 5)
        private val QUOTES = listOf(
            "אל תיגע לי בבטן. ברצינות.",
            "אני לא שמן, אני פלאפי.",
            "תזכורת: האוכל שלי מאחר.",
            "קבעת שעה? חמוד. אני אחליט.",
            "פרררר... עכשיו תביא חטיף.",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var heroQuote: TextView
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var countdownText: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var repeatSwitch: MaterialSwitch
    private lateinit var callSwitch: MaterialSwitch
    private lateinit var callTitle: TextView
    private lateinit var callSubtitle: TextView
    private lateinit var callSettings: LinearLayout
    private lateinit var phoneInput: TextInputEditText
    private lateinit var delayGroup: ChipGroup
    private lateinit var warningCard: MaterialCardView
    private lateinit var warningText: TextView
    private lateinit var rotator: PhotoRotator
    private var meow: MediaPlayer? = null
    private var binding = false

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                AlarmStore.setCallNumber(this, c.getString(0) ?: "")
                AlarmStore.setCallName(this, c.getString(1)?.takeIf { it.isNotBlank() } ?: Names.DEFAULT_CALLEE)
                refresh()
            }
        }
    }

    private val requestCallPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            AlarmStore.setCallEnabled(this, false)
            snack("בלי הרשאת שיחות ${Names.CAT} לא יכול להתקשר 📵")
        }
        refresh()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        heroQuote = findViewById(R.id.heroQuote)
        timeText = findViewById(R.id.timeText)
        dateText = findViewById(R.id.dateText)
        countdownText = findViewById(R.id.countdownText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        repeatSwitch = findViewById(R.id.repeatSwitch)
        callSwitch = findViewById(R.id.callSwitch)
        callTitle = findViewById(R.id.callTitle)
        callSubtitle = findViewById(R.id.callSubtitle)
        callSettings = findViewById(R.id.callSettings)
        phoneInput = findViewById(R.id.phoneInput)
        delayGroup = findViewById(R.id.delayGroup)
        warningCard = findViewById(R.id.warningCard)
        warningText = findViewById(R.id.warningText)

        rotator = PhotoRotator(findViewById<ImageView>(R.id.heroPhotoA), findViewById(R.id.heroPhotoB), Names.PHOTOS)
        findViewById<View>(R.id.heroCard).setOnClickListener { meow() }

        timeText.setOnClickListener { pickTime() }
        findViewById<MaterialButton>(R.id.pickTimeButton).setOnClickListener { pickTime() }
        dateText.setOnClickListener { pickDate() }
        findViewById<MaterialButton>(R.id.pickDateButton).setOnClickListener { pickDate() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AlarmStore.setEnabled(this, checked)
            AlarmScheduler.schedule(this)
            if (checked && !AlarmStore.isEnabled(this)) snack("הזמן שנבחר כבר עבר – בחר זמן עתידי ⏳")
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
            if (checked && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                requestCallPermission.launch(Manifest.permission.CALL_PHONE)
            }
            refresh()
        }
        phoneInput.doAfterTextChanged {
            if (!binding) {
                AlarmStore.setCallNumber(this, it?.toString()?.trim() ?: "")
                refreshPermissions()
            }
        }
        findViewById<MaterialButton>(R.id.pickContactButton).setOnClickListener {
            runCatching {
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }.onFailure { snack("לא נמצאה אפליקציית אנשי קשר") }
        }
        delayGroup.setOnCheckedStateChangeListener { _, ids ->
            if (binding) return@setOnCheckedStateChangeListener
            ids.firstOrNull()?.let { DELAYS[it] }?.let { AlarmStore.setCallDelayMinutes(this, it) }
        }

        findViewById<MaterialButton>(R.id.testButton).setOnClickListener {
            AlarmService.start(this, test = true)
            startActivity(Intent(this, AlarmActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
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

    // ---- Date & time ----

    private fun alarmCalendar() = Calendar.getInstance().apply { timeInMillis = AlarmStore.alarmAt(this@MainActivity) }

    private fun pickDate() {
        val current = alarmCalendar()
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
            applyAlarm(current.timeInMillis, rollForward = false)
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun pickTime() {
        val current = alarmCalendar()
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
            .setHour(current.get(Calendar.HOUR_OF_DAY))
            .setMinute(current.get(Calendar.MINUTE))
            .setTitleText("באיזו שעה?")
            .build()
        picker.addOnPositiveButtonClickListener {
            current.set(Calendar.HOUR_OF_DAY, picker.hour)
            current.set(Calendar.MINUTE, picker.minute)
            current.set(Calendar.SECOND, 0)
            current.set(Calendar.MILLISECOND, 0)
            applyAlarm(current.timeInMillis, rollForward = true)
        }
        picker.show(supportFragmentManager, "time")
    }

    /**
     * Saves and arms the alarm. When only the time was changed and the stored date is already
     * in the past, the alarm moves to the next time that hour comes round.
     */
    private fun applyAlarm(time: Long, rollForward: Boolean) {
        var at = time
        val now = System.currentTimeMillis()
        if (at <= now && !AlarmStore.isRepeating(this)) {
            if (!rollForward) {
                snack("השעה הזו כבר עברה היום – בחר שעה מאוחרת יותר ⏳")
                return
            }
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            val todayCal = Calendar.getInstance()
            cal.set(todayCal.get(Calendar.YEAR), todayCal.get(Calendar.MONTH), todayCal.get(Calendar.DAY_OF_MONTH))
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            at = cal.timeInMillis
        }
        AlarmStore.setAlarmAt(this, at)
        AlarmStore.setEnabled(this, true)
        AlarmScheduler.schedule(this)
        refresh()
        AlarmScheduler.nextTrigger(this)?.let {
            snack("סגור! ${Names.CAT} יעיר את ${Names.HUMAN} ${countdown(it - System.currentTimeMillis())} 😼")
        }
    }

    // ---- Rendering ----

    private fun refresh() {
        binding = true
        val next = AlarmScheduler.nextTrigger(this) ?: AlarmStore.alarmAt(this)
        timeText.text = SimpleDateFormat("HH:mm", Locale.US).format(next)
        dateText.text = describeDate(next)
        enabledSwitch.isChecked = AlarmStore.isEnabled(this)
        repeatSwitch.isChecked = AlarmStore.isRepeating(this)

        val callee = AlarmStore.callName(this)
        val callOn = AlarmStore.isCallEnabled(this)
        callTitle.text = "📞 ${Names.CAT} מתקשר ל$callee"
        callSubtitle.text = "כשהמעורר מצלצל, ${Names.CAT} מחייג ל$callee ברמקול כדי שיעזרו להעיר את ${Names.HUMAN}."
        callSwitch.isChecked = callOn
        callSettings.visibility = if (callOn) View.VISIBLE else View.GONE
        val number = AlarmStore.callNumber(this)
        if (phoneInput.text?.toString() != number) phoneInput.setText(number)
        val chip = DELAYS.entries.firstOrNull { it.value == AlarmStore.callDelayMinutes(this) }?.key ?: R.id.delay0
        delayGroup.check(chip)
        binding = false

        refreshCountdown()
        refreshPermissions()
    }

    private fun refreshCountdown() {
        val next = AlarmScheduler.nextTrigger(this)
        countdownText.text = if (!AlarmStore.isEnabled(this) || next == null) {
            "😴 המעורר כבוי – ${Names.CAT} ישן"
        } else {
            "⏰ ${countdown(next - System.currentTimeMillis())}"
        }
    }

    private fun describeDate(time: Long): String {
        val day = SimpleDateFormat("EEEE, d בMMMM yyyy", HEBREW).format(time)
        val target = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance()
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val repeat = if (AlarmStore.isRepeating(this)) " · כל יום" else ""
        return when {
            sameDay(target, today) -> "היום · $day$repeat"
            sameDay(target, tomorrow) -> "מחר · $day$repeat"
            else -> "$day$repeat"
        }
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
    private fun refreshPermissions() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pkg = Uri.parse("package:$packageName")
        val callOn = AlarmStore.isCallEnabled(this)
        val problem: Pair<String, (() -> Unit)>? = when {
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
            callOn && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ->
                "⚠️ ${Names.CAT} צריך הרשאה לבצע שיחות. לחץ כאן." to {
                    requestCallPermission.launch(Manifest.permission.CALL_PHONE)
                }
            callOn && AlarmStore.callNumber(this).isBlank() ->
                "⚠️ חסר מספר טלפון ל${AlarmStore.callName(this)}." to { phoneInput.requestFocus(); Unit }
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

    /** Tapping Oliver plays his meow at a random pitch and shows the next photo. */
    private fun meow() {
        rotator.next()
        heroQuote.text = QUOTES.random()
        meow?.release()
        meow = MediaPlayer.create(this, R.raw.cat_alarm)?.apply {
            val mood = floatArrayOf(0.7f, 1f, 1.5f, 2f).random()
            runCatching { playbackParams = PlaybackParams().setSpeed(mood).setPitch(mood) }
            start()
        }
    }

    private fun snack(text: String) =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show()
}
