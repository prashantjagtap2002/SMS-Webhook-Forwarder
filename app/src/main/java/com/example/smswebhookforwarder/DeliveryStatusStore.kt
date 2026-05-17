package com.example.smswebhookforwarder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeliveryStatusStore(context: Context) {
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

    fun getLastStatus(): String = preferences.getString(KEY_LAST_STATUS, DEFAULT_STATUS).orEmpty()

    fun saveStatus(status: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            message = status
        )
        val existing = getLogEntries().toMutableList()
        existing.add(0, entry)
        val trimmed = existing.take(MAX_LOG_ENTRIES)
        preferences.edit()
            .putString(KEY_LAST_STATUS, status)
            .putString(KEY_LOGS, gson.toJson(trimmed))
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
    }

    fun getLogText(): String {
        val entries = getLogEntries()
        if (entries.isEmpty()) {
            return DEFAULT_LOGS
        }

        return entries.joinToString(separator = "\n\n") { entry ->
            "[${entry.timestamp.formatTimestamp()}] ${entry.message}"
        }
    }

    fun getFilteredLogText(query: String): String {
        if (query.isBlank()) return getLogText()
        val filtered = getLogEntries().filter { it.message.contains(query, ignoreCase = true) }
        if (filtered.isEmpty()) return "No logs match \"$query\"."
        return filtered.joinToString(separator = "\n\n") { entry ->
            "[${entry.timestamp.formatTimestamp()}] ${entry.message}"
        }
    }

    fun clearLogs() {
        preferences.edit()
            .putString(KEY_LAST_STATUS, DEFAULT_STATUS)
            .remove(KEY_LOGS)
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
    }

    fun recordSuccess() {
        preferences.edit()
            .putInt(KEY_SUCCESS_COUNT, getStats().successCount + 1)
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
    }

    fun recordError() {
        preferences.edit()
            .putInt(KEY_ERROR_COUNT, getStats().errorCount + 1)
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
    }

    fun getStats(): DeliveryStats {
        return DeliveryStats(
            successCount = preferences.getInt(KEY_SUCCESS_COUNT, 0),
            errorCount = preferences.getInt(KEY_ERROR_COUNT, 0)
        )
    }

    fun resetStats() {
        preferences.edit()
            .putInt(KEY_SUCCESS_COUNT, 0)
            .putInt(KEY_ERROR_COUNT, 0)
            .apply()
        DeliveryWidgetUpdater.updateAll(appContext)
    }

    private fun getLogEntries(): List<LogEntry> {
        val raw = preferences.getString(KEY_LOGS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return runCatching {
            gson.fromJson<List<LogEntry>>(raw, logEntryType)
        }.getOrDefault(emptyList())
    }

    private fun Long.formatTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(this))
    }

    data class LogEntry(
        val timestamp: Long,
        val message: String
    )

    data class DeliveryStats(
        val successCount: Int,
        val errorCount: Int
    )

    companion object {
        private const val PREFS_NAME = "sms_webhook_delivery_status_secure"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LOGS = "logs"
        private const val KEY_SUCCESS_COUNT = "success_count"
        private const val KEY_ERROR_COUNT = "error_count"
        private const val DEFAULT_STATUS = "No SMS has been captured yet."
        private const val DEFAULT_LOGS = "No delivery logs yet."
        private const val MAX_LOG_ENTRIES = 100
        private val logEntryType = object : TypeToken<List<LogEntry>>() {}.type
    }
}
