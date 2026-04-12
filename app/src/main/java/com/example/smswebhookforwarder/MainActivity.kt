package com.example.smswebhookforwarder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.smswebhookforwarder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: WebhookConfigStore
    private lateinit var deliveryStatusStore: DeliveryStatusStore

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionState()
        deliveryStatusStore.saveStatus(
            if (granted) {
                "RECEIVE_SMS permission granted by user."
            } else {
                "RECEIVE_SMS permission denied by user."
            }
        )
        refreshDeliveryUi()
        if (!granted) {
            showToast(getString(R.string.sms_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = WebhookConfigStore(applicationContext)
        deliveryStatusStore = DeliveryStatusStore(applicationContext)
        binding.webhookEditText.setText(configStore.getWebhookUrl())

        binding.saveWebhookButton.setOnClickListener { saveWebhookUrl() }
        binding.requestPermissionButton.setOnClickListener { requestSmsPermission(force = true) }
        binding.sendTestWebhookButton.setOnClickListener { sendTestWebhook() }
        binding.refreshStatusButton.setOnClickListener { refreshDeliveryUi() }
        binding.clearLogsButton.setOnClickListener { clearLogs() }

        updateWebhookState()
        updatePermissionState()
        refreshDeliveryUi()
        requestSmsPermission(force = false)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        updateWebhookState()
        refreshDeliveryUi()
    }

    private fun saveWebhookUrl() {
        val webhookUrl = normalizeWebhookUrl(binding.webhookEditText.text?.toString().orEmpty())
        val validationError = validateWebhookUrl(webhookUrl)
        if (validationError != null) {
            binding.webhookInputLayout.error = validationError
            return
        }

        binding.webhookInputLayout.error = null
        binding.webhookEditText.setText(webhookUrl)
        configStore.saveWebhookUrl(webhookUrl)
        deliveryStatusStore.saveStatus("Webhook URL saved: $webhookUrl")
        updateWebhookState()
        refreshDeliveryUi()
        showToast(getString(R.string.webhook_saved))
    }

    private fun updateWebhookState() {
        val webhookUrl = configStore.getWebhookUrl()
        binding.webhookStatusText.text = if (webhookUrl.isBlank()) {
            getString(R.string.webhook_missing)
        } else {
            getString(R.string.webhook_configured, webhookUrl)
        }
    }

    private fun updatePermissionState() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        binding.permissionStatusText.text = if (granted) {
            getString(R.string.sms_permission_granted)
        } else {
            getString(R.string.sms_permission_missing)
        }

        binding.requestPermissionButton.isEnabled = !granted
        binding.requestPermissionButton.text = if (granted) {
            getString(R.string.sms_permission_ready)
        } else {
            getString(R.string.request_sms_permission)
        }
    }

    private fun requestSmsPermission(force: Boolean) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            if (force) {
                deliveryStatusStore.saveStatus("RECEIVE_SMS permission was already granted.")
                refreshDeliveryUi()
                showToast(getString(R.string.sms_permission_already_granted))
            }
            return
        }

        smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
    }

    private fun sendTestWebhook() {
        val webhookUrl = configStore.getWebhookUrl()
        val validationError = validateWebhookUrl(webhookUrl)
        if (validationError != null) {
            binding.webhookInputLayout.error = validationError
            deliveryStatusStore.saveStatus("Manual test could not start because the webhook URL is invalid.")
            refreshDeliveryUi()
            return
        }

        binding.webhookInputLayout.error = null
        deliveryStatusStore.saveStatus("Queued manual test webhook delivery.")
        refreshDeliveryUi()

        SmsWebhookWorker.enqueue(
            context = applicationContext,
            sender = "MANUAL_TEST",
            message = "This is a manual test payload from SMS Webhook Forwarder.",
            receivedAtMillis = System.currentTimeMillis(),
            isManualTest = true
        )

        showToast(getString(R.string.test_webhook_queued))
    }

    private fun refreshDeliveryUi() {
        binding.deliveryStatusText.text = deliveryStatusStore.getLastStatus()
        binding.deliveryLogsText.text = deliveryStatusStore.getLogText()
    }

    private fun clearLogs() {
        deliveryStatusStore.clearLogs()
        refreshDeliveryUi()
        showToast(getString(R.string.logs_cleared))
    }

    private fun normalizeWebhookUrl(candidate: String): String {
        return candidate.filterNot { it.isWhitespace() }
    }

    private fun validateWebhookUrl(candidate: String): String? {
        if (candidate.isBlank()) {
            return getString(R.string.invalid_webhook_url)
        }

        return runCatching {
            val uri = candidate.toUri()
            val scheme = uri.scheme.orEmpty().lowercase()
            val firstPathSegment = uri.pathSegments.firstOrNull().orEmpty()
            val webhookId = uri.pathSegments.getOrNull(1).orEmpty()

            when {
                scheme != "http" && scheme != "https" -> getString(R.string.invalid_webhook_url)
                uri.host.isNullOrBlank() -> getString(R.string.invalid_webhook_url)
                firstPathSegment != "webhook" && firstPathSegment != "webhook-test" ->
                    getString(R.string.invalid_n8n_webhook_path)
                webhookId.isBlank() -> getString(R.string.invalid_n8n_webhook_path)
                else -> null
            }
        }.getOrElse {
            getString(R.string.invalid_webhook_url)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
