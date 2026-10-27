package com.yungerman.catcam

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Sends a detection photo to WhatsApp; WorkManager retries it if the network is down. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val file = inputData.getString(KEY_PATH)?.let(::File)
        val caption = inputData.getString(KEY_CAPTION).orEmpty()
        val timeText = inputData.getString(KEY_TIME).orEmpty()
        if (file == null || !file.exists()) return@withContext Result.failure()

        CatCamState.sendStatus.value = "שולח ללילך... ($timeText)"
        when (val r = GreenApi.sendImage(Prefs(applicationContext), file, caption)) {
            GreenApi.Result.Ok -> {
                CatCamState.sendStatus.value = "✓ נשלח ללילך בוואטסאפ ($timeText)"
                Result.success()
            }
            is GreenApi.Result.Retry -> if (runAttemptCount < MAX_ATTEMPTS) {
                CatCamState.sendStatus.value = "השליחה נכשלה, אנסה שוב: ${r.message}"
                Result.retry()
            } else {
                CatCamState.sendStatus.value = "✗ השליחה נכשלה סופית: ${r.message}"
                Result.failure()
            }
            is GreenApi.Result.Fail -> {
                CatCamState.sendStatus.value = "✗ השליחה נכשלה: ${r.message}"
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_CAPTION = "caption"
        private const val KEY_TIME = "time"
        private const val MAX_ATTEMPTS = 8

        fun enqueue(context: Context, file: File, caption: String, timeText: String) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(KEY_PATH to file.absolutePath, KEY_CAPTION to caption, KEY_TIME to timeText)
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
