package com.example.smswebhookforwarder

import android.content.Context
import android.content.SharedPreferences

class PayloadConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun includeDeliveryId(): Boolean = prefs.getBoolean(KEY_DELIVERY_ID, true)
    fun includeReceivedAt(): Boolean = prefs.getBoolean(KEY_RECEIVED_AT, true)
    fun includeReceivedAtMillis(): Boolean = prefs.getBoolean(KEY_RECEIVED_AT_MILLIS, true)
    fun includeDeviceModel(): Boolean = prefs.getBoolean(KEY_DEVICE_MODEL, true)
    fun includeAttempt(): Boolean = prefs.getBoolean(KEY_ATTEMPT, true)

    fun save(
        deliveryId: Boolean,
        receivedAt: Boolean,
        receivedAtMillis: Boolean,
        deviceModel: Boolean,
        attempt: Boolean
    ) {
        prefs.edit()
            .putBoolean(KEY_DELIVERY_ID, deliveryId)
            .putBoolean(KEY_RECEIVED_AT, receivedAt)
            .putBoolean(KEY_RECEIVED_AT_MILLIS, receivedAtMillis)
            .putBoolean(KEY_DEVICE_MODEL, deviceModel)
            .putBoolean(KEY_ATTEMPT, attempt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "sms_payload_config"
        private const val KEY_DELIVERY_ID = "include_delivery_id"
        private const val KEY_RECEIVED_AT = "include_received_at"
        private const val KEY_RECEIVED_AT_MILLIS = "include_received_at_millis"
        private const val KEY_DEVICE_MODEL = "include_device_model"
        private const val KEY_ATTEMPT = "include_attempt"
    }
}
