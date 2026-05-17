package com.example.smswebhookforwarder

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.smswebhookforwarder.databinding.ActivityMainBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var deliveryStatusStore: DeliveryStatusStore
    private lateinit var outboxStore: SmsOutboxStore
    private lateinit var profileStore: WebhookProfileStore
    private lateinit var filterStore: SenderFilterStore
    private lateinit var payloadConfigStore: PayloadConfigStore
    private lateinit var buildStore: BuildStore
    private val githubClient = GitHubClient(
        owner = "prashantjagtap2002",
        repo = "SMS-Webhook-Forwarder",
        workflowFile = "build.yml"
    )
    private val actionsUrl = "https://github.com/prashantjagtap2002/SMS-Webhook-Forwarder/actions"

    private data class ProfileViews(
        val content: LinearLayout,
        val expandButton: com.google.android.material.button.MaterialButton,
        val enabledSwitch: MaterialSwitch,
        val nameEdit: TextInputEditText,
        val urlLayout: TextInputLayout,
        val urlEdit: TextInputEditText,
        val authHeaderEdit: TextInputEditText,
        val hmacSecretEdit: TextInputEditText
    )

    private lateinit var profileViews: List<ProfileViews>

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        deliveryStatusStore.saveStatus(
            if (granted) "RECEIVE_SMS permission granted." else "RECEIVE_SMS permission denied."
        )
        refreshDeliveryUi()
        refreshHomeTab()
        if (!granted) showToast(getString(R.string.sms_permission_denied))
    }

    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshDeliveryUi()
        refreshHomeTab()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateBatteryAndNotifState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        deliveryStatusStore = DeliveryStatusStore(applicationContext)
        outboxStore = SmsOutboxStore(applicationContext)
        profileStore = WebhookProfileStore(applicationContext)
        filterStore = SenderFilterStore(applicationContext)
        payloadConfigStore = PayloadConfigStore(applicationContext)
        buildStore = BuildStore(applicationContext)

        DeliveryNotificationHelper.createChannel(applicationContext)
        migrateLegacyConfig()

        profileViews = listOf(
            ProfileViews(
                content = binding.profile1Content,
                expandButton = binding.profile1ExpandButton,
                enabledSwitch = binding.profile1EnabledSwitch,
                nameEdit = binding.profile1NameEdit,
                urlLayout = binding.profile1UrlInputLayout,
                urlEdit = binding.profile1UrlEdit,
                authHeaderEdit = binding.profile1AuthHeaderEdit,
                hmacSecretEdit = binding.profile1HmacSecretEdit
            ),
            ProfileViews(
                content = binding.profile2Content,
                expandButton = binding.profile2ExpandButton,
                enabledSwitch = binding.profile2EnabledSwitch,
                nameEdit = binding.profile2NameEdit,
                urlLayout = binding.profile2UrlInputLayout,
                urlEdit = binding.profile2UrlEdit,
                authHeaderEdit = binding.profile2AuthHeaderEdit,
                hmacSecretEdit = binding.profile2HmacSecretEdit
            ),
            ProfileViews(
                content = binding.profile3Content,
                expandButton = binding.profile3ExpandButton,
                enabledSwitch = binding.profile3EnabledSwitch,
                nameEdit = binding.profile3NameEdit,
                urlLayout = binding.profile3UrlInputLayout,
                urlEdit = binding.profile3UrlEdit,
                authHeaderEdit = binding.profile3AuthHeaderEdit,
                hmacSecretEdit = binding.profile3HmacSecretEdit
            )
        )

        profileViews.forEach { pv ->
            pv.expandButton.setOnClickListener { toggleProfileExpand(pv) }
            pv.enabledSwitch.setOnCheckedChangeListener { _, _ ->
                if (pv.enabledSwitch.isChecked && pv.content.visibility == View.GONE) {
                    toggleProfileExpand(pv)
                }
            }
        }

        // Settings tab buttons
        binding.saveAllProfilesButton.setOnClickListener { saveAllProfiles() }
        binding.batteryOptButton.setOnClickListener { requestBatteryOptExemption() }
        binding.requestNotifPermButton.setOnClickListener { requestNotifPermission() }
        binding.saveFilterButton.setOnClickListener { saveFilter() }
        binding.savePayloadConfigButton.setOnClickListener { savePayloadConfig() }

        // Logs tab buttons
        binding.sendTestWebhookButton.setOnClickListener { sendTestWebhook() }
        binding.refreshStatusButton.setOnClickListener { refreshDeliveryUi() }
        binding.exportLogsButton.setOnClickListener { exportLogs() }
        binding.clearLogsButton.setOnClickListener { clearLogs() }
        binding.resetStatsButton.setOnClickListener { resetStats() }

        // Home tab buttons
        binding.homeGrantSmsButton.setOnClickListener { requestSmsPermission(force = true) }
        binding.homeSetDefaultSmsButton.setOnClickListener { requestDefaultSmsApp() }
        binding.homeSendTestButton.setOnClickListener { sendTestWebhook() }
        binding.homeBatteryExemptButton.setOnClickListener { requestBatteryOptExemption() }
        binding.homeNotifEnableButton.setOnClickListener { requestNotifPermission() }
        binding.homeWebhookGoSettingsButton.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.navSettings
        }

        // Build & Updates section
        binding.homeBuildPatEdit.setText(buildStore.getPat())
        binding.homeBuildSavePatButton.setOnClickListener { saveGithubPat() }
        binding.homeBuildPushButton.setOnClickListener { triggerBuild() }
        binding.homeBuildRefreshButton.setOnClickListener { refreshBuildStatus() }
        binding.homeBuildOpenActionsButton.setOnClickListener { openActionsInBrowser() }

        binding.logSearchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateLogsDisplay() }
        })

        // Bottom navigation tab switching
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navHome -> showTab(0)
                R.id.navSettings -> showTab(1)
                R.id.navLogs -> showTab(2)
            }
            true
        }

        loadProfilesIntoUi()
        loadFilterIntoUi()
        loadPayloadConfigIntoUi()
        updateBatteryAndNotifState()
        updateProfilesStatusText()
        refreshDeliveryUi()
        refreshHomeTab()
        refreshBuildStatus()
        requestSmsPermission(force = false)
    }

    override fun onResume() {
        super.onResume()
        updateBatteryAndNotifState()
        updateProfilesStatusText()
        refreshDeliveryUi()
        refreshHomeTab()
    }

    // ── Tab navigation ──────────────────────────────────────────────────────

    private fun showTab(index: Int) {
        binding.tabHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.tabSettings.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.tabLogs.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // ── Home tab ────────────────────────────────────────────────────────────

    private fun refreshHomeTab() {
        val smsPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val isDefaultSms = isDefaultSmsApp()

        val enabledProfiles = profileStore.getEnabledProfiles()
        val webhookConfigured = enabledProfiles.isNotEmpty()

        val pm = getSystemService(PowerManager::class.java)
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val allGood = smsPerm && webhookConfigured && isDefaultSms
        val statusColor = ContextCompat.getColor(
            this, if (allGood) R.color.statusGreen else R.color.statusAmber
        )
        binding.homeStatusDot.setTextColor(statusColor)
        binding.homeStatusTitle.text = getString(
            if (allGood) R.string.home_status_ok_title else R.string.home_status_warn_title
        )
        binding.homeStatusSubtitle.text = getString(
            if (allGood) R.string.home_status_ok_subtitle else R.string.home_status_warn_subtitle
        )

        // SMS permission row
        binding.homeSetupSmsStatusText.text = getString(
            if (smsPerm) R.string.home_sms_granted else R.string.home_sms_missing
        )
        binding.homeGrantSmsButton.visibility = if (smsPerm) View.GONE else View.VISIBLE

        // Default SMS app row
        binding.homeDefaultSmsStatusText.text = getString(
            if (isDefaultSms) R.string.home_default_sms_ok else R.string.home_default_sms_missing
        )
        binding.homeSetDefaultSmsButton.visibility = if (isDefaultSms) View.GONE else View.VISIBLE

        // Webhook row
        binding.homeWebhookStatusText.text = if (webhookConfigured) {
            getString(R.string.home_webhook_ok, enabledProfiles.size)
        } else {
            getString(R.string.home_webhook_missing)
        }

        // Battery row
        binding.homeBatteryStatusText.text = getString(
            if (batteryExempt) R.string.home_battery_ok else R.string.home_battery_warn
        )
        binding.homeBatteryExemptButton.visibility = if (batteryExempt) View.GONE else View.VISIBLE

        // Notifications row
        binding.homeNotifStatusText.text = getString(
            if (notifGranted) R.string.home_notif_ok else R.string.home_notif_missing
        )
        binding.homeNotifEnableButton.visibility = if (notifGranted) View.GONE else View.VISIBLE

        // Test button and network status
        binding.homeSendTestButton.isEnabled = webhookConfigured && smsPerm
        binding.homeNetworkStatusText.text = getNetworkStatusText()
    }

    private fun isDefaultSmsApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        return Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    private fun requestDefaultSmsApp() {
        // Modern path: RoleManager.ROLE_SMS (Android 10+). This shows a proper system
        // dialog "Allow [app] to access SMS messages?" and grants RECEIVE_SMS on accept.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    showToast(getString(R.string.sms_permission_already_granted))
                    return
                }
                defaultSmsLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                )
                return
            }
        }
        // Pre-Android 10 fallback
        defaultSmsLauncher.launch(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    // ── Profile management ──────────────────────────────────────────────────

    private fun migrateLegacyConfig() {
        val legacyUrl = WebhookConfigStore(applicationContext).getWebhookUrl()
        profileStore.migrateFromLegacy(legacyUrl)
        if (legacyUrl.isNotBlank()) {
            WebhookConfigStore(applicationContext).saveWebhookUrl("")
        }
    }

    private fun loadProfilesIntoUi() {
        val profiles = profileStore.getProfiles()
        profiles.forEachIndexed { i, profile ->
            val pv = profileViews[i]
            pv.nameEdit.setText(profile.name)
            pv.urlEdit.setText(profile.url)
            pv.authHeaderEdit.setText(profile.authHeader)
            pv.hmacSecretEdit.setText(profile.hmacSecret)
            pv.enabledSwitch.isChecked = profile.enabled
            if (i == 0 && profile.url.isNotBlank()) {
                pv.content.visibility = View.VISIBLE
                pv.expandButton.text = getString(R.string.profile_collapse)
            }
        }
    }

    private fun toggleProfileExpand(pv: ProfileViews) {
        val expanding = pv.content.visibility == View.GONE
        pv.content.visibility = if (expanding) View.VISIBLE else View.GONE
        pv.expandButton.text = getString(if (expanding) R.string.profile_collapse else R.string.profile_expand)
    }

    private fun saveAllProfiles() {
        val existing = profileStore.getProfiles()
        val updated = existing.mapIndexed { i, profile ->
            val pv = profileViews[i]
            val url = pv.urlEdit.text?.toString().orEmpty().trim().filterNot { it.isWhitespace() }
            pv.urlLayout.error = if (url.isNotBlank()) validateUrl(url) else null
            profile.copy(
                name = pv.nameEdit.text?.toString().orEmpty().trim().ifBlank { "Profile ${i + 1}" },
                url = url,
                authHeader = pv.authHeaderEdit.text?.toString().orEmpty().trim(),
                hmacSecret = pv.hmacSecretEdit.text?.toString().orEmpty().trim(),
                enabled = pv.enabledSwitch.isChecked
            )
        }
        if (updated.any { it.url.isNotBlank() && validateUrl(it.url) != null }) return

        profileStore.saveProfiles(updated)
        updateProfilesStatusText()
        val enabledCount = updated.count { it.enabled && it.url.isNotBlank() }
        deliveryStatusStore.saveStatus("Webhook profiles saved. $enabledCount profile(s) active.")
        if (outboxStore.hasPending()) SmsWebhookWorker.enqueuePendingDrain(applicationContext, replaceExisting = true)
        refreshDeliveryUi()
        refreshHomeTab()
        showToast(getString(R.string.profiles_saved))
    }

    private fun updateProfilesStatusText() {
        val enabled = profileStore.getEnabledProfiles()
        binding.profilesStatusText.text = if (enabled.isEmpty()) {
            getString(R.string.profiles_status_none)
        } else {
            getString(R.string.profiles_enabled_count, enabled.size)
        }
    }

    private fun validateUrl(candidate: String): String? {
        if (candidate.isBlank()) return getString(R.string.invalid_webhook_url)
        return runCatching {
            val uri = Uri.parse(candidate)
            when {
                uri.scheme?.lowercase() != "https" -> getString(R.string.invalid_webhook_url)
                uri.host.isNullOrBlank() -> getString(R.string.invalid_webhook_url)
                else -> null
            }
        }.getOrElse { getString(R.string.invalid_webhook_url) }
    }

    // ── Battery optimization & notifications ───────────────────────────────

    private fun updateBatteryAndNotifState() {
        val pm = getSystemService(PowerManager::class.java)
        val isExempt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.batteryOptStatusText.text = getString(
            if (isExempt) R.string.battery_opt_exempt else R.string.battery_opt_restricted
        )
        binding.batteryOptButton.visibility = if (isExempt) View.GONE else View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            binding.notifPermStatusText.text = getString(
                if (granted) R.string.notif_perm_granted else R.string.notif_perm_missing
            )
            binding.requestNotifPermButton.visibility = if (granted) View.GONE else View.VISIBLE
        } else {
            binding.notifPermStatusText.text = getString(R.string.notif_perm_granted)
            binding.requestNotifPermButton.visibility = View.GONE
        }

        refreshHomeTab()
    }

    private fun requestBatteryOptExemption() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Sender & message filter ────────────────────────────────────────────

    private fun loadFilterIntoUi() {
        val mode = filterStore.getMode()
        binding.filterModeGroup.check(
            when (mode) {
                FilterMode.ALLOW_ALL -> R.id.filterAllRadio
                FilterMode.ALLOWLIST -> R.id.filterAllowlistRadio
                FilterMode.BLOCKLIST -> R.id.filterBlocklistRadio
            }
        )
        binding.filterListEdit.setText(filterStore.getList().joinToString(", "))
        binding.messageRegexEdit.setText(filterStore.getMessageRegex())
        updateFilterStatusText(mode)
    }

    private fun saveFilter() {
        val mode = when (binding.filterModeGroup.checkedRadioButtonId) {
            R.id.filterAllowlistRadio -> FilterMode.ALLOWLIST
            R.id.filterBlocklistRadio -> FilterMode.BLOCKLIST
            else -> FilterMode.ALLOW_ALL
        }
        val list = binding.filterListEdit.text?.toString().orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val regex = binding.messageRegexEdit.text?.toString().orEmpty().trim()

        if (regex.isNotBlank()) {
            val valid = runCatching { Regex(regex) }.isSuccess
            if (!valid) {
                showToast(getString(R.string.message_regex_invalid))
                return
            }
        }

        filterStore.saveMode(mode)
        filterStore.saveList(list)
        filterStore.saveMessageRegex(regex)
        updateFilterStatusText(mode)
        showToast(getString(R.string.filter_saved))
    }

    private fun updateFilterStatusText(mode: FilterMode) {
        val regexActive = filterStore.getMessageRegex().isNotBlank()
        val senderStatus = getString(
            when (mode) {
                FilterMode.ALLOW_ALL -> R.string.filter_status_all
                FilterMode.ALLOWLIST -> R.string.filter_status_allowlist
                FilterMode.BLOCKLIST -> R.string.filter_status_blocklist
            }
        )
        val regexStatus = if (regexActive) getString(R.string.message_regex_active) else getString(R.string.message_regex_inactive)
        binding.filterStatusText.text = "$senderStatus\n$regexStatus"
    }

    // ── Payload config ─────────────────────────────────────────────────────

    private fun loadPayloadConfigIntoUi() {
        binding.payloadDeliveryIdSwitch.isChecked = payloadConfigStore.includeDeliveryId()
        binding.payloadReceivedAtSwitch.isChecked = payloadConfigStore.includeReceivedAt()
        binding.payloadReceivedAtMillisSwitch.isChecked = payloadConfigStore.includeReceivedAtMillis()
        binding.payloadDeviceModelSwitch.isChecked = payloadConfigStore.includeDeviceModel()
        binding.payloadAttemptSwitch.isChecked = payloadConfigStore.includeAttempt()
    }

    private fun savePayloadConfig() {
        payloadConfigStore.save(
            deliveryId = binding.payloadDeliveryIdSwitch.isChecked,
            receivedAt = binding.payloadReceivedAtSwitch.isChecked,
            receivedAtMillis = binding.payloadReceivedAtMillisSwitch.isChecked,
            deviceModel = binding.payloadDeviceModelSwitch.isChecked,
            attempt = binding.payloadAttemptSwitch.isChecked
        )
        showToast(getString(R.string.payload_config_saved))
    }

    // ── SMS permission ─────────────────────────────────────────────────────

    private fun requestSmsPermission(force: Boolean) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            if (force) {
                deliveryStatusStore.saveStatus("RECEIVE_SMS permission was already granted.")
                refreshDeliveryUi()
                showToast(getString(R.string.sms_permission_already_granted))
            }
            return
        }
        // On Android 10+, RECEIVE_SMS is a restricted permission. Sideloaded apps cannot
        // be granted it via the normal runtime dialog — the system shows "App was denied
        // access" instead. The only way to get it is by becoming the default SMS app,
        // after which Android grants RECEIVE_SMS automatically.
        if (!isDefaultSmsApp()) {
            if (force) requestDefaultSmsApp()
            return
        }
        // Already the default SMS app but permission still missing (rare): try the
        // raw runtime request as a last resort.
        smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
    }

    // ── Delivery UI ────────────────────────────────────────────────────────

    private fun sendTestWebhook() {
        if (profileStore.getEnabledProfiles().isEmpty()) {
            showToast(getString(R.string.profiles_status_none))
            return
        }
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
        val stats = deliveryStatusStore.getStats()
        val total = stats.successCount + stats.errorCount
        val pending = outboxStore.count()

        binding.statSuccessCount.text = stats.successCount.toString()
        binding.statErrorCount.text = stats.errorCount.toString()
        binding.statPendingCount.text = pending.toString()

        binding.statRateText.text = if (total > 0) {
            val rate = stats.successCount * 100.0 / total
            getString(R.string.stat_rate_with_total, rate, total)
        } else {
            getString(R.string.stat_rate_no_data)
        }

        binding.deliveryStatusText.text = deliveryStatusStore.getLastStatus()
        updateNetworkStatus()
        updateLogsDisplay()
    }

    private fun updateLogsDisplay() {
        val query = binding.logSearchEdit.text?.toString().orEmpty()
        binding.deliveryLogsText.text = deliveryStatusStore.getFilteredLogText(query)
    }

    private fun getNetworkStatusText(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val networkLabel = when {
            caps == null -> getString(R.string.network_none)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.network_wifi)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.network_mobile)
            else -> getString(R.string.network_other)
        }
        val pendingCount = outboxStore.count()
        val workerLabel = if (pendingCount > 0) {
            getString(R.string.worker_pending, pendingCount)
        } else {
            getString(R.string.worker_idle)
        }
        return getString(R.string.network_status, networkLabel, workerLabel)
    }

    private fun updateNetworkStatus() {
        binding.networkStatusText.text = getNetworkStatusText()
    }

    private fun clearLogs() {
        deliveryStatusStore.clearLogs()
        refreshDeliveryUi()
        showToast(getString(R.string.logs_cleared))
    }

    private fun resetStats() {
        deliveryStatusStore.resetStats()
        refreshDeliveryUi()
        showToast(getString(R.string.stats_reset))
    }

    private fun exportLogs() {
        try {
            val logs = deliveryStatusStore.getLogText()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "sms_webhook_logs_$ts.txt"
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, fileName)
            file.writeText(logs)
            showToast(getString(R.string.export_success, file.absolutePath))
        } catch (e: Exception) {
            showToast(getString(R.string.export_failed, e.message.orEmpty()))
        }
    }

    // ── Window insets ──────────────────────────────────────────────────────

    private fun applyWindowInsets() {
        val containers = listOf(
            binding.homeContentContainer,
            binding.settingsContentContainer,
            binding.logsContentContainer
        )
        val basePaddings = containers.map {
            intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
        }
        val baseNavBottom = binding.bottomNav.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            containers.zip(basePaddings).forEach { (container, base) ->
                container.setPadding(
                    base[0] + bars.left,
                    base[1] + bars.top,
                    base[2] + bars.right,
                    base[3]
                )
            }
            binding.bottomNav.setPadding(0, 0, 0, baseNavBottom + bars.bottom)
            windowInsets
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ── Build & Updates ────────────────────────────────────────────────────

    private fun saveGithubPat() {
        val pat = binding.homeBuildPatEdit.text?.toString().orEmpty().trim()
        buildStore.savePat(pat)
        showToast(getString(R.string.home_build_pat_saved))
        refreshBuildStatus()
    }

    private fun openActionsInBrowser() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionsUrl)))
    }

    private fun triggerBuild() {
        val pat = buildStore.getPat()
        if (pat.isBlank()) {
            showToast(getString(R.string.home_build_pat_missing))
            return
        }
        binding.homeBuildPushButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { githubClient.triggerWorkflow(pat) }
            when (result) {
                is GitHubClient.Result.Ok -> {
                    showToast(getString(R.string.home_build_push_triggered))
                    // GitHub takes a few seconds to register the new run
                    kotlinx.coroutines.delay(3000)
                    refreshBuildStatus()
                }
                is GitHubClient.Result.Err -> {
                    showToast(getString(R.string.home_build_push_failed, result.message))
                }
            }
            binding.homeBuildPushButton.isEnabled = true
        }
    }

    private fun refreshBuildStatus() {
        binding.homeBuildInstalledText.text = getString(
            R.string.home_build_installed_label
        ) + " " + BuildConfig.VERSION_NAME
        binding.homeBuildLatestText.text = getString(R.string.home_build_latest_label) + " " +
            getString(R.string.home_build_loading)
        binding.homeBuildLastRunText.text = getString(R.string.home_build_last_run_label) + " " +
            getString(R.string.home_build_loading)
        binding.homeBuildPushButton.isEnabled = buildStore.hasPat()

        lifecycleScope.launch {
            val pat = buildStore.getPat().ifBlank { null }
            val releaseResult = withContext(Dispatchers.IO) { githubClient.getLatestRelease() }
            val runResult = withContext(Dispatchers.IO) { githubClient.getLatestRun(pat) }

            // Latest release line
            binding.homeBuildLatestText.text = when (releaseResult) {
                is GitHubClient.Result.Ok -> {
                    val release = releaseResult.value
                    if (release == null) {
                        getString(R.string.home_build_latest_label) + " —"
                    } else {
                        val installed = "v${BuildConfig.VERSION_NAME}"
                        val marker = if (release.tagName == installed) {
                            getString(R.string.home_build_up_to_date)
                        } else {
                            getString(R.string.home_build_update_available)
                        }
                        "${getString(R.string.home_build_latest_label)} ${release.tagName}  $marker"
                    }
                }
                is GitHubClient.Result.Err -> {
                    "${getString(R.string.home_build_latest_label)} ${getString(R.string.home_build_status_error, releaseResult.message)}"
                }
            }

            // Last run line
            binding.homeBuildLastRunText.text = when (runResult) {
                is GitHubClient.Result.Ok -> {
                    val run = runResult.value
                    if (run == null) {
                        "${getString(R.string.home_build_last_run_label)} ${getString(R.string.home_build_status_no_runs)}"
                    } else {
                        val title = run.displayTitle.ifBlank { run.headBranch }
                        val statusLine = when (run.status) {
                            "completed" -> when (run.conclusion) {
                                "success" -> getString(R.string.home_build_status_success, title)
                                "failure" -> getString(R.string.home_build_status_failed, title, run.htmlUrl)
                                "cancelled" -> getString(R.string.home_build_status_cancelled, title)
                                else -> "${run.conclusion ?: "?"} · $title"
                            }
                            "in_progress" -> getString(R.string.home_build_status_running, title)
                            "queued" -> getString(R.string.home_build_status_queued, title)
                            else -> "${run.status} · $title"
                        }
                        "${getString(R.string.home_build_last_run_label)} $statusLine"
                    }
                }
                is GitHubClient.Result.Err -> {
                    "${getString(R.string.home_build_last_run_label)} ${getString(R.string.home_build_status_error, runResult.message)}"
                }
            }
        }
    }
}
