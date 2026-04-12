package com.example.smswebhookforwarder

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeliveryStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    fun clearLogs() {
        preferences.edit()
            .putString(KEY_LAST_STATUS, DEFAULT_STATUS)
            .remove(KEY_LOGS)
            .apply()
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

    companion object {
        private const val PREFS_NAME = "sms_webhook_delivery_status"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LOGS = "logs"
        private const val DEFAULT_STATUS = "No SMS has been captured yet."
        private const val DEFAULT_LOGS = "No delivery logs yet."
        private const val MAX_LOG_ENTRIES = 100
        private val logEntryType = object : TypeToken<List<LogEntry>>() {}.type
    }
}
