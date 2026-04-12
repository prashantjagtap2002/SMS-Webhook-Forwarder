package com.example.smswebhookforwarder

import android.content.Context

class WebhookConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWebhookUrl(): String {
        val savedUrl = preferences.getString(KEY_WEBHOOK_URL, "").orEmpty().normalized()
        return savedUrl.ifBlank { DEFAULT_WEBHOOK_URL }
    }

    fun saveWebhookUrl(url: String) {
        preferences.edit()
            .putString(KEY_WEBHOOK_URL, url.normalized())
            .apply()
    }

    private fun String.normalized(): String = filterNot { it.isWhitespace() }

    companion object {
        private const val PREFS_NAME = "sms_webhook_forwarder"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val DEFAULT_WEBHOOK_URL =
            "https://n8n.fiaxe.com/webhook/0fe4aaea-d1e6-47ae-8a71-b8c9c0bc70ed"
    }
}
