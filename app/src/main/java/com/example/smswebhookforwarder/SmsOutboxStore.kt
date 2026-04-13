package com.example.smswebhookforwarder

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SmsOutboxStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun enqueue(
        sender: String,
        message: String,
        receivedAtMillis: Long,
        isManualTest: Boolean
    ): PendingWebhookDelivery {
        val existing = findExisting(sender, message, receivedAtMillis, isManualTest)
        if (existing != null) {
            return existing
        }

        val newEntry = PendingWebhookDelivery(
            id = UUID.randomUUID().toString(),
            sender = sender,
            message = message,
            receivedAtMillis = receivedAtMillis,
            isManualTest = isManualTest,
            createdAtMillis = System.currentTimeMillis()
        )

        val updated = readAll().toMutableList()
        updated.add(newEntry)
        writeAll(updated)
        return newEntry
    }

    fun peek(): PendingWebhookDelivery? = readAll().minByOrNull { it.createdAtMillis }

    fun markAttemptStarted(id: String): PendingWebhookDelivery? {
        val updated = readAll().map { entry ->
            if (entry.id == id) {
                entry.copy(
                    attemptCount = entry.attemptCount + 1,
                    lastAttemptAtMillis = System.currentTimeMillis(),
                    lastError = null
                )
            } else {
                entry
            }
        }

        writeAll(updated)
        return updated.firstOrNull { it.id == id }
    }

    fun markAttemptFailed(id: String, error: String) {
        val updated = readAll().map { entry ->
            if (entry.id == id) {
                entry.copy(lastError = error)
            } else {
                entry
            }
        }

        writeAll(updated)
    }

    fun remove(id: String) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun count(): Int = readAll().size

    fun hasPending(): Boolean = count() > 0

    private fun findExisting(
        sender: String,
        message: String,
        receivedAtMillis: Long,
        isManualTest: Boolean
    ): PendingWebhookDelivery? {
        return readAll().firstOrNull { entry ->
            entry.sender == sender &&
                entry.message == message &&
                entry.receivedAtMillis == receivedAtMillis &&
                entry.isManualTest == isManualTest
        }
    }

    private fun readAll(): List<PendingWebhookDelivery> {
        val raw = preferences.getString(KEY_OUTBOX, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return runCatching {
            gson.fromJson<List<PendingWebhookDelivery>>(raw, pendingListType)
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<PendingWebhookDelivery>) {
        preferences.edit()
            .putString(KEY_OUTBOX, gson.toJson(items))
            .apply()
    }

    data class PendingWebhookDelivery(
        val id: String,
        val sender: String,
        val message: String,
        val receivedAtMillis: Long,
        val isManualTest: Boolean,
        val createdAtMillis: Long,
        val attemptCount: Int = 0,
        val lastAttemptAtMillis: Long? = null,
        val lastError: String? = null
    )

    companion object {
        private const val PREFS_NAME = "sms_webhook_outbox"
        private const val KEY_OUTBOX = "pending_deliveries"
        private val pendingListType =
            object : TypeToken<List<PendingWebhookDelivery>>() {}.type
    }
}
