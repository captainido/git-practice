package com.yungerman.catcam

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal Green-API client (WhatsApp sending through the user's linked account). */
object GreenApi {

    sealed class Result {
        data object Ok : Result()
        /** Temporary problem (network, server) - worth retrying. */
        data class Retry(val message: String) : Result()
        /** Permanent problem (bad credentials, quota) - retrying won't help. */
        data class Fail(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Israeli or international number -> "9725XXXXXXXX@c.us", or null if it doesn't look valid. */
    fun chatId(phone: String): String? {
        var digits = phone.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.startsWith("0")) digits = "972" + digits.drop(1)
        return if (digits.length in 11..15) "$digits@c.us" else null
    }

    fun sendText(prefs: Prefs, text: String): Result {
        val chatId = chatId(prefs.phone) ?: return Result.Fail("מספר טלפון לא תקין")
        val json = JSONObject().put("chatId", chatId).put("message", text).toString()
        val request = Request.Builder()
            .url("${prefs.apiUrl}/waInstance${prefs.idInstance}/sendMessage/${prefs.apiToken}")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(request)
    }

    fun sendImage(prefs: Prefs, file: File, caption: String): Result {
        val chatId = chatId(prefs.phone) ?: return Result.Fail("מספר טלפון לא תקין")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chatId", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("fileName", file.name)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${prefs.mediaUrl}/waInstance${prefs.idInstance}/sendFileByUpload/${prefs.apiToken}")
            .post(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): Result = try {
        client.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> Result.Ok
                code == 401 || code == 403 -> Result.Fail("פרטי Green-API שגויים (HTTP $code)")
                code == 466 -> Result.Fail("נגמרה המכסה החודשית בתוכנית החינמית של Green-API")
                code == 429 || code >= 500 -> Result.Retry("שגיאת שרת זמנית (HTTP $code)")
                else -> Result.Fail("HTTP $code: ${response.body?.string().orEmpty().take(200)}")
            }
        }
    } catch (e: IOException) {
        Result.Retry("אין חיבור לאינטרנט (${e.message})")
    } catch (e: IllegalArgumentException) {
        Result.Fail("כתובת שרת לא תקינה")
    }
}
