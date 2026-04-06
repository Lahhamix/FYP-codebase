package com.example.ble_viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.github.angads25.toggle.widget.LabeledSwitch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private var pendingLanguageTransition = false
        private const val EXTRA_LANGUAGE_TRANSITION = "extra_language_transition"
        const val EXTRA_SCROLL_TO_RESOURCES = "extra_scroll_to_resources"
        private const val PREFS_NAME = "SolematePrefs"
        private const val KEY_DEVICE_ALERTS_ENABLED = "device_alerts_enabled"
        private const val KEY_VOICE_READ_HINTS_ENABLED = "voice_read_hints_enabled"
        private const val KEY_VOICE_READ_HINTS_DIALOG_SEEN = "voice_read_hints_dialog_seen"
        private const val FEEDBACK_FORM_URL_EN = "https://docs.google.com/forms/d/e/1FAIpQLSe0C7NxZ-hUC-oVMoHSYUianMU36Q1E4xyMS07JrURUJOXjEw/viewform?usp=dialog"
        private const val FEEDBACK_FORM_URL_AR = "https://docs.google.com/forms/d/e/1FAIpQLSeV57vy8dmqPhhS2ElAWk40UaOpFfPHfTvdEUbzHOTVdCdsEQ/viewform?usp=publish-editor"
        private const val FEEDBACK_FORM_URL_FR = "https://docs.google.com/forms/d/e/1FAIpQLSfGYhRvRHMw5pOEZHiQuD2yoHH5n2Kx5rEdt7noJBoZb0n1QQ/viewform?usp=publish-editor"
    }

    private var suppressFinishAnimation = false

    private lateinit var settingsUsername: TextView
    private lateinit var settingsEmail: TextView
    private lateinit var languageValue: TextView
    private lateinit var settingsScrollView: View
    private lateinit var textSizeSlider: SeekBar
    private lateinit var textSizeValue: TextView
    private lateinit var deviceAlertsSwitch: LabeledSwitch
    private lateinit var voiceReadHintsSwitch: LabeledSwitch
    private lateinit var voiceReadHintsRow: LinearLayout
    private var suppressDeviceAlertsToggleCallback = false
    private var suppressVoiceReadToggleCallback = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeechText: String? = null

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (isGranted) {
            setDeviceAlertsSwitchChecked(true)
            prefs.edit().putBoolean(KEY_DEVICE_ALERTS_ENABLED, true).apply()
        } else {
            setDeviceAlertsSwitchChecked(false)
            prefs.edit().putBoolean(KEY_DEVICE_ALERTS_ENABLED, false).apply()
            Toast.makeText(this, getString(R.string.toast_notifications_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private data class LanguageOption(
        val tag: String,
        val labelRes: Int,
        val optionViewId: Int,
        val circleViewId: Int,
        val flagViewId: Int,
        val labelViewId: Int
    )

    private val languageOptions = listOf(
        LanguageOption(
            "en",
            R.string.language_english,
            R.id.language_option_en,
            R.id.language_circle_en,
            R.id.language_flag_en,
            R.id.language_label_en
        ),
        LanguageOption(
            "ar",
            R.string.language_arabic,
            R.id.language_option_ar,
            R.id.language_circle_ar,
            R.id.language_flag_ar,
            R.id.language_label_ar
        ),
        LanguageOption(
            "fr",
            R.string.language_french,
            R.id.language_option_fr,
            R.id.language_circle_fr,
            R.id.language_flag_fr,
            R.id.language_label_fr
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val transitioned = pendingLanguageTransition || intent.getBooleanExtra(EXTRA_LANGUAGE_TRANSITION, false)
        if (transitioned) {
            val content = findViewById<View>(android.R.id.content)
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(280L).start()
            pendingLanguageTransition = false
        }

        settingsUsername = findViewById(R.id.settings_username)
        settingsEmail = findViewById(R.id.settings_email)
        languageValue = findViewById(R.id.settings_language_value)
        settingsScrollView = findViewById(R.id.settings_scroll_view)
        textSizeSlider = findViewById(R.id.text_size_slider)
        textSizeValue = findViewById(R.id.settings_text_size_value)
        deviceAlertsSwitch = findViewById(R.id.settings_device_alerts_switch)
        voiceReadHintsSwitch = findViewById(R.id.settings_voice_read_hints_switch)
        voiceReadHintsRow = findViewById(R.id.settings_voice_read_hints_row)
        updateUsername()
        updateLanguageChip()
        setupAccessibilityToggles()
        setupReadableResourceRows()
        setupTextSizeControls()
        setupSecurityAndAccessibilityTts()
        setupGeneralNavigationTts()

        if (intent.getBooleanExtra(EXTRA_SCROLL_TO_RESOURCES, false)) {
            settingsScrollView.post {
                val resourcesSection = findViewById<View>(R.id.settings_resources_section)
                (settingsScrollView as android.widget.ScrollView).smoothScrollTo(0, resourcesSection.top)
            }
        }

        findViewById<Button>(R.id.button_edit_profile).setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_edit_profile_coming_soon), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.button_edit_profile).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.edit_profile))
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.button_sign_out).setOnClickListener {
            val prefs = getSharedPreferences("SolematePrefs", MODE_PRIVATE)
            prefs.edit()
                .remove("username")
                .remove("google_email")
                .remove("google_id")
                .remove("auth_provider")
                .remove("is_logged_in")
                .remove("remember_me")
                .apply()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.button_sign_out).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.sign_out))
                true
            } else {
                false
            }
        }

        findViewById<TextView>(R.id.button_send_feedback).setOnClickListener {
            openFeedbackFormForCurrentLanguage()
        }
        findViewById<TextView>(R.id.button_send_feedback).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_feedback))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_language_row).setOnClickListener {
            showLanguagePicker()
        }
        findViewById<LinearLayout>(R.id.settings_language_row).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_language) + ". " + getString(R.string.settings_choose_language))
            }
            true
        }

        findViewById<LinearLayout>(R.id.nav_home).setOnClickListener {
            finish()
        }

        findViewById<LinearLayout>(R.id.nav_history).setOnClickListener {
            startActivity(Intent(this, ReadingsActivity::class.java))
            finish()
        }

        findViewById<LinearLayout>(R.id.nav_settings).setOnClickListener {
            // Already on settings.
        }
    }

    private fun openFeedbackFormForCurrentLanguage() {
        val url = when (currentLanguageTag()) {
            "ar" -> FEEDBACK_FORM_URL_AR
            "fr" -> FEEDBACK_FORM_URL_FR
            else -> FEEDBACK_FORM_URL_EN
        }

        val openUrlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            startActivity(openUrlIntent)
        }.onFailure {
            Toast.makeText(this, getString(R.string.settings_contact_support_desc), Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        super.finish()
        if (!suppressFinishAnimation) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun updateUsername() {
        val username = getSharedPreferences("SolematePrefs", MODE_PRIVATE)
            .getString("username", "Jane Salam")
            ?: "Jane Salam"
        settingsUsername.text = username

        val email = getSharedPreferences("SolematePrefs", MODE_PRIVATE)
            .getString("google_email", null)
            ?.trim()
            .orEmpty()

        if (email.isNotEmpty()) {
            settingsEmail.text = email
            settingsEmail.visibility = VISIBLE
        } else {
            settingsEmail.text = ""
            settingsEmail.visibility = GONE
        }
    }

    private fun updateLanguageChip() {
        val currentTag = currentLanguageTag()
        val option = languageOptions.firstOrNull { it.tag == currentTag } ?: languageOptions.first()
        languageValue.text = getString(option.labelRes)
    }

    private fun setupAccessibilityToggles() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        deviceAlertsSwitch.setOn(prefs.getBoolean(KEY_DEVICE_ALERTS_ENABLED, true))
        deviceAlertsSwitch.setOnToggledListener { _, isChecked ->
            if (suppressDeviceAlertsToggleCallback) return@setOnToggledListener

            if (isChecked && shouldRequestPostNotificationsPermission()) {
                postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnToggledListener
            }

            prefs.edit().putBoolean(KEY_DEVICE_ALERTS_ENABLED, isChecked).apply()
        }
        findViewById<LinearLayout>(R.id.settings_device_alerts_row).setOnClickListener {
            deviceAlertsSwitch.performClick()
        }
        findViewById<LinearLayout>(R.id.settings_device_alerts_row).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_device_alerts) + ". " + getString(R.string.settings_notifications_desc))
            }
            true
        }

        voiceReadHintsSwitch.setOn(prefs.getBoolean(KEY_VOICE_READ_HINTS_ENABLED, false))

        voiceReadHintsRow.setOnLongClickListener {
            speakSettingsText(buildTutorialSpeech())
            true
        }

        voiceReadHintsSwitch.setOnToggledListener { _, isChecked ->
            if (suppressVoiceReadToggleCallback) return@setOnToggledListener

            if (!isChecked) {
                prefs.edit().putBoolean(KEY_VOICE_READ_HINTS_ENABLED, false).apply()
                stopDialogSpeech()
                return@setOnToggledListener
            }

            val skipDialog = prefs.getBoolean(KEY_VOICE_READ_HINTS_DIALOG_SEEN, false)
            if (skipDialog) {
                prefs.edit().putBoolean(KEY_VOICE_READ_HINTS_ENABLED, true).apply()
            } else {
                showVoiceReadHintsDialog()
            }
        }
    }

    private fun showVoiceReadHintsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tts_info, null)
        TextSizeScaleManager.applyTo(dialogView, TextSizeScaleManager.scaleForMode(TextSizeScaleManager.getMode(this)))
        val messageView = dialogView.findViewById<TextView>(R.id.tts_dialog_message)
        val doNotShowAgain = dialogView.findViewById<CheckBox>(R.id.tts_dialog_do_not_show_again)
        val speakerButton = dialogView.findViewById<ImageButton>(R.id.tts_dialog_speaker)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.tts_dialog_cancel_button)
        val enableButton = dialogView.findViewById<MaterialButton>(R.id.tts_dialog_enable_button)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener {
            setVoiceReadHintsSwitchChecked(false)
            prefs.edit().putBoolean(KEY_VOICE_READ_HINTS_ENABLED, false).apply()
            stopDialogSpeech()
            dialog.dismiss()
        }

        enableButton.setOnClickListener {
            prefs.edit()
                .putBoolean(KEY_VOICE_READ_HINTS_ENABLED, true)
                .putBoolean(KEY_VOICE_READ_HINTS_DIALOG_SEEN, doNotShowAgain.isChecked)
                .apply()
            stopDialogSpeech()
            dialog.dismiss()
        }

        speakerButton.setOnClickListener {
            speakSettingsText(buildVoiceReadHintsSpeech(messageView.text.toString()), forcePreview = true)
        }

        dialog.setOnDismissListener {
            stopDialogSpeech()
        }

        dialog.show()
    }

    private fun setVoiceReadHintsSwitchChecked(checked: Boolean) {
        suppressVoiceReadToggleCallback = true
        voiceReadHintsSwitch.setOn(checked)
        suppressVoiceReadToggleCallback = false
    }

    private fun setDeviceAlertsSwitchChecked(checked: Boolean) {
        suppressDeviceAlertsToggleCallback = true
        deviceAlertsSwitch.setOn(checked)
        suppressDeviceAlertsToggleCallback = false
    }

    private fun shouldRequestPostNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun setupSecurityAndAccessibilityTts() {
        // Text Size Slider long-press TTS
        val textSizeValue = findViewById<TextView>(R.id.settings_text_size_value)
        val textSizeSlider = findViewById<SeekBar>(R.id.text_size_slider)
        if (textSizeValue != null && textSizeSlider != null) {
            findViewById<LinearLayout>(R.id.settings_text_size_row).setOnLongClickListener {
                if (isVoiceReadHintsEnabled()) {
                    speakSettingsText(getString(R.string.settings_text_size) + ": " + textSizeValue.text.toString())
                }
                true
            }
            // Also add long-press to the value TextView
            textSizeValue.setOnLongClickListener {
                if (isVoiceReadHintsEnabled()) {
                    speakSettingsText(getString(R.string.settings_text_size) + ": " + textSizeValue.text.toString())
                }
                true
            }
        }

        // Biometric Sign-in toggle long-press TTS
        findViewById<LinearLayout>(R.id.settings_biometric_signin_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_biometric_signin) + ". " + getString(R.string.settings_biometric_desc))
            }
            true
        }

        // Change Passcode long-press TTS
        findViewById<LinearLayout>(R.id.settings_change_passcode_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_change_passcode))
            }
            true
        }

        // Manage Paired Devices long-press TTS (uses new ID)
        findViewById<LinearLayout>(R.id.settings_manage_paired_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_manage_paired) + ". " + getString(R.string.settings_manage_paired_desc))
            }
            true
        }
    }

    private fun setupReadableResourceRows() {
        val resourceRows = listOf(
            findViewById<LinearLayout>(R.id.settings_voice_read_hints_row) to buildTutorialSpeech(),
            findViewById<LinearLayout>(R.id.settings_device_manual_row) to (getString(R.string.settings_device_manual) + ". " + getString(R.string.settings_device_manual_desc)),
            findViewById<LinearLayout>(R.id.settings_datasheet_row) to (getString(R.string.settings_datasheet) + ". " + getString(R.string.settings_datasheet_desc))
        )

        resourceRows.forEach { (row, speechText) ->
            row.setOnLongClickListener {
                if (isVoiceReadHintsEnabled()) {
                    speakSettingsText(speechText)
                }
                true
            }
        }
    }

    private fun setupGeneralNavigationTts() {
        findViewById<LinearLayout>(R.id.nav_home).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.nav_home))
            }
            true
        }

        findViewById<LinearLayout>(R.id.nav_history).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.nav_history))
            }
            true
        }

        findViewById<LinearLayout>(R.id.nav_settings).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.nav_settings))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_contact_support_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_contact_support) + ". " + getString(R.string.settings_contact_support_desc))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_privacy_policy_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_privacy_policy))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_terms_of_use_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_terms_of_use))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_export_data_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_export_data))
            }
            true
        }

        findViewById<LinearLayout>(R.id.settings_request_deletion_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(getString(R.string.settings_request_deletion))
            }
            true
        }
    }

    private fun buildVoiceReadHintsSpeech(messageOverride: String? = null): String {
        val message = messageOverride?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tts_dialog_message)
        return getString(R.string.tts_dialog_title) + ". " + message
    }

    private fun buildTutorialSpeech(): String {
        return getString(R.string.settings_app_tutorial) + ". " + getString(R.string.settings_app_tutorial_desc)
    }

    private fun speakSettingsText(text: String, forcePreview: Boolean = false) {
        if (!forcePreview && !isVoiceReadHintsEnabled()) {
            stopDialogSpeech()
            return
        }

        val message = text.trim()
        if (message.isBlank()) return

        pendingSpeechText = message

        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    speakPendingText()
                } else {
                    pendingSpeechText = null
                    Toast.makeText(this, getString(R.string.tts_unavailable), Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        if (ttsReady) {
            speakPendingText()
        }
    }

    private fun speakPendingText() {
        val engine = tts ?: return
        val message = pendingSpeechText?.trim().orEmpty()
        if (message.isBlank()) return

        if (!configureSpeechLanguage(engine)) {
            pendingSpeechText = null
            Toast.makeText(this, getString(R.string.tts_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "settings_tts")
        pendingSpeechText = null
    }

    private fun configureSpeechLanguage(engine: TextToSpeech): Boolean {
        val candidates = listOf(
            Locale.forLanguageTag(currentLanguageTag()),
            resources.configuration.locales[0],
            Locale.getDefault(),
            Locale.US
        ).distinctBy { it.toLanguageTag() }

        for (candidate in candidates) {
            val result = engine.setLanguage(candidate)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }

        return false
    }

    private fun stopDialogSpeech() {
        tts?.stop()
        pendingSpeechText = null
    }

    private fun isVoiceReadHintsEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_VOICE_READ_HINTS_ENABLED, false)
    }

    override fun onDestroy() {
        stopDialogSpeech()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun setupTextSizeControls() {
        val savedMode = TextSizeScaleManager.getMode(this)

        textSizeSlider.progress = savedMode
        applyTextSizeMode(savedMode)

        textSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mode = progress.coerceIn(TextSizeScaleManager.MODE_STANDARD, TextSizeScaleManager.MODE_EXTRA_LARGE)
                applyTextSizeMode(mode)
                if (fromUser) {
                    TextSizeScaleManager.setMode(this@SettingsActivity, mode)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyTextSizeMode(mode: Int) {
        textSizeValue.setText(TextSizeScaleManager.labelResForMode(mode))
        TextSizeScaleManager.applyTo(this, mode)
    }

    private fun currentLanguageTag(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val tag = appLocales.toLanguageTags().takeIf { it.isNotBlank() }
            ?: Locale.getDefault().toLanguageTag()
        return tag.substringBefore('-').lowercase(Locale.ROOT)
    }

    private fun showLanguagePicker() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_language_picker, null)
        TextSizeScaleManager.applyTo(dialogView, TextSizeScaleManager.scaleForMode(TextSizeScaleManager.getMode(this)))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val selectedTag = currentLanguageTag()

        languageOptions.forEach { option ->
            dialogView.findViewById<TextView>(option.flagViewId).text = flagEmojiFor(option.tag)
            dialogView.findViewById<TextView>(option.labelViewId).text = getString(option.labelRes)

            val isSelected = option.tag == selectedTag
            styleLanguageOption(dialogView, option, isSelected)

            dialogView.findViewById<View>(option.optionViewId).setOnClickListener {
                val slideDownExit = AnimationUtils.loadAnimation(this, R.anim.slide_down_exit)
                dialogView.startAnimation(slideDownExit)

                slideDownExit.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}

                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        dialog.dismiss()
                        applyLanguage(option.tag)
                    }

                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun styleLanguageOption(root: View, option: LanguageOption, isSelected: Boolean) {
        val circle = root.findViewById<View>(option.circleViewId)
        val flag = root.findViewById<TextView>(option.flagViewId)
        val label = root.findViewById<TextView>(option.labelViewId)

        circle.setBackgroundResource(
            if (isSelected) R.drawable.language_option_circle_bg_selected
            else R.drawable.language_option_circle_bg
        )

        flag.alpha = if (isSelected) 1f else 0.9f
        label.alpha = if (isSelected) 1f else 0.75f
        label.setTypeface(label.typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun flagEmojiFor(languageTag: String): String {
        return when (languageTag) {
            "en" -> "\uD83C\uDDEC\uD83C\uDDE7"
            "ar" -> "\uD83C\uDDF8\uD83C\uDDE6"
            "fr" -> "\uD83C\uDDEB\uD83C\uDDF7"
            else -> "\uD83C\uDF10"
        }
    }

    private fun applyLanguage(languageTag: String) {
        if (languageTag == currentLanguageTag()) return

        val content = findViewById<View>(android.R.id.content)
        content.animate()
            .alpha(0f)
            .setDuration(240L)
            .withEndAction {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
                pendingLanguageTransition = true

                val restartIntent = Intent(this, SettingsActivity::class.java).apply {
                    putExtra(EXTRA_LANGUAGE_TRANSITION, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                startActivity(restartIntent)
                overridePendingTransition(0, 0)
                suppressFinishAnimation = true
                finish()
            }
            .start()
    }
}
