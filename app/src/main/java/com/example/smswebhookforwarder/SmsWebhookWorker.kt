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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SmsWebhookWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val deliveryStatusStore = DeliveryStatusStore(appContext)
    private val outboxStore = SmsOutboxStore(appContext)
    private val profileStore = WebhookProfileStore(appContext)
    private val payloadConfig = PayloadConfigStore(appContext)

    override suspend fun doWork(): Result {
        val activeProfiles = profileStore.getEnabledProfiles().ifEmpty {
            val legacyUrl = WebhookConfigStore(applicationContext).getWebhookUrl()
            if (legacyUrl.isBlank()) {
                deliveryStatusStore.saveStatus(
                    "Worker waiting: no webhook profiles configured. Pending outbox: ${outboxStore.count()}."
                )
                return Result.failure()
            }
            listOf(WebhookProfile(id = "legacy", name = "Default", url = legacyUrl))
        }

        // Fix #1: snapshot ALL pending items upfront and process every one of them in this
        // single worker run. Previously the loop returned Result.retry() on the first failed
        // item, blocking every message behind it in the queue for up to hours.
        val allPending = outboxStore.peekAll()
        if (allPending.isEmpty()) return Result.success()

        var anyItemNeedsRetry = false

        for (pending in allPending) {
            val item = outboxStore.markAttemptStarted(pending.id) ?: pending
            val label = if (item.isManualTest) "manual test payload" else "SMS from ${item.sender}"

            val profilesToSend = activeProfiles.filter { it.id !in item.deliveredProfileIds }
            if (profilesToSend.isEmpty()) {
                // Fix #4: all profiles were already marked delivered before the item was
                // removed (crash-recovery path). Count the success that was missed.
                outboxStore.remove(item.id)
                deliveryStatusStore.recordSuccess()
                continue
            }

            val jsonBody = gson.toJson(
                WebhookPayload(
                    deliveryId = if (payloadConfig.includeDeliveryId()) item.id else null,
                    sender = item.sender,
                    message = item.message,
                    receivedAt = if (payloadConfig.includeReceivedAt()) Instant.ofEpochMilli(item.receivedAtMillis).toString() else null,
                    receivedAtMillis = if (payloadConfig.includeReceivedAtMillis()) item.receivedAtMillis else null,
                    deviceModel = if (payloadConfig.includeDeviceModel()) "${Build.MANUFACTURER} ${Build.MODEL}".trim() else null,
                    attempt = if (payloadConfig.includeAttempt()) item.attemptCount else null
                )
            )

            var anyRetryNeeded = false
            var deliveredCount = 0

            for (profile in profilesToSend) {
                when (sendToProfile(profile, item, jsonBody, label)) {
                    SendResult.DELIVERED -> {
                        outboxStore.markDeliveredToProfile(item.id, profile.id)
                        deliveredCount++
                    }
                    SendResult.RETRY -> anyRetryNeeded = true
                    SendResult.PERMANENT_FAILURE -> {
                        // Permanent rejection (wrong URL, auth error, etc.) — count error now.
                        // Mark as "done" for this profile to avoid retrying it forever.
                        deliveryStatusStore.recordError()
                        outboxStore.markDeliveredToProfile(item.id, profile.id)
                    }
                }
            }

            if (anyRetryNeeded) {
                if (item.attemptCount >= MAX_PERMANENT_FAILURE_ATTEMPTS) {
                    outboxStore.remove(item.id)
                    deliveryStatusStore.recordError()
                    deliveryStatusStore.saveStatus(
                        "Gave up delivering $label after ${item.attemptCount} attempts. Pending: ${outboxStore.count()}."
                    )
                    // Don't set anyItemNeedsRetry — this item is gone, move on to next.
                } else {
                    if (item.attemptCount >= FAILURE_NOTIFICATION_THRESHOLD) {
                        DeliveryNotificationHelper.notifyDeliveryFailed(applicationContext, outboxStore.count())
                    }
                    anyItemNeedsRetry = true
                }
            } else {
                outboxStore.remove(item.id)
                if (deliveredCount > 0) {
                    deliveryStatusStore.recordSuccess()
                    deliveryStatusStore.saveStatus(
                        "Delivered $label to $deliveredCount profile(s) on attempt ${item.attemptCount}. Pending: ${outboxStore.count()}."
                    )
                } else {
                    deliveryStatusStore.saveStatus(
                        "All profiles permanently rejected $label. Pending: ${outboxStore.count()}."
                    )
                }
            }
        }

        return if (anyItemNeedsRetry) Result.retry() else Result.success()
    }

    private suspend fun sendToProfile(
        profile: WebhookProfile,
        item: SmsOutboxStore.PendingWebhookDelivery,
        jsonBody: String,
        label: String
    ): SendResult {
        val requestBuilder = Request.Builder()
            .url(profile.url)
            .header("X-Webhook-Delivery-Id", item.id)
            .header("X-Webhook-Attempt", item.attemptCount.toString())
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))

        if (profile.authHeader.isNotBlank()) {
            requestBuilder.header("Authorization", profile.authHeader.trim())
        }
        if (profile.hmacSecret.isNotBlank()) {
            val sig = computeHmacSha256(profile.hmacSecret, jsonBody)
            requestBuilder.header("X-Webhook-Signature", "sha256=$sig")
        }

        val request = requestBuilder.build()
        var localRetryCount = 0

        while (localRetryCount <= MAX_LOCAL_RETRIES) {
            try {
                val response = httpClient.newCall(request).await()
                val code = response.code
                response.close()

                return when {
                    code in 200..299 -> {
                        Log.d(TAG, "Delivered to profile '${profile.name}' HTTP $code")
                        SendResult.DELIVERED
                    }
                    code == 408 || code == 425 || code == 429 || code in 500..599 -> {
                        // Retryable server-side errors — WorkManager will retry; don't count as error yet.
                        Log.w(TAG, "Retryable HTTP $code from profile '${profile.name}'")
                        outboxStore.markAttemptFailed(item.id, "HTTP $code (${profile.name})")
                        deliveryStatusStore.saveStatus(
                            "Retryable HTTP $code for $label on profile '${profile.name}' attempt ${item.attemptCount}. Will retry. Pending: ${outboxStore.count()}."
                        )
                        SendResult.RETRY
                    }
                    else -> {
                        // Non-retryable rejection (4xx auth/not-found/bad-request) — permanent.
                        Log.e(TAG, "HTTP $code from profile '${profile.name}'")
                        outboxStore.markAttemptFailed(item.id, "HTTP $code (${profile.name})")
                        deliveryStatusStore.saveStatus(
                            "HTTP $code for $label on profile '${profile.name}' — permanent failure. Pending: ${outboxStore.count()}."
                        )
                        SendResult.PERMANENT_FAILURE
                    }
                }
            } catch (e: IOException) {
                val isTransient = e is UnknownHostException || e is SocketTimeoutException
                if (isTransient && localRetryCount < MAX_LOCAL_RETRIES) {
                    localRetryCount++
                    val delayMs = when (localRetryCount) { 1 -> 2000L; 2 -> 5000L; else -> 10000L }
                    Log.w(TAG, "Transient error for '${profile.name}', local retry $localRetryCount in ${delayMs}ms")
                    delay(delayMs)
                    continue
                }
                val detail = e.describeForLog()
                outboxStore.markAttemptFailed(item.id, detail)
                deliveryStatusStore.saveStatus(
                    "Network error for $label on profile '${profile.name}': $detail. Will retry. Pending: ${outboxStore.count()}."
                )
                return SendResult.RETRY
            } catch (e: Exception) {
                val detail = e.describeForLog()
                outboxStore.markAttemptFailed(item.id, detail)
                deliveryStatusStore.saveStatus(
                    "Unexpected error for $label on profile '${profile.name}': $detail. Will retry. Pending: ${outboxStore.count()}."
                )
                return SendResult.RETRY
            }
        }
        return SendResult.RETRY
    }

    private enum class SendResult { DELIVERED, RETRY, PERMANENT_FAILURE }

    private fun computeHmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }

    private fun Throwable.describeForLog(): String {
        val type = when (this) {
            is SocketTimeoutException -> "timeout"
            else -> this::class.java.simpleName
        }
        val detail = message?.trim().orEmpty()
        return if (detail.isBlank()) type else "$type: $detail"
    }

    data class WebhookPayload(
        val deliveryId: String?,
        val sender: String,
        val message: String,
        val receivedAt: String?,
        val receivedAtMillis: Long?,
        val deviceModel: String?,
        val attempt: Int?
    )

    companion object {
        private const val TAG = "SmsWebhookWorker"
        private const val UNIQUE_WORK_NAME = "sms-webhook-drain"
        private const val MAX_LOCAL_RETRIES = 3
        private const val FAILURE_NOTIFICATION_THRESHOLD = 5
        private const val MAX_PERMANENT_FAILURE_ATTEMPTS = 10
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()

        // Fix #6: retryOnConnectionFailure disabled — the manual localRetryCount loop in
        // sendToProfile already handles transient network failures. Keeping OkHttp's own
        // retry on top would silently double the number of attempts per worker run.
        private val httpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        fun enqueue(
            context: Context,
            sender: String,
            message: String,
            receivedAtMillis: Long,
            isManualTest: Boolean = false
        ) {
            SmsOutboxStore(context.applicationContext).enqueue(sender, message, receivedAtMillis, isManualTest)
            enqueuePendingDrain(context, replaceExisting = true)
        }

        fun enqueuePendingDrain(context: Context, replaceExisting: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SmsWebhookWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
