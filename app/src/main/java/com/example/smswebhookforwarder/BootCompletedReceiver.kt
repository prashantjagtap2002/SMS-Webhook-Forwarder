package com.example.smswebhookforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val outboxStore = SmsOutboxStore(context)
        if (!outboxStore.hasPending()) {
            return
        }

        DeliveryStatusStore(context).saveStatus(
            "Resuming ${outboxStore.count()} pending webhook deliver${if (outboxStore.count() == 1) "y" else "ies"} after device/app restart."
        )
        SmsWebhookWorker.enqueuePendingDrain(context)
    }
}
