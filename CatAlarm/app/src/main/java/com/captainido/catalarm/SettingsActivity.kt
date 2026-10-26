package com.captainido.catalarm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Alarm behaviour settings, with the same choices and defaults as the Android Clock app. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.settingsList)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()

        section("התראות")
        choice(
            "משך נודניק", listOf(5, 10, 15, 20, 30), AlarmRepo.snoozeMinutes(this), Format::minutes
        ) { AlarmRepo.setSnoozeMinutes(this, it) }
        choice(
            "השתקה אוטומטית אחרי", listOf(1, 5, 10, 15, 20, 30), AlarmRepo.silenceAfterMinutes(this), Format::minutes
        ) { AlarmRepo.setSilenceAfterMinutes(this, it) }
        toggle("הגברה הדרגתית של העוצמה", "העוצמה עולה בהדרגה במשך 30 שניות", AlarmRepo.gradualVolume(this)) {
            AlarmRepo.setGradualVolume(this, it)
        }
        choice(
            "לחצני עוצמת השמע",
            listOf(AlarmRepo.VOLUME_KEYS_SNOOZE, AlarmRepo.VOLUME_KEYS_STOP, AlarmRepo.VOLUME_KEYS_VOLUME),
            AlarmRepo.volumeKeys(this),
            { when (it) { AlarmRepo.VOLUME_KEYS_SNOOZE -> "נודניק"; AlarmRepo.VOLUME_KEYS_STOP -> "עצירה"; else -> "שליטה בעוצמה" } },
        ) { AlarmRepo.setVolumeKeys(this, it) }
        row("תצוגה מקדימה של ההתראה", "השמעת הצלצול והצגת מסך ההתראה, ללא שיחה") {
            AlarmService.preview(this)
            startActivity(Intent(this, AlarmActivity::class.java))
        }

        section("שיחה ברקע")
        val number = AlarmRepo.callNumber(this)
        row("איש קשר", if (number.isBlank()) "לא נבחר · הקש לבחירה" else "${AlarmRepo.callName(this)} · ‎$number") {
            runCatching {
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
        }
        row(
            "הצגה מעל אפליקציות אחרות",
            if (Settings.canDrawOverlays(this)) "מאושר · מסך ההתראה נשאר מוצג בזמן השיחה"
            else "לא מאושר · בלי זה מסך השיחה יוצג במקום ההתראה",
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        section("אודות")
        row("אוליבר", "גרסה ${packageManager.getPackageInfo(packageName, 0).versionName}") {}
    }

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_Oliver_Overline)
            val s = resources.getDimensionPixelSize(R.dimen.space_4)
            setPadding(s, resources.getDimensionPixelSize(R.dimen.space_6), s, resources.getDimensionPixelSize(R.dimen.space_2))
        }
        list.addView(tv)
    }

    private fun row(title: String, value: String, onClick: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.value).text = value
        v.setOnClickListener { onClick() }
        list.addView(v)
        return v
    }

    private fun toggle(title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        lateinit var v: View
        v = row(title, value) {
            val sw = v.findViewById<MaterialSwitch>(R.id.toggle)
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
        v.findViewById<MaterialSwitch>(R.id.toggle).apply {
            visibility = View.VISIBLE
            isChecked = checked
        }
    }

    private fun choice(title: String, options: List<Int>, current: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
        row(title, label(current)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current)) { d, i ->
                    onPick(options[i])
                    d.dismiss()
                    render()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }
}
