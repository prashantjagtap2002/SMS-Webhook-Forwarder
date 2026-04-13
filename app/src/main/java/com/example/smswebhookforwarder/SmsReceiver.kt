package com.example.smswebhookforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            return
        }

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty().trim()
        val messageBody = buildString {
            messages.forEach { append(it.displayMessageBody.orEmpty()) }
        }.trim()
        val receivedAtMillis = messages.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()

        if (sender.isBlank() || messageBody.isBlank()) {
            return
        }

        val outboxStore = SmsOutboxStore(context)
        outboxStore.enqueue(
            sender = sender,
            message = messageBody,
            receivedAtMillis = receivedAtMillis,
            isManualTest = false
        )

        DeliveryStatusStore(context).saveStatus(
            "Receiver captured SMS from $sender at ${receivedAtMillis.formatTimestamp()} and queued webhook delivery. Pending outbox size: ${outboxStore.count()}."
        )

        SmsWebhookWorker.enqueuePendingDrain(context)
    }

    private fun Long.formatTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(this))
    }
}
