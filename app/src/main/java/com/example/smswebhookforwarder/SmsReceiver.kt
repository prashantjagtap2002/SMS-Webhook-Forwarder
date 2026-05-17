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
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
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
        // Fix #9: use the first PDU's timestamp — that is when the SMS was received.
        // maxOfOrNull picked the latest PDU for multi-part messages, which is slightly wrong.
        val receivedAtMillis = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        if (sender.isBlank() || messageBody.isBlank()) {
            return
        }

        val statusStore = DeliveryStatusStore(context)
        val filterStore = SenderFilterStore(context)
        if (!filterStore.shouldForward(sender)) {
            statusStore.saveStatus("SMS from $sender blocked by sender filter at ${receivedAtMillis.formatTimestamp()}.")
            return
        }
        if (!filterStore.matchesMessageFilter(messageBody)) {
            statusStore.saveStatus("SMS from $sender blocked by message regex filter at ${receivedAtMillis.formatTimestamp()}.")
            return
        }

        val outboxStore = SmsOutboxStore(context)
        outboxStore.enqueue(
            sender = sender,
            message = messageBody,
            receivedAtMillis = receivedAtMillis,
            isManualTest = false
        )

        statusStore.saveStatus(
            "Receiver captured SMS from $sender at ${receivedAtMillis.formatTimestamp()} and queued webhook delivery. Pending outbox size: ${outboxStore.count()}."
        )

        SmsWebhookWorker.enqueuePendingDrain(context, replaceExisting = true)
    }

    private fun Long.formatTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(this))
    }
}
