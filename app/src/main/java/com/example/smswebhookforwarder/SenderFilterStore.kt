package com.example.smswebhookforwarder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class FilterMode { ALLOW_ALL, ALLOWLIST, BLOCKLIST }

class SenderFilterStore(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val preferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getMode(): FilterMode {
        val name = preferences.getString(KEY_MODE, FilterMode.ALLOW_ALL.name).orEmpty()
        return runCatching { FilterMode.valueOf(name) }.getOrDefault(FilterMode.ALLOW_ALL)
    }

    fun saveMode(mode: FilterMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getList(): List<String> {
        val raw = preferences.getString(KEY_LIST, "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun saveList(entries: List<String>) {
        preferences.edit().putString(KEY_LIST, entries.joinToString(",")).apply()
    }

    fun getMessageRegex(): String =
        preferences.getString(KEY_MESSAGE_REGEX, "").orEmpty()

    fun saveMessageRegex(pattern: String) {
        preferences.edit().putString(KEY_MESSAGE_REGEX, pattern.trim()).apply()
    }

    fun matchesMessageFilter(message: String): Boolean {
        val pattern = getMessageRegex()
        if (pattern.isBlank()) return true
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(message) }
            .getOrDefault(true)
    }

    fun shouldForward(sender: String): Boolean {
        return when (getMode()) {
            FilterMode.ALLOW_ALL -> true
            FilterMode.ALLOWLIST -> {
                // Fix #5: empty allowlist should block everything, not forward everything.
                // A user who enables allowlist mode without adding entries expects all SMS blocked.
                val list = getList()
                list.isNotEmpty() && list.any { sender.contains(it, ignoreCase = true) }
            }
            FilterMode.BLOCKLIST -> {
                val list = getList()
                list.none { sender.contains(it, ignoreCase = true) }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "sms_sender_filter_secure"
        private const val KEY_MODE = "filter_mode"
        private const val KEY_LIST = "filter_list"
        private const val KEY_MESSAGE_REGEX = "message_regex"
    }
}
