package com.example.smswebhookforwarder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WebhookProfile(
    val id: String,
    val name: String,
    val url: String,
    val authHeader: String = "",
    val hmacSecret: String = "",
    val enabled: Boolean = false
)

class WebhookProfileStore(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val preferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val gson = Gson()

    fun getProfiles(): List<WebhookProfile> {
        val raw = preferences.getString(KEY_PROFILES, null).orEmpty()
        if (raw.isBlank()) return defaultProfiles()
        return runCatching {
            gson.fromJson<List<WebhookProfile>>(raw, profileListType)
        }.getOrElse { defaultProfiles() }
    }

    fun saveProfiles(profiles: List<WebhookProfile>) {
        preferences.edit()
            .putString(KEY_PROFILES, gson.toJson(profiles.take(MAX_PROFILES)))
            .apply()
    }

    fun getEnabledProfiles(): List<WebhookProfile> =
        getProfiles().filter { it.enabled && it.url.isNotBlank() }

    fun migrateFromLegacy(legacyUrl: String) {
        if (legacyUrl.isBlank()) return
        val raw = preferences.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) return
        val profiles = defaultProfiles().toMutableList()
        profiles[0] = profiles[0].copy(url = legacyUrl, enabled = true)
        saveProfiles(profiles)
    }

    private fun defaultProfiles(): List<WebhookProfile> = listOf(
        WebhookProfile(id = "1", name = "Profile 1", url = "", enabled = false),
        WebhookProfile(id = "2", name = "Profile 2", url = "", enabled = false),
        WebhookProfile(id = "3", name = "Profile 3", url = "", enabled = false)
    )

    companion object {
        private const val PREFS_NAME = "sms_webhook_profiles_secure"
        private const val KEY_PROFILES = "webhook_profiles"
        const val MAX_PROFILES = 3
        private val profileListType = object : TypeToken<List<WebhookProfile>>() {}.type
    }
}
