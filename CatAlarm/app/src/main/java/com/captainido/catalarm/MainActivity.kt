package com.captainido.catalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.TimeZone

/** The alarm list, modelled on the Android Clock app. */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var alarmList: LinearLayout
    private lateinit var rotator: PhotoRotator
    private var expandedId = -1
    private var chooserShowing = false
    private var openSettingsIfDenied = false

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst() && !c.getString(1).isNullOrBlank()) {
                AlarmRepo.setCallContact(this, c.getString(0) ?: Names.DEFAULT_CALLEE, c.getString(1))
                render()
            }
        }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (openSettingsIfDenied && result.values.any { !it }) openAppSettings()
        openSettingsIfDenied = false
        autoFindCallee()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        alarmList = findViewById(R.id.alarmList)

        rotator = PhotoRotator(
            PhotoRotator.Slide(findViewById(R.id.slideA), findViewById(R.id.slideABg), findViewById(R.id.slideAPhoto)),
            PhotoRotator.Slide(findViewById(R.id.slideB), findViewById(R.id.slideBBg), findViewById(R.id.slideBPhoto)),
            intervalMs = 6000,
        )
        findViewById<View>(R.id.addButton).setOnClickListener { addAlarm() }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        Notifier.ensureChannels(this)
        askMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        autoFindCallee()
        AlarmScheduler.scheduleAll(this)
        render()
        rotator.start()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        rotator.stop()
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderHero()
            handler.postDelayed(this, 30_000)
        }
    }

    // ---- Rendering ----

    private fun render() {
        findViewById<TextView>(R.id.greetingText).text = greeting()
        renderHero()
        renderWarning()
        alarmList.removeAllViews()
        val alarms = AlarmRepo.all(this)
        findViewById<View>(R.id.emptyText).visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        alarms.forEach { alarmList.addView(card(it)) }
    }

    private fun greeting(): String {
        val hello = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "בוקר טוב"
            in 12..16 -> "צהריים טובים"
            in 17..21 -> "ערב טוב"
            else -> "לילה טוב"
        }
        return "$hello, ${Names.HUMAN}"
    }

    private fun renderHero() {
        val next = AlarmScheduler.nextOverall(this)
        val label = findViewById<TextView>(R.id.nextLabel)
        val time = findViewById<TextView>(R.id.nextTime)
        val detail = findViewById<TextView>(R.id.nextDetail)
        if (next == null) {
            label.text = "אין התראה פעילה"
            time.text = "—"
            detail.text = "הקש על + כדי להגדיר התראה"
            return
        }
        val (alarm, at) = next
        label.text = if (alarm.snoozedUntil > System.currentTimeMillis()) "נודניק" else "ההתראה הבאה"
        time.text = Format.time(at)
        detail.text = "${Format.relativeDay(at)} · בעוד ${Format.duration(at - System.currentTimeMillis())}"
    }

    private fun card(alarm: Alarm): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_alarm, alarmList, false)
        val expanded = alarm.id == expandedId
        val now = System.currentTimeMillis()
        val active = alarm.enabled || alarm.snoozedUntil > now

        v.findViewById<TextView>(R.id.timeText).apply {
            text = Format.time(alarm.hour, alarm.minute)
            setTextColor(getColor(if (active) R.color.text_primary else R.color.text_tertiary))
            setOnClickListener { pickTime(alarm) }
        }
        v.findViewById<TextView>(R.id.summaryText).text = Format.summary(alarm, now)
        v.findViewById<TextView>(R.id.labelText).apply {
            text = alarm.label
            visibility = if (alarm.label.isBlank()) View.GONE else View.VISIBLE
        }
        v.findViewById<MaterialSwitch>(R.id.enabledSwitch).apply {
            isChecked = active
            setOnCheckedChangeListener { _, checked -> setEnabled(alarm, checked) }
        }
        v.findViewById<ImageView>(R.id.expandIcon).rotation = if (expanded) 180f else 0f
        v.findViewById<View>(R.id.header).setOnClickListener {
            expandedId = if (expanded) -1 else alarm.id
            render()
        }

        val details = v.findViewById<View>(R.id.details)
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        if (!expanded) return v

        // Repeat days
        val dayRow = v.findViewById<LinearLayout>(R.id.dayRow)
        val size = resources.getDimensionPixelSize(R.dimen.day_chip)
        for (i in 0..6) {
            val selected = alarm.days and (1 shl i) != 0
            val chip = TextView(this).apply {
                text = Format.dayLetter(i)
                gravity = Gravity.CENTER
                setTextAppearance(R.style.TextAppearance_Oliver_Label)
                setTextColor(getColor(if (selected) R.color.on_gold else R.color.text_secondary))
                setBackgroundResource(R.drawable.day_chip)
                isSelected = selected
                contentDescription = Format.dayName(i)
                setOnClickListener {
                    alarm.days = alarm.days xor (1 shl i)
                    if (alarm.repeats) alarm.date = 0L
                    alarm.enabled = true
                    alarm.skipUntil = 0L
                    commit(alarm, announce = true)
                }
            }
            dayRow.addView(FrameLayout(this).apply {
                addView(chip, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, size, 1f))
        }

        // Specific date (one-time alarms only)
        v.findViewById<View>(R.id.dateRow).apply {
            visibility = if (alarm.repeats) View.GONE else View.VISIBLE
            setOnClickListener { pickDate(alarm) }
        }
        v.findViewById<TextView>(R.id.dateText).text =
            if (alarm.date != 0L) "תאריך: ${Format.relativeDay(alarm.date)}" else "קביעת תאריך"

        v.findViewById<TextView>(R.id.labelEdit).apply {
            text = alarm.label.ifBlank { "הוספת תווית" }
            setTextColor(getColor(if (alarm.label.isBlank()) R.color.text_secondary else R.color.text_primary))
        }
        v.findViewById<View>(R.id.labelRow).setOnClickListener { editLabel(alarm) }

        val vibrate = v.findViewById<MaterialSwitch>(R.id.vibrateSwitch)
        vibrate.isChecked = alarm.vibrate
        vibrate.setOnCheckedChangeListener { _, c -> alarm.vibrate = c; commit(alarm) }
        v.findViewById<View>(R.id.vibrateRow).setOnClickListener { vibrate.toggle() }

        val number = AlarmRepo.callNumber(this)
        v.findViewById<TextView>(R.id.callTitle).text = "שיחה ברקע ל${AlarmRepo.callName(this)}"
        v.findViewById<TextView>(R.id.callDetail).text =
            if (number.isBlank()) "לא נבחר איש קשר · הקש לבחירה" else "‎$number · ברמקול, עם הצלצול"
        val call = v.findViewById<MaterialSwitch>(R.id.callSwitch)
        call.isChecked = alarm.call
        call.setOnCheckedChangeListener { _, c ->
            alarm.call = c
            commit(alarm)
            if (c) askMissingPermissions()
        }
        v.findViewById<View>(R.id.callRow).setOnClickListener {
            if (AlarmRepo.callNumber(this).isBlank()) openContactPicker() else call.toggle()
        }

        v.findViewById<View>(R.id.deleteRow).setOnClickListener { delete(alarm) }
        return v
    }

    // ---- Actions ----

    private fun commit(alarm: Alarm, announce: Boolean = false) {
        AlarmRepo.save(this, alarm)
        AlarmScheduler.scheduleAll(this)
        render()
        if (announce) announce(alarm)
    }

    /** "ההתראה נקבעה לעוד 7 שעות ו-12 דקות", as the system clock says it. */
    private fun announce(alarm: Alarm) {
        val at = AlarmScheduler.nextTrigger(alarm) ?: return
        snack("ההתראה נקבעה לעוד ${Format.duration(at - System.currentTimeMillis())}")
    }

    private fun setEnabled(alarm: Alarm, enabled: Boolean) {
        alarm.enabled = enabled
        alarm.snoozedUntil = 0L
        alarm.skipUntil = 0L
        if (enabled && alarm.date != 0L &&
            AlarmScheduler.atDate(alarm.date, alarm.hour, alarm.minute) <= System.currentTimeMillis()
        ) alarm.date = 0L
        Notifier.clear(this, alarm.id)
        commit(alarm, announce = enabled)
    }

    private fun addAlarm() {
        val now = Calendar.getInstance()
        showTimePicker(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)) { h, m ->
            val alarm = AlarmRepo.newAlarm(this, h, m)
            expandedId = alarm.id
            commit(alarm, announce = true)
        }
    }

    /** A picked time goes to its nearest upcoming occurrence unless the alarm has its own date. */
    private fun pickTime(alarm: Alarm) {
        showTimePicker(alarm.hour, alarm.minute) { h, m ->
            alarm.hour = h
            alarm.minute = m
            alarm.enabled = true
            alarm.snoozedUntil = 0L
            alarm.skipUntil = 0L
            if (alarm.date != 0L && AlarmScheduler.atDate(alarm.date, h, m) <= System.currentTimeMillis()) {
                alarm.date = 0L
            }
            commit(alarm, announce = true)
        }
    }

    private fun showTimePicker(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("בחירת שעה")
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour, picker.minute) }
        picker.show(supportFragmentManager, "time")
    }

    private fun pickDate(alarm: Alarm) {
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val current = AlarmScheduler.nextTrigger(alarm) ?: System.currentTimeMillis()
        val local = Calendar.getInstance().apply { timeInMillis = current }
        val selection = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("בחירת תאריך")
            .setSelection(maxOf(selection, today))
            .setCalendarConstraints(
                CalendarConstraints.Builder().setStart(today).setValidator(DateValidatorPointForward.from(today)).build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
            val date = Calendar.getInstance().apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
            if (AlarmScheduler.atDate(date, alarm.hour, alarm.minute) <= System.currentTimeMillis()) {
                snack("השעה ${Format.time(alarm.hour, alarm.minute)} כבר עברה היום – שנה את השעה או בחר תאריך אחר")
                return@addOnPositiveButtonClickListener
            }
            alarm.date = date
            alarm.days = 0
            alarm.enabled = true
            alarm.skipUntil = 0L
            commit(alarm, announce = true)
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun editLabel(alarm: Alarm) {
        val input = TextInputEditText(this).apply {
            setText(alarm.label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text?.length ?: 0)
        }
        val box = TextInputLayout(this).apply {
            hint = "תווית"
            val pad = resources.getDimensionPixelSize(R.dimen.space_6)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("תווית")
            .setView(box)
            .setPositiveButton("שמירה") { _, _ ->
                alarm.label = input.text?.toString()?.trim().orEmpty()
                commit(alarm)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun delete(alarm: Alarm) {
        AlarmScheduler.cancel(this, alarm.id)
        AlarmRepo.delete(this, alarm.id)
        expandedId = -1
        AlarmScheduler.scheduleAll(this)
        render()
        Snackbar.make(findViewById(R.id.coordinator), "ההתראה נמחקה", Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.addButton)
            .setAction("ביטול") {
                AlarmRepo.save(this, alarm)
                AlarmScheduler.scheduleAll(this)
                render()
            }
            .show()
    }

    // ---- Permissions & contact ----

    private fun callWanted() = AlarmRepo.all(this).any { it.call && it.enabled }

    private fun askMissingPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (callWanted() || AlarmRepo.all(this@MainActivity).isEmpty()) {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_CONTACTS)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray()) else openSettingsIfDenied = false
    }

    /** Finds Lilach in the address book; asks which one when several contacts match. */
    private fun autoFindCallee() {
        if (chooserShowing || AlarmRepo.callNumber(this).isNotBlank()) return
        val matches = ContactFinder.find(this, Names.DEFAULT_CALLEE)
        when {
            matches.isEmpty() -> return
            matches.size == 1 -> AlarmRepo.setCallContact(this, matches[0].name, matches[0].number)
            else -> {
                chooserShowing = true
                MaterialAlertDialogBuilder(this)
                    .setTitle("למי להתקשר עם ההתראה?")
                    .setItems(matches.map { "${it.name}   ‎${it.number}" }.toTypedArray()) { _, i ->
                        AlarmRepo.setCallContact(this, matches[i].name, matches[i].number)
                        render()
                    }
                    .setOnDismissListener { chooserShowing = false }
                    .show()
            }
        }
    }

    private fun openContactPicker() {
        runCatching {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }.onFailure { snack("לא נמצאה אפליקציית אנשי קשר") }
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    /** The first thing that would stop an alarm (or its call) from working; tap to fix it. */
    private fun renderWarning() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pkg = Uri.parse("package:$packageName")
        val call = callWanted()
        val problem: Pair<String, () -> Unit>? = when {
            !nm.areNotificationsEnabled() -> "ההתראות כבויות. הקש כדי לאפשר." to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                "כדי שההתראה תוצג על מסך הנעילה, אשר תצוגה במסך מלא." to {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                }
            Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() ->
                "כדי שההתראה תצלצל בדיוק בזמן, אשר \"התראות ותזכורות\"." to {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                }
            call && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ->
                "כדי להתקשר ל${AlarmRepo.callName(this)} עם ההתראה, אשר הרשאת שיחות." to {
                    openSettingsIfDenied = true
                    askMissingPermissions()
                }
            call && AlarmRepo.callNumber(this).isBlank() ->
                "לא נמצא איש קשר לשיחה. הקש כדי לבחור." to { openContactPicker() }
            call && !Settings.canDrawOverlays(this) ->
                "כדי שהשיחה תתבצע ברקע ומסך ההתראה יישאר מוצג, אשר \"הצגה מעל אפליקציות אחרות\"." to {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
                }
            else -> null
        }
        val card = findViewById<MaterialCardView>(R.id.warningCard)
        if (problem == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.warningText).text = problem.first
        card.setOnClickListener { runCatching { problem.second() }.onFailure { openAppSettings() } }
    }

    private fun snack(text: String) =
        Snackbar.make(findViewById(R.id.coordinator), text, Snackbar.LENGTH_LONG).setAnchorView(R.id.addButton).show()
}
