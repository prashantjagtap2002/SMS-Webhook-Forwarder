package com.example.smswebhookforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // WAP/MMS push messages are not forwarded by this app.
        // This receiver exists solely so the app qualifies as a default SMS handler,
        // which allows Android to grant the RECEIVE_SMS restricted permission.
    }
}
