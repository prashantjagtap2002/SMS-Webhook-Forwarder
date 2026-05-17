package com.example.smswebhookforwarder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class WebhookConfigStore(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val preferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getWebhookUrl(): String {
        return preferences.getString(KEY_WEBHOOK_URL, "").orEmpty().normalized()
    }

    fun saveWebhookUrl(url: String) {
        preferences.edit()
            .putString(KEY_WEBHOOK_URL, url.normalized())
            .apply()
    }

    private fun String.normalized(): String = filterNot { it.isWhitespace() }

    companion object {
        private const val PREFS_NAME = "sms_webhook_forwarder_secure"
        private const val KEY_WEBHOOK_URL = "webhook_url"
    }
}
