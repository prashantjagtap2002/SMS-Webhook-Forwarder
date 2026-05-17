package com.example.smswebhookforwarder

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SmsComposeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, getString(R.string.sms_compose_unsupported), Toast.LENGTH_SHORT).show()
        finish()
    }
}
