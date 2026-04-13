package com.example.smswebhookforwarder

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
    private val outboxStore = SmsOutboxStore(appContext)

    override suspend fun doWork(): Result {
        val webhookUrl = WebhookConfigStore(applicationContext).getWebhookUrl()
        migrateLegacyInputIntoOutbox()

        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Skipping SMS forward because no webhook URL is configured")
            deliveryStatusStore.saveStatus("Worker is waiting because no webhook URL is configured. Pending outbox size: ${outboxStore.count()}.")
            return Result.retry()
        }

        while (true) {
            val pending = outboxStore.peek() ?: return Result.success()
            val startedAttempt = outboxStore.markAttemptStarted(pending.id) ?: pending
            val deliveryLabel = if (startedAttempt.isManualTest) {
                "manual test payload"
            } else {
                "SMS from ${startedAttempt.sender}"
            }
            val payload = WebhookPayload(
                sender = startedAttempt.sender,
                message = startedAttempt.message,
                receivedAt = Instant.ofEpochMilli(startedAttempt.receivedAtMillis).toString(),
                receivedAtMillis = startedAttempt.receivedAtMillis,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )

            val request = Request.Builder()
                .url(webhookUrl)
                .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            Log.d(TAG, "Forwarded ${startedAttempt.sender}")
                            outboxStore.remove(startedAttempt.id)
                            deliveryStatusStore.saveStatus(
                                "Webhook delivered successfully for $deliveryLabel with HTTP ${response.code} on attempt ${startedAttempt.attemptCount}. Pending outbox size: ${outboxStore.count()}."
                            )
                        }
                        response.code == 408 || response.code == 425 || response.code == 429 || response.code in 500..599 -> {
                            Log.w(TAG, "Retrying webhook delivery after HTTP ${response.code}")
                            outboxStore.markAttemptFailed(startedAttempt.id, "HTTP ${response.code}")
                            deliveryStatusStore.saveStatus(
                                "Webhook got retryable HTTP ${response.code} for $deliveryLabel on attempt ${startedAttempt.attemptCount}. Message remains in outbox. Pending outbox size: ${outboxStore.count()}."
                            )
                            return Result.retry()
                        }
                        else -> {
                            Log.e(TAG, "Webhook returned blocking HTTP ${response.code}")
                            outboxStore.markAttemptFailed(startedAttempt.id, "HTTP ${response.code}")
                            deliveryStatusStore.saveStatus(
                                "Webhook returned HTTP ${response.code} for $deliveryLabel on attempt ${startedAttempt.attemptCount}. Message stays in outbox until the endpoint is fixed. Pending outbox size: ${outboxStore.count()}."
                            )
                            return Result.retry()
                        }
                    }
                }
            } catch (ioException: IOException) {
                Log.w(TAG, "Network failure while forwarding webhook", ioException)
                outboxStore.markAttemptFailed(
                    startedAttempt.id,
                    ioException.message ?: ioException::class.java.simpleName
                )
                deliveryStatusStore.saveStatus(
                    "Network error while delivering $deliveryLabel on attempt ${startedAttempt.attemptCount}. Message remains in outbox and WorkManager will retry. Pending outbox size: ${outboxStore.count()}."
                )
                return Result.retry()
            } catch (exception: Exception) {
                Log.e(TAG, "Unexpected failure while forwarding webhook", exception)
                outboxStore.markAttemptFailed(
                    startedAttempt.id,
                    exception.message ?: exception::class.java.simpleName
                )
                deliveryStatusStore.saveStatus(
                    "Unexpected worker error for $deliveryLabel on attempt ${startedAttempt.attemptCount}: ${exception.message ?: "unknown error"}. Message remains in outbox. Pending outbox size: ${outboxStore.count()}."
                )
                return Result.retry()
            }
        }
    }

    private fun migrateLegacyInputIntoOutbox() {
        val sender = inputData.getString(KEY_SENDER).orEmpty().trim()
        val message = inputData.getString(KEY_MESSAGE).orEmpty()
        val receivedAtMillis = inputData.getLong(KEY_RECEIVED_AT_MILLIS, -1L)
        val isManualTest = inputData.getBoolean(KEY_IS_MANUAL_TEST, false)

        if (sender.isBlank() || message.isBlank() || receivedAtMillis <= 0L) {
            return
        }

        outboxStore.enqueue(
            sender = sender,
            message = message,
            receivedAtMillis = receivedAtMillis,
            isManualTest = isManualTest
        )
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
        private const val UNIQUE_WORK_NAME = "sms-webhook-drain"
        private const val KEY_SENDER = "sender"
        private const val KEY_MESSAGE = "message"
        private const val KEY_RECEIVED_AT_MILLIS = "received_at_millis"
        private const val KEY_IS_MANUAL_TEST = "is_manual_test"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()
        private val httpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        fun enqueue(
            context: Context,
            sender: String,
            message: String,
            receivedAtMillis: Long,
            isManualTest: Boolean = false
        ) {
            SmsOutboxStore(context.applicationContext).enqueue(
                sender = sender,
                message = message,
                receivedAtMillis = receivedAtMillis,
                isManualTest = isManualTest
            )
            enqueuePendingDrain(context)
        }

        fun enqueuePendingDrain(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsWebhookWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
