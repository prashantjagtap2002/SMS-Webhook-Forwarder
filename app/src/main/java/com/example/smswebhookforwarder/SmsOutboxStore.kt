package com.example.smswebhookforwarder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SmsOutboxStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val preferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val gson = Gson()

    // Fix #2: all read-modify-write methods use a class-level lock shared across all instances,
    // preventing SmsReceiver (main thread) and SmsWebhookWorker (worker thread) from
    // corrupting the outbox with concurrent reads and writes.

    fun enqueue(
        sender: String,
        message: String,
        receivedAtMillis: Long,
        isManualTest: Boolean
    ): PendingWebhookDelivery = synchronized(lock) {
        val existing = findExisting(sender, message, receivedAtMillis, isManualTest)
        if (existing != null) return@synchronized existing

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
        newEntry
    }

    fun peekAll(): List<PendingWebhookDelivery> = synchronized(lock) {
        readAll().sortedBy { it.createdAtMillis }
    }

    fun peek(): PendingWebhookDelivery? = synchronized(lock) {
        readAll().minByOrNull { it.createdAtMillis }
    }

    fun markAttemptStarted(id: String): PendingWebhookDelivery? = synchronized(lock) {
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
        updated.firstOrNull { it.id == id }
    }

    fun markDeliveredToProfile(id: String, profileId: String) = synchronized(lock) {
        val updated = readAll().map { entry ->
            if (entry.id == id) entry.copy(deliveredProfileIds = entry.deliveredProfileIds + profileId)
            else entry
        }
        writeAll(updated)
    }

    fun markAttemptFailed(id: String, error: String) = synchronized(lock) {
        val updated = readAll().map { entry ->
            if (entry.id == id) entry.copy(lastError = error)
            else entry
        }
        writeAll(updated)
    }

    fun remove(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun count(): Int = synchronized(lock) { readAll().size }

    fun hasPending(): Boolean = count() > 0

    // Private helpers — always called from within a synchronized(lock) block.
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
        if (raw.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<PendingWebhookDelivery>>(raw, pendingListType)
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<PendingWebhookDelivery>) {
        preferences.edit()
            .putString(KEY_OUTBOX, gson.toJson(items))
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
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
        val lastError: String? = null,
        val deliveredProfileIds: Set<String> = emptySet()
    )

    companion object {
        private val lock = Any()
        private const val PREFS_NAME = "sms_webhook_outbox_secure"
        private const val KEY_OUTBOX = "pending_deliveries"
        private val pendingListType =
            object : TypeToken<List<PendingWebhookDelivery>>() {}.type
    }
}
