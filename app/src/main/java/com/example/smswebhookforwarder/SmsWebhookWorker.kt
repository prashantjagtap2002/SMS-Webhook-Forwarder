package com.example.smswebhookforwarder

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class SmsWebhookWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val deliveryStatusStore = DeliveryStatusStore(appContext)

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER).orEmpty().trim()
        val message = inputData.getString(KEY_MESSAGE).orEmpty()
        val receivedAtMillis = inputData.getLong(KEY_RECEIVED_AT_MILLIS, System.currentTimeMillis())
        val webhookUrl = WebhookConfigStore(applicationContext).getWebhookUrl()
        val isManualTest = inputData.getBoolean(KEY_IS_MANUAL_TEST, false)
        val deliveryLabel = if (isManualTest) "manual test payload" else "SMS from $sender"

        if (sender.isBlank() || message.isBlank()) {
            Log.w(TAG, "Skipping SMS forward because sender or message is empty")
            deliveryStatusStore.saveStatus("Worker stopped because sender or message was empty.")
            return Result.failure()
        }

        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Skipping SMS forward because no webhook URL is configured")
            deliveryStatusStore.saveStatus("Worker stopped because no webhook URL is configured.")
            return Result.failure()
        }

        val payload = WebhookPayload(
            sender = sender,
            message = message,
            receivedAt = Instant.ofEpochMilli(receivedAtMillis).toString(),
            receivedAtMillis = receivedAtMillis,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        )

        val request = Request.Builder()
            .url(webhookUrl)
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        Log.d(TAG, "Forwarded SMS from $sender")
                        deliveryStatusStore.saveStatus(
                            "Webhook delivered successfully for $deliveryLabel with HTTP ${response.code}."
                        )
                        Result.success()
                    }
                    response.code == 408 || response.code == 429 || response.code in 500..599 -> {
                        Log.w(TAG, "Retrying SMS forward after HTTP ${response.code}")
                        deliveryStatusStore.saveStatus(
                            "Webhook got retryable HTTP ${response.code} for $deliveryLabel. WorkManager will retry."
                        )
                        Result.retry()
                    }
                    else -> {
                        Log.e(TAG, "Permanent webhook failure with HTTP ${response.code}")
                        deliveryStatusStore.saveStatus(
                            "Webhook failed permanently for $deliveryLabel with HTTP ${response.code} at $webhookUrl."
                        )
                        Result.failure(
                            workDataOf(KEY_ERROR to "Webhook returned HTTP ${response.code}")
                        )
                    }
                }
            }
        } catch (ioException: IOException) {
            Log.w(TAG, "Network failure while forwarding SMS", ioException)
            deliveryStatusStore.saveStatus(
                "Network error while delivering $deliveryLabel. WorkManager will retry."
            )
            Result.retry()
        } catch (exception: Exception) {
            Log.e(TAG, "Unexpected failure while forwarding SMS", exception)
            deliveryStatusStore.saveStatus(
                "Unexpected worker error for $deliveryLabel: ${exception.message ?: "unknown error"}"
            )
            Result.failure(
                workDataOf(KEY_ERROR to (exception.message ?: "Unexpected error"))
            )
        }
    }

    data class WebhookPayload(
        val sender: String,
        val message: String,
        val receivedAt: String,
        val receivedAtMillis: Long,
        val deviceModel: String
    )

    companion object {
        private const val TAG = "SmsWebhookWorker"
        private const val KEY_SENDER = "sender"
        private const val KEY_MESSAGE = "message"
        private const val KEY_RECEIVED_AT_MILLIS = "received_at_millis"
        private const val KEY_ERROR = "error"
        private const val KEY_IS_MANUAL_TEST = "is_manual_test"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        fun enqueue(
            context: Context,
            sender: String,
            message: String,
            receivedAtMillis: Long,
            isManualTest: Boolean = false
        ) {
            val request = OneTimeWorkRequestBuilder<SmsWebhookWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_MESSAGE to message,
                        KEY_RECEIVED_AT_MILLIS to receivedAtMillis,
                        KEY_IS_MANUAL_TEST to isManualTest
                    )
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
