package com.captainido.catalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Finds a contact's phone number by name in the phone's address book. */
object ContactFinder {
    data class Match(val name: String, val number: String, val mobile: Boolean)

    fun canRead(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** All numbers whose contact name contains [name], mobiles first, one entry per number. */
    fun find(ctx: Context, name: String): List<Match> {
        if (!canRead(ctx)) return emptyList()
        val matches = mutableListOf<Match>()
        runCatching {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1) ?: continue
                    matches += Match(c.getString(0) ?: name, number, c.getInt(2) == Phone.TYPE_MOBILE)
                }
            }
        }
        return matches
            .distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
            .sortedByDescending { it.mobile }
    }

    /** Fills in the stored contact from the address book if it is still missing. */
    fun resolveIfMissing(ctx: Context): Boolean {
        if (AlarmRepo.callNumber(ctx).isNotBlank()) return true
        val match = find(ctx, Names.DEFAULT_CALLEE).firstOrNull() ?: return false
        AlarmRepo.setCallContact(ctx, match.name, match.number)
        return true
    }
}
