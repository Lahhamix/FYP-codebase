package com.example.ble_viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.github.angads25.toggle.widget.LabeledSwitch
import com.yalantis.ucrop.UCrop
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import android.widget.FrameLayout

class SettingsActivity : AppCompatActivity() {

    companion object {
        private var pendingLanguageTransition = false
        private const val EXTRA_LANGUAGE_TRANSITION = "extra_language_transition"
        const val EXTRA_SCROLL_TO_RESOURCES = "extra_scroll_to_resources"
        private const val PREFS_NAME = "SolematePrefs"
        private const val KEY_DEVICE_ALERTS_ENABLED = "device_alerts_enabled"
        private const val KEY_VOICE_READ_HINTS_ENABLED = "voice_read_hints_enabled"
        private const val KEY_VOICE_READ_HINTS_DIALOG_SEEN = "voice_read_hints_dialog_seen"
        private const val KEY_PROFILE_IMAGE_PATH = "profile_image_path"
        private const val KEY_AUTO_SHARE_ENABLED = "auto_share_enabled"
        private const val KEY_AUTO_SHARE_EMAIL = "auto_share_email"
        private const val KEY_AUTO_SHARE_VERIFIED_EMAIL = "auto_share_verified_email"
        private const val KEY_AUTO_SHARE_VERIFIED_EMAILS = "auto_share_verified_emails"
        private const val KEY_AUTO_SHARE_VERIFIED_EMAILS_ORDERED = "auto_share_verified_emails_ordered"
        private const val KEY_AUTO_SHARE_PENDING_EMAIL = "auto_share_pending_email"
        private const val KEY_AUTO_SHARE_PENDING_CODE = "auto_share_pending_code"
        private const val KEY_AUTO_SHARE_PENDING_EXPIRES_AT = "auto_share_pending_expires_at"
        private const val KEY_AUTO_SHARE_DISABLE_CONFIRM_SKIP = "auto_share_disable_confirm_skip"
        private const val KEY_AUTO_SHARE_TIME_HOUR = "auto_share_time_hour"
        private const val KEY_AUTO_SHARE_TIME_MINUTE = "auto_share_time_minute"
        private const val DEFAULT_AUTO_SHARE_HOUR = 9
        private const val DEFAULT_AUTO_SHARE_MINUTE = 0
        private const val FEEDBACK_FORM_URL_EN = "https://docs.google.com/forms/d/e/1FAIpQLSe0C7NxZ-hUC-oVMoHSYUianMU36Q1E4xyMS07JrURUJOXjEw/viewform?usp=dialog"
        private const val FEEDBACK_FORM_URL_AR = "https://docs.google.com/forms/d/e/1FAIpQLSeV57vy8dmqPhhS2ElAWk40UaOpFfPHfTvdEUbzHOTVdCdsEQ/viewform?usp=publish-editor"
        private const val FEEDBACK_FORM_URL_FR = "https://docs.google.com/forms/d/e/1FAIpQLSfGYhRvRHMw5pOEZHiQuD2yoHH5n2Kx5rEdt7noJBoZb0n1QQ/viewform?usp=publish-editor"
    }

    private var suppressFinishAnimation = false

    private lateinit var settingsProfileImage: ImageView
    private lateinit var settingsUsername: TextView
    private lateinit var settingsEmail: TextView
    private lateinit var languageValue: TextView
    private lateinit var settingsScrollView: View
    private lateinit var textSizeSlider: SeekBar
    private lateinit var textSizeValue: TextView
    private lateinit var settingsAutoShareSummary: TextView
    private lateinit var settingsAutoShareRecipientsCount: TextView
    private lateinit var autoShareSwitch: LabeledSwitch
    private lateinit var deviceAlertsSwitch: LabeledSwitch
    private lateinit var voiceReadHintsSwitch: LabeledSwitch
    private lateinit var voiceReadHintsRow: LinearLayout
    private var suppressDeviceAlertsToggleCallback = false
    private var suppressAutoShareToggleCallback = false
    private var suppressVoiceReadToggleCallback = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeechText: String? = null
    private var editProfileDialogImageView: ImageView? = null
    private var pendingProfileImagePath: String? = null
    private var pendingCropSourceUri: Uri? = null

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

    private val pickProfileImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        launchProfileCrop(uri)
    }

    private val takeProfilePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap ?: return@registerForActivityResult
        val tempSourceUri = persistBitmapToCache(bitmap)
        if (tempSourceUri == null) {
            Toast.makeText(this, getString(R.string.toast_profile_image_update_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        launchProfileCrop(tempSourceUri)
    }

    private val cropProfileImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val outputUri = UCrop.getOutput(result.data ?: return@registerForActivityResult) ?: return@registerForActivityResult
        val savedPath = persistProfileImageFromUri(outputUri)
        if (savedPath == null) {
            Toast.makeText(this, getString(R.string.toast_profile_image_update_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        pendingProfileImagePath = savedPath
        editProfileDialogImageView?.setImageBitmap(BitmapFactory.decodeFile(savedPath))
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

        settingsProfileImage = findViewById(R.id.settings_profile_image)
        settingsUsername = findViewById(R.id.settings_username)
        settingsEmail = findViewById(R.id.settings_email)
        languageValue = findViewById(R.id.settings_language_value)
        settingsScrollView = findViewById(R.id.settings_scroll_view)
        textSizeSlider = findViewById(R.id.text_size_slider)
        textSizeValue = findViewById(R.id.settings_text_size_value)
        settingsAutoShareSummary = findViewById(R.id.settings_auto_share_summary)
        settingsAutoShareRecipientsCount = findViewById(R.id.settings_auto_share_recipients_count)
        autoShareSwitch = findViewById(R.id.settings_auto_share_switch)
        deviceAlertsSwitch = findViewById(R.id.settings_device_alerts_switch)
        voiceReadHintsSwitch = findViewById(R.id.settings_voice_read_hints_switch)
        voiceReadHintsRow = findViewById(R.id.settings_voice_read_hints_row)
        updateUsername()
        updateProfileImage()
        updateLanguageChip()
        updateAutoShareSummary()
        setupAccessibilityToggles()
        setupAutoShareToggle()
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
            showEditProfileDialog()
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

        findViewById<LinearLayout>(R.id.settings_auto_share_row).setOnClickListener {
            autoShareSwitch.performClick()
        }
        findViewById<LinearLayout>(R.id.settings_auto_share_row).setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(buildAutoShareSpeech())
            }
            true
        }

        findViewById<MaterialButton>(R.id.settings_auto_share_manage_button).setOnClickListener {
            showAutoShareDialog()
        }

        findViewById<LinearLayout>(R.id.settings_terms_of_use_row).setOnClickListener {
            showTermsOfUseDialog()
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

    private fun updateProfileImage() {
        val imagePath = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROFILE_IMAGE_PATH, null)
        loadProfileImageOrDefault(settingsProfileImage, imagePath)
    }

    private fun showEditProfileDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null)
        TextSizeScaleManager.applyTo(dialogView, TextSizeScaleManager.scaleForMode(TextSizeScaleManager.getMode(this)))

        val profileImage = dialogView.findViewById<ImageView>(R.id.edit_profile_dialog_image)
        val cameraButton = dialogView.findViewById<FrameLayout>(R.id.edit_profile_dialog_camera_button)
        val nameField = dialogView.findViewById<EditText>(R.id.edit_profile_dialog_name)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.edit_profile_dialog_cancel_button)
        val applyButton = dialogView.findViewById<MaterialButton>(R.id.edit_profile_dialog_apply_button)

        val currentName = prefs.getString("username", getString(R.string.settings_default_name))
            ?: getString(R.string.settings_default_name)
        val currentImagePath = prefs.getString(KEY_PROFILE_IMAGE_PATH, null)

        nameField.setText(currentName)
        pendingProfileImagePath = currentImagePath
        editProfileDialogImageView = profileImage
        loadProfileImageOrDefault(profileImage, currentImagePath)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val openImagePicker = {
            showProfileImageSourceOptions()
        }

        cameraButton.setOnClickListener { openImagePicker() }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        applyButton.setOnClickListener {
            val updatedName = nameField.text?.toString()?.trim().orEmpty()
                .ifBlank { getString(R.string.settings_default_name) }

            val saved = prefs.edit()
                .putString("username", updatedName)
                .putString(KEY_PROFILE_IMAGE_PATH, pendingProfileImagePath)
                .commit()

            if (!saved) {
                Toast.makeText(this, getString(R.string.toast_profile_update_failed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            updateUsername()
            updateProfileImage()
            Toast.makeText(this, getString(R.string.toast_profile_updated), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            editProfileDialogImageView = null
            pendingProfileImagePath = null
            pendingCropSourceUri = null
        }

        dialog.show()
    }

    private fun showProfileImageSourceOptions() {
        val options = arrayOf(
            getString(R.string.edit_profile_choose_gallery),
            getString(R.string.edit_profile_take_photo),
            getString(R.string.edit_profile_remove_photo)
        )

        val dialog = AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickProfileImageLauncher.launch("image/*")
                    1 -> takeProfilePhotoLauncher.launch(null)
                    2 -> {
                        pendingProfileImagePath = null
                        editProfileDialogImageView?.let {
                            loadProfileImageOrDefault(it, null)
                        }
                    }
                }
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(R.drawable.rounded_dialog_bg)
        dialog.show()
    }

    private fun launchProfileCrop(sourceUri: Uri) {
        val destinationUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            File(cacheDir, "profile_cropped_${System.currentTimeMillis()}.jpg")
        )

        pendingCropSourceUri = sourceUri
        runCatching {
            val cropIntent = UCrop.of(sourceUri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(1024, 1024)
                .withOptions(
                    UCrop.Options().apply {
                        val appBlue = ContextCompat.getColor(this@SettingsActivity, R.color.blue)
                        val appBlueDark = ContextCompat.getColor(this@SettingsActivity, R.color.dark_blue)
                        val white = ContextCompat.getColor(this@SettingsActivity, R.color.white)

                        setToolbarTitle(getString(R.string.edit_profile_crop_photo))
                        setCircleDimmedLayer(false)
                        setShowCropFrame(true)
                        setShowCropGrid(true)
                        setToolbarColor(appBlue)
                        setStatusBarColor(appBlueDark)
                        setToolbarWidgetColor(white)
                        setActiveControlsWidgetColor(appBlue)
                        setDimmedLayerColor(Color.parseColor("#B3000000"))
                        setCropFrameColor(white)
                        setCropGridColor(white)
                        setCropGridStrokeWidth(1)
                        setCropFrameStrokeWidth(3)
                        setCompressionQuality(90)
                    }
                )
                .getIntent(this)

            cropProfileImageLauncher.launch(cropIntent)
        }.onFailure {
            // Fallback: keep app alive and apply the selected image without crop.
            val savedPath = persistProfileImageFromUri(sourceUri)
            if (savedPath != null) {
                pendingProfileImagePath = savedPath
                editProfileDialogImageView?.setImageBitmap(BitmapFactory.decodeFile(savedPath))
            } else {
                Toast.makeText(this, getString(R.string.toast_profile_image_update_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistProfileImageFromUri(uri: Uri): String? {
        val targetFile = File(filesDir, "profile_image.jpg")
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            targetFile.absolutePath
        }.getOrNull()
    }

    private fun persistBitmapToCache(bitmap: Bitmap): Uri? {
        val targetFile = File(cacheDir, "profile_source_${System.currentTimeMillis()}.jpg")
        return runCatching {
            FileOutputStream(targetFile, false).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                targetFile
            )
        }.getOrNull()
    }

    private fun loadProfileImageOrDefault(target: ImageView, imagePath: String?) {
        val file = imagePath?.let(::File)
        if (file != null && file.exists()) {
            target.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        } else {
            target.setImageResource(R.drawable.profile)
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

        // Ensure the custom rounded background in dialog_tts_info is visible.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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

    private fun showTermsOfUseDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_terms_of_use, null)
        TextSizeScaleManager.applyTo(dialogView, TextSizeScaleManager.scaleForMode(TextSizeScaleManager.getMode(this)))

        val closeButton = dialogView.findViewById<MaterialButton>(R.id.terms_dialog_close_button)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAutoShareDialog() {
        showAutoShareDialog(triggeredByToggle = false)
    }

    private fun showAutoShareDialog(triggeredByToggle: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_share, null)
        TextSizeScaleManager.applyTo(dialogView, TextSizeScaleManager.scaleForMode(TextSizeScaleManager.getMode(this)))

        val emailField = dialogView.findViewById<EditText>(R.id.auto_share_dialog_email)
        val verifyEmailButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_dialog_verify_email_button)
        val codeContainer = dialogView.findViewById<LinearLayout>(R.id.auto_share_dialog_code_container)
        val codeField = dialogView.findViewById<EditText>(R.id.auto_share_dialog_code)
        val resendContainer = dialogView.findViewById<LinearLayout>(R.id.auto_share_dialog_resend_container)
        val resendButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_dialog_resend_button)
        val resendTimerText = dialogView.findViewById<TextView>(R.id.auto_share_dialog_resend_timer)
        val sharedWithLabel = dialogView.findViewById<TextView>(R.id.auto_share_dialog_shared_with_label)
        val emailLabel = dialogView.findViewById<TextView>(R.id.auto_share_dialog_email_label)
        val emailRow = dialogView.findViewById<LinearLayout>(R.id.auto_share_dialog_email_row)
        val verifiedSection = dialogView.findViewById<LinearLayout>(R.id.auto_share_dialog_verified_section)
        val verifiedList = dialogView.findViewById<LinearLayout>(R.id.auto_share_dialog_verified_list)
        val addRecipientButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_dialog_add_recipient_button)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_dialog_cancel_button)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_dialog_save_button)

        val resendCooldownMs = 60_000L
        var resendTimer: CountDownTimer? = null
        var autoShareDialog: AlertDialog? = null

        val verifiedEmails = getVerifiedAutoShareEmails(prefs).toMutableList()

        fun persistVerifiedEmails() {
            val normalized = verifiedEmails
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()

            val orderedJson = JSONArray(normalized).toString()

            val editor = prefs.edit()
                .putString(KEY_AUTO_SHARE_VERIFIED_EMAILS_ORDERED, orderedJson)
                .putStringSet(KEY_AUTO_SHARE_VERIFIED_EMAILS, normalized.toCollection(LinkedHashSet()))

            val primary = normalized.firstOrNull().orEmpty()
            if (primary.isNotBlank()) {
                editor.putString(KEY_AUTO_SHARE_EMAIL, primary)
                editor.putString(KEY_AUTO_SHARE_VERIFIED_EMAIL, primary)
            } else {
                editor.remove(KEY_AUTO_SHARE_EMAIL)
                editor.remove(KEY_AUTO_SHARE_VERIFIED_EMAIL)
            }
            editor.apply()
        }

        fun renderVerifiedEmails() {
            verifiedList.removeAllViews()
            val hasRecipients = verifiedEmails.isNotEmpty()
            sharedWithLabel.visibility = if (hasRecipients) View.VISIBLE else View.GONE
            verifiedList.visibility = if (hasRecipients) View.VISIBLE else View.GONE
            verifiedSection.visibility = if (hasRecipients) View.VISIBLE else View.GONE
            if (verifiedEmails.isEmpty()) return

            val density = resources.displayMetrics.density

            verifiedEmails.forEach { email ->
                val emailContainer = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.settings_outline_button_bg)
                    setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (8 * density).toInt()
                    )
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val emailText = TextView(this).apply {
                    text = email
                    setTextColor(Color.parseColor("#1A416B"))
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val deleteButton = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_trash_2)
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (38 * density).toInt(),
                        (38 * density).toInt()
                    ).apply {
                        marginStart = (12 * density).toInt()
                    }
                    setOnClickListener {
                        val dialogView = LayoutInflater.from(this@SettingsActivity)
                            .inflate(R.layout.dialog_stop_sharing, null)

                        val titleView = dialogView.findViewById<TextView>(R.id.stop_sharing_title)
                        val messageView = dialogView.findViewById<TextView>(R.id.stop_sharing_message)
                        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.stop_sharing_cancel_button)
                        val removeButton = dialogView.findViewById<MaterialButton>(R.id.stop_sharing_remove_button)

                        titleView.text = "Stop Sharing?"
                        messageView.text = "By removing $email, they won't be receiving your health alerts anymore."

                        val dialog = AlertDialog.Builder(this@SettingsActivity)
                            .setView(dialogView)
                            .create()

                        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                        cancelButton.setOnClickListener {
                            dialog.dismiss()
                        }

                        removeButton.setOnClickListener {
                            val wasAutoShareEnabled = prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, false)
                            verifiedEmails.removeAll { it.equals(email, ignoreCase = true) }
                            persistVerifiedEmails()
                            renderVerifiedEmails()

                            if (verifiedEmails.isEmpty() && wasAutoShareEnabled) {
                                prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
                                AutoShareScheduler.cancel(this@SettingsActivity)
                                setAutoShareSwitchChecked(false)
                                updateAutoShareSummary()
                                autoShareDialog?.dismiss()
                                showAutoShareTurnedOffDialog()
                            }

                            Thread {
                                val (sent, errorDetail) = sendSharingStoppedEmail(email)
                                if (!sent) {
                                    runOnUiThread {
                                        val detail = errorDetail?.take(120).orEmpty()
                                        val message = if (detail.isNotBlank()) {
                                            "Recipient removed, but update email failed (" + detail + ")"
                                        } else {
                                            "Recipient removed, but update email failed"
                                        }
                                        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                            dialog.dismiss()
                        }

                        dialog.show()
                    }
                }

                emailContainer.addView(emailText)
                emailContainer.addView(deleteButton)
                verifiedList.addView(emailContainer)
            }
        }

        fun resetEntryFields() {
            emailField.text?.clear()
            codeField.text?.clear()
            codeContainer.visibility = View.GONE
            resendContainer.visibility = View.GONE
            resendTimerText.visibility = View.GONE
            resendButton.alpha = 1f
            resendButton.text = getString(R.string.settings_auto_share_dialog_resend)
            verifyEmailButton.text = getString(R.string.settings_auto_share_dialog_verify)
            verifyEmailButton.isEnabled = true
            verifyEmailButton.visibility = View.VISIBLE
        }

        fun setRecipientEntryVisible(visible: Boolean) {
            emailLabel.visibility = if (visible) View.VISIBLE else View.GONE
            emailRow.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) {
                codeContainer.visibility = View.GONE
                resendContainer.visibility = View.GONE
                resendTimerText.visibility = View.GONE
            }
        }

        fun cooldownRemainingMs(): Long {
            val lastSentAt = prefs.getLong("auto_share_last_code_sent_at", 0L)
            if (lastSentAt <= 0L) return 0L
            return (lastSentAt + resendCooldownMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }

        fun updateResendForCooldown(remainingMs: Long) {
            val isVerifyMode = codeContainer.visibility == View.VISIBLE && !codeField.text.isNullOrBlank()
            if (isVerifyMode) {
                resendButton.isEnabled = true
                resendButton.alpha = 1f
                resendTimerText.visibility = View.GONE
                return
            }

            if (remainingMs <= 0L) {
                resendButton.isEnabled = true
                resendButton.alpha = 1f
                resendTimerText.visibility = View.GONE
            } else {
                resendButton.isEnabled = false
                resendButton.alpha = 0.45f
                resendTimerText.visibility = View.VISIBLE
                resendTimerText.text = getString(
                    R.string.settings_auto_share_resend_in,
                    (remainingMs / 1000L).toInt().coerceAtLeast(1)
                )
            }
        }

        fun startResendCooldown(remainingMs: Long) {
            resendTimer?.cancel()
            updateResendForCooldown(remainingMs)
            if (remainingMs <= 0L) return

            resendTimer = object : CountDownTimer(remainingMs, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    updateResendForCooldown(millisUntilFinished)
                }

                override fun onFinish() {
                    updateResendForCooldown(0L)
                }
            }.start()
        }

        fun sendVerificationCode(targetEmail: String) {
            val verificationCode = generateVerificationCode()
            verifyEmailButton.isEnabled = false

            Thread {
                val (sent, errorDetail) = sendVerificationEmail(targetEmail, verificationCode)
                runOnUiThread {
                    verifyEmailButton.isEnabled = true
                    if (!sent) {
                        val detail = errorDetail?.take(140).orEmpty()
                        val message = if (detail.isNotBlank()) {
                            getString(R.string.settings_auto_share_send_failed) + " (" + detail + ")"
                        } else {
                            getString(R.string.settings_auto_share_send_failed)
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        if (verifiedEmails.isEmpty()) {
                            prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
                            AutoShareScheduler.cancel(this@SettingsActivity)
                            setAutoShareSwitchChecked(false)
                            updateAutoShareSummary()
                        }
                        return@runOnUiThread
                    }

                    prefs.edit()
                        .putString(KEY_AUTO_SHARE_PENDING_EMAIL, targetEmail)
                        .putString(KEY_AUTO_SHARE_PENDING_CODE, verificationCode)
                        .putLong(KEY_AUTO_SHARE_PENDING_EXPIRES_AT, System.currentTimeMillis() + 24L * 60L * 60L * 1000L)
                        .putLong("auto_share_last_code_sent_at", System.currentTimeMillis())
                        .apply()

                    verifyEmailButton.visibility = View.GONE
                    codeContainer.visibility = View.VISIBLE
                    resendContainer.visibility = View.VISIBLE
                    resendButton.text = getString(R.string.settings_auto_share_dialog_resend)
                    codeField.text?.clear()
                    codeField.requestFocus()
                    Toast.makeText(
                        this,
                        getString(R.string.settings_auto_share_code_sent_to, targetEmail),
                        Toast.LENGTH_SHORT
                    ).show()
                    startResendCooldown(resendCooldownMs)
                }
            }.start()
        }

        codeContainer.visibility = View.GONE
        resendContainer.visibility = View.GONE
        resendTimerText.visibility = View.GONE
        renderVerifiedEmails()

        verifyEmailButton.setOnClickListener {
            val email = emailField.text?.toString()?.trim().orEmpty()
            if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailField.error = getString(R.string.settings_auto_share_invalid_email)
                emailField.requestFocus()
                return@setOnClickListener
            }

            if (verifiedEmails.any { it.equals(email, ignoreCase = true) }) {
                Toast.makeText(this, getString(R.string.settings_auto_share_email_already_verified), Toast.LENGTH_SHORT).show()
                codeContainer.visibility = View.GONE
                resendContainer.visibility = View.GONE
                verifyEmailButton.visibility = View.VISIBLE
                return@setOnClickListener
            }

            sendVerificationCode(email)
        }

        resendButton.setOnClickListener {
            val email = emailField.text?.toString()?.trim().orEmpty()
            val enteredCode = codeField.text?.toString()?.trim().orEmpty()
            if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailField.error = getString(R.string.settings_auto_share_invalid_email)
                emailField.requestFocus()
                return@setOnClickListener
            }

            val pendingEmail = prefs.getString(KEY_AUTO_SHARE_PENDING_EMAIL, "").orEmpty()
            val pendingCode = prefs.getString(KEY_AUTO_SHARE_PENDING_CODE, "").orEmpty()
            val expiresAt = prefs.getLong(KEY_AUTO_SHARE_PENDING_EXPIRES_AT, 0L)
            val hasActiveCode = pendingCode.isNotBlank() && pendingEmail.equals(email, ignoreCase = true) && System.currentTimeMillis() < expiresAt

            if (enteredCode.isNotBlank()) {
                if (!hasActiveCode) {
                    Toast.makeText(this, getString(R.string.settings_auto_share_code_invalid), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (enteredCode != pendingCode) {
                    codeField.error = getString(R.string.settings_auto_share_code_invalid)
                    codeField.requestFocus()
                    return@setOnClickListener
                }

                verifiedEmails.removeAll { it.equals(email, ignoreCase = true) }
                verifiedEmails.add(email)
                persistVerifiedEmails()

                prefs.edit()
                    .remove(KEY_AUTO_SHARE_PENDING_CODE)
                    .remove(KEY_AUTO_SHARE_PENDING_EMAIL)
                    .remove(KEY_AUTO_SHARE_PENDING_EXPIRES_AT)
                    .apply()

                renderVerifiedEmails()
                resetEntryFields()
                setRecipientEntryVisible(false)
                Toast.makeText(this, getString(R.string.settings_auto_share_recipient_added), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cooldown = cooldownRemainingMs()
            if (cooldown > 0L) {
                startResendCooldown(cooldown)
                Toast.makeText(
                    this,
                    getString(R.string.settings_auto_share_resend_wait, (cooldown / 1000L).toInt().coerceAtLeast(1)),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            sendVerificationCode(email)
        }

        addRecipientButton.setOnClickListener {
            setRecipientEntryVisible(true)
            resetEntryFields()
            emailField.requestFocus()
        }

        emailField.doAfterTextChanged {
            val typedEmail = it?.toString()?.trim().orEmpty()
            if (typedEmail.isBlank()) {
                codeContainer.visibility = View.GONE
                resendContainer.visibility = View.GONE
                verifyEmailButton.visibility = View.VISIBLE
                return@doAfterTextChanged
            }

            val pendingEmail = prefs.getString(KEY_AUTO_SHARE_PENDING_EMAIL, "").orEmpty()
            val expiresAt = prefs.getLong(KEY_AUTO_SHARE_PENDING_EXPIRES_AT, 0L)
            val now = System.currentTimeMillis()
            val showCode = pendingEmail.equals(typedEmail, ignoreCase = true) && now < expiresAt
            codeContainer.visibility = if (showCode) View.VISIBLE else View.GONE
            resendContainer.visibility = if (showCode) View.VISIBLE else View.GONE
            verifyEmailButton.visibility = if (showCode) View.GONE else View.VISIBLE
        }

        codeField.doAfterTextChanged {
            val hasCode = !it.isNullOrBlank()
            if (codeContainer.visibility == View.VISIBLE) {
                resendButton.text = if (hasCode) {
                    getString(R.string.settings_auto_share_dialog_verify)
                } else {
                    getString(R.string.settings_auto_share_dialog_resend)
                }

                if (hasCode) {
                    resendButton.isEnabled = true
                    resendButton.alpha = 1f
                    resendTimerText.visibility = View.GONE
                } else {
                    updateResendForCooldown(cooldownRemainingMs())
                }
            }
        }

        autoShareDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        autoShareDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        cancelButton.setOnClickListener {
            resendTimer?.cancel()
            if (triggeredByToggle) {
                setAutoShareSwitchChecked(false)
            }
            autoShareDialog?.dismiss()
        }

        saveButton.setOnClickListener {
            val email = emailField.text?.toString()?.trim().orEmpty()
            val now = System.currentTimeMillis()
            val pendingCode = prefs.getString(KEY_AUTO_SHARE_PENDING_CODE, "").orEmpty()
            val pendingEmail = prefs.getString(KEY_AUTO_SHARE_PENDING_EMAIL, "").orEmpty()
            val expiresAt = prefs.getLong(KEY_AUTO_SHARE_PENDING_EXPIRES_AT, 0L)
            val enteredCode = codeField.text?.toString()?.trim().orEmpty()
            val hasActiveCode = pendingCode.isNotBlank() &&
                pendingEmail.equals(email, ignoreCase = true) &&
                now < expiresAt

            if (hasActiveCode) {
                if (enteredCode.isBlank()) {
                    codeContainer.visibility = View.VISIBLE
                    codeField.error = getString(R.string.settings_auto_share_code_required)
                    codeField.requestFocus()
                    return@setOnClickListener
                }

                if (enteredCode != pendingCode) {
                    codeField.error = getString(R.string.settings_auto_share_code_invalid)
                    codeField.requestFocus()
                    return@setOnClickListener
                }

                if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailField.error = getString(R.string.settings_auto_share_invalid_email)
                    emailField.requestFocus()
                    return@setOnClickListener
                }

                verifiedEmails.removeAll { it.equals(email, ignoreCase = true) }
                verifiedEmails.add(email)
                persistVerifiedEmails()

                prefs.edit()
                    .remove(KEY_AUTO_SHARE_PENDING_CODE)
                    .remove(KEY_AUTO_SHARE_PENDING_EMAIL)
                    .remove(KEY_AUTO_SHARE_PENDING_EXPIRES_AT)
                    .apply()

                renderVerifiedEmails()
                resetEntryFields()
                setRecipientEntryVisible(false)
                Toast.makeText(this, getString(R.string.settings_auto_share_recipient_added), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (verifiedEmails.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_auto_share_verify_one_recipient), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putBoolean(KEY_AUTO_SHARE_ENABLED, true)
                .apply()

            AutoShareScheduler.reschedule(this)
            setAutoShareSwitchChecked(true)
            updateAutoShareSummary()
            Toast.makeText(this, getString(R.string.settings_auto_share_saved), Toast.LENGTH_SHORT).show()
            resendTimer?.cancel()
            autoShareDialog?.dismiss()
        }

        autoShareDialog?.setOnDismissListener {
            resendTimer?.cancel()
            if (triggeredByToggle && !prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, false)) {
                setAutoShareSwitchChecked(false)
            }
            updateAutoShareSummary()
        }

        renderVerifiedEmails()
        setRecipientEntryVisible(verifiedEmails.isEmpty())
        startResendCooldown(cooldownRemainingMs())

        autoShareDialog?.show()
    }

    private fun showAutoShareTurnedOffDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_share_turned_off, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<MaterialButton>(R.id.auto_share_off_ok_button).setOnClickListener {
            dialog.dismiss()
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

    private fun setAutoShareSwitchChecked(checked: Boolean) {
        suppressAutoShareToggleCallback = true
        autoShareSwitch.setOn(checked)
        suppressAutoShareToggleCallback = false
    }

    private fun setupAutoShareToggle() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasRecipients = getVerifiedAutoShareEmails(prefs).isNotEmpty()
        val persistedEnabled = prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, false)
        val shouldEnable = persistedEnabled && hasRecipients

        if (persistedEnabled && !hasRecipients) {
            prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
            AutoShareScheduler.cancel(this)
        }

        setAutoShareSwitchChecked(shouldEnable)

        autoShareSwitch.setOnToggledListener { _, isChecked ->
            if (suppressAutoShareToggleCallback) return@setOnToggledListener

            if (isChecked) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                if (getVerifiedAutoShareEmails(prefs).isEmpty()) {
                    prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
                    AutoShareScheduler.cancel(this)
                    setAutoShareSwitchChecked(false)
                    updateAutoShareSummary()
                    showAutoShareMissingRecipientsDialog()
                    return@setOnToggledListener
                }
                prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, true).apply()
                AutoShareScheduler.reschedule(this)
                updateAutoShareSummary()
            } else {
                if (!prefs.getBoolean(KEY_AUTO_SHARE_DISABLE_CONFIRM_SKIP, false)) {
                    setAutoShareSwitchChecked(true)
                    showAutoShareDisableConfirmDialog()
                    return@setOnToggledListener
                }
                disableAutoShareAndNotifyRecipients()
            }
        }
    }

    private fun disableAutoShareAndNotifyRecipients() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val recipients = getVerifiedAutoShareEmails(prefs).toList()
        prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
        AutoShareScheduler.cancel(this)
        setAutoShareSwitchChecked(false)
        updateAutoShareSummary()
        if (recipients.isNotEmpty()) {
            sendAutoShareDisabledEmails(recipients)
        }
    }

    private fun showAutoShareDisableConfirmDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_share_disable_confirm, null)
        val doNotShowAgain = dialogView.findViewById<CheckBox>(R.id.auto_share_disable_do_not_show_again)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_disable_cancel_button)
        val turnOffButton = dialogView.findViewById<MaterialButton>(R.id.auto_share_disable_turn_off_button)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        cancelButton.setOnClickListener {
            setAutoShareSwitchChecked(true)
            dialog.dismiss()
        }

        turnOffButton.setOnClickListener {
            if (doNotShowAgain.isChecked) {
                prefs.edit().putBoolean(KEY_AUTO_SHARE_DISABLE_CONFIRM_SKIP, true).apply()
            }
            dialog.dismiss()
            disableAutoShareAndNotifyRecipients()
        }

        dialog.setOnCancelListener {
            setAutoShareSwitchChecked(true)
        }

        dialog.show()
    }

    private fun showAutoShareMissingRecipientsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_share_missing_recipients, null)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<MaterialButton>(R.id.auto_share_missing_cancel_button).setOnClickListener {
            prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
            AutoShareScheduler.cancel(this)
            updateAutoShareSummary()
            setAutoShareSwitchChecked(false)
            dialog.dismiss()
        }

        dialogView.findViewById<MaterialButton>(R.id.auto_share_missing_manage_button).setOnClickListener {
            prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
            AutoShareScheduler.cancel(this)
            updateAutoShareSummary()
            setAutoShareSwitchChecked(false)
            dialog.dismiss()
            showAutoShareDialog(triggeredByToggle = true)
        }

        dialog.show()
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

        findViewById<LinearLayout>(R.id.settings_auto_share_row)?.setOnLongClickListener {
            if (isVoiceReadHintsEnabled()) {
                speakSettingsText(buildAutoShareSpeech())
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

    private fun buildAutoShareSpeech(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, false)
        val verifiedEmails = getVerifiedAutoShareEmails(prefs)

        return if (!enabled) {
            getString(R.string.settings_auto_share) + ". " + getString(R.string.settings_auto_share_off)
        } else if (verifiedEmails.isEmpty()) {
            getString(R.string.settings_auto_share) + ". " + getString(R.string.settings_auto_share_pending_verification)
        } else if (verifiedEmails.size == 1) {
            getString(R.string.settings_auto_share) + ". " + verifiedEmails.first()
        } else {
            getString(R.string.settings_auto_share) + ". " +
                verifiedEmails.joinToString(separator = ", ")
        }
    }

    private fun updateAutoShareSummary() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val verifiedEmails = getVerifiedAutoShareEmails(prefs)
        val isEnabled = prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, false)

        if (verifiedEmails.isEmpty() && isEnabled) {
            prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, false).apply()
            AutoShareScheduler.cancel(this)
            setAutoShareSwitchChecked(false)
        }

        settingsAutoShareSummary.text = getString(R.string.settings_auto_share_desc)
        settingsAutoShareRecipientsCount.text = getString(
            R.string.settings_auto_share_recipients_count,
            verifiedEmails.size
        )
    }

    private fun getVerifiedAutoShareEmails(prefs: android.content.SharedPreferences): LinkedHashSet<String> {
        val ordered = prefs.getString(KEY_AUTO_SHARE_VERIFIED_EMAILS_ORDERED, null)
        if (!ordered.isNullOrBlank()) {
            return runCatching {
                val parsed = JSONArray(ordered)
                val emails = LinkedHashSet<String>()
                for (index in 0 until parsed.length()) {
                    val email = parsed.optString(index).trim()
                    if (email.isNotBlank()) {
                        emails.add(email)
                    }
                }
                emails
            }.getOrDefault(LinkedHashSet())
        }

        val set = prefs.getStringSet(KEY_AUTO_SHARE_VERIFIED_EMAILS, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toCollection(LinkedHashSet())
            ?: LinkedHashSet()

        if (set.isEmpty()) {
            val legacy = prefs.getString(KEY_AUTO_SHARE_VERIFIED_EMAIL, "").orEmpty().trim()
            if (legacy.isNotBlank()) {
                set.add(legacy)
            }
        }
        return set
    }

    private fun isAutoShareEmailVerified(prefs: android.content.SharedPreferences, email: String): Boolean {
        if (email.isBlank()) return false
        return getVerifiedAutoShareEmails(prefs).any { it.equals(email, ignoreCase = true) }
    }

    private fun generateVerificationCode(): String {
        val code = SecureRandom().nextInt(900_000) + 100_000
        return code.toString()
    }

    private fun sendVerificationEmail(recipientEmail: String, code: String): Pair<Boolean, String?> {
        val patientName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("username", getString(R.string.settings_default_name))
            .orEmpty()
        val html = buildVerificationEmailHtml(patientName, code)
        val plainText = """
            SoleMate Email Verification

            Hello,

            $patientName has added you to receive health data via SoleMate.
            Verification code: $code

            This code expires in 24 hours.
        """.trimIndent()

        return sendEmailViaBackend(recipientEmail, "SoleMate Email Verification", plainText, html)
    }

    private fun sendSharingStoppedEmail(recipientEmail: String): Pair<Boolean, String?> {
        val patientName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("username", getString(R.string.settings_default_name))
            .orEmpty()
            .ifBlank { getString(R.string.settings_default_name) }

        val safePatientName = escapeHtml(patientName)
        val html = buildSharingStoppedEmailHtml(recipientEmail, safePatientName)
        val plainText = """
            SoleMate Alert Sharing Update

            $patientName has stopped sharing health alerts with you.

            You will no longer receive email notifications when alerts are triggered.

            This is an automated message. No action is required.
        """.trimIndent()

        return sendEmailViaBackend(recipientEmail, "Alert Sharing Update - SoleMate", plainText, html)
    }

    private fun sendAutoShareDisabledEmails(recipientEmails: List<String>) {
        val cleanedRecipients = recipientEmails
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        if (cleanedRecipients.isEmpty()) return

        Thread {
            val failedRecipients = mutableListOf<String>()
            cleanedRecipients.forEach { recipientEmail ->
                val (sent, errorDetail) = sendAlertSharingDisabledEmail(recipientEmail)
                if (!sent) {
                    failedRecipients.add(recipientEmail)
                    Log.e(
                        "SendGridEmail",
                        "Failed to send disabled-sharing email to $recipientEmail: ${errorDetail.orEmpty()}"
                    )
                }
            }

            if (failedRecipients.isNotEmpty()) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Alert-sharing disabled emails failed for ${failedRecipients.size} recipient(s)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun sendAlertSharingDisabledEmail(recipientEmail: String): Pair<Boolean, String?> {
        val patientName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("username", getString(R.string.settings_default_name))
            .orEmpty()
            .ifBlank { getString(R.string.settings_default_name) }

        val safePatientName = escapeHtml(patientName)
        val html = buildAlertSharingDisabledEmailHtml(recipientEmail, safePatientName)
        val plainText = """
            Alert Sharing Disabled - SoleMate

            Hello,

            $patientName has turned off alert sharing in the SoleMate Application.

            As a result, you will no longer receive email notifications when alerts are triggered for this user.

            If you believe this change was made unintentionally, please contact the user directly.

            This is an automated message. No action is required.
        """.trimIndent()

        return sendEmailViaBackend(recipientEmail, "Alert Sharing Disabled - SoleMate", plainText, html)
    }

    private fun sendEmailViaBackend(
        recipientEmail: String,
        subject: String,
        plainText: String,
        html: String
    ): Pair<Boolean, String?> {
        val backendBaseUrl = BuildConfig.EMAIL_BACKEND_BASE_URL.trim().ifBlank { "http://10.0.2.2:3000" }.trimEnd('/')
        val sendUrl = "$backendBaseUrl/send-email"

        val backendResult = runCatching {
            val payload = JSONObject().apply {
                put("to", recipientEmail)
                put("subject", subject)
                put("text", plainText)
                put("html", html)
            }

            val connection = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..202) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
                Log.e("EmailBackend", "Backend error: $errorStream")
                val parsedMessage = runCatching {
                    val errorJson = JSONObject(errorStream)
                    errorJson.optString("error").ifBlank {
                        errorJson.optString("message")
                    }
                }.getOrNull().orEmpty().ifBlank { null }
                connection.disconnect()
                return@runCatching (false to (parsedMessage ?: "HTTP $responseCode"))
            }

            connection.disconnect()
            true to null
        }.onFailure { exception ->
            Log.e("EmailBackend", "Exception sending email via backend: ${exception.message}", exception)
        }.getOrElse { exception ->
            false to (exception.message ?: "network error")
        }

        if (backendResult.first || !BuildConfig.DEBUG) {
            return backendResult
        }

        Log.w("EmailBackend", "Backend send failed in debug build. Falling back to direct SendGrid.")
        val directResult = sendEmailViaSendGridDirect(recipientEmail, subject, plainText, html)
        if (directResult.first) {
            return directResult
        }

        val backendError = backendResult.second.orEmpty()
        val directError = directResult.second.orEmpty()
        val combinedError = listOf(backendError, directError)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .ifBlank { "send failed" }
        return false to combinedError
    }

    private fun sendEmailViaSendGridDirect(
        recipientEmail: String,
        subject: String,
        plainText: String,
        html: String
    ): Pair<Boolean, String?> {
        val apiKey = BuildConfig.SENDGRID_API_KEY.trim()
        val fromEmail = BuildConfig.SENDGRID_FROM_EMAIL.trim()
        if (apiKey.isBlank() || fromEmail.isBlank()) {
            return false to "debug fallback missing SendGrid key/from email"
        }

        return runCatching {
            val payload = JSONObject().apply {
                put("personalizations", JSONArray().put(
                    JSONObject().put("to", JSONArray().put(JSONObject().put("email", recipientEmail)))
                ))
                put("from", JSONObject().put("email", fromEmail).put("name", "SoleMate"))
                put("subject", subject)
                put("content", JSONArray().put(
                    JSONObject().put("type", "text/plain").put("value", plainText)
                ).put(
                    JSONObject().put("type", "text/html").put("value", html)
                ))
            }

            val baseUrl = BuildConfig.SENDGRID_API_BASE_URL.trim()
                .ifBlank { "https://api.sendgrid.com" }
                .trimEnd('/')
            val sendUrl = "$baseUrl/v3/mail/send"
            val connection = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..202) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
                Log.e("SendGridEmail", "Debug fallback SendGrid error: $errorStream")
                val parsedMessage = runCatching {
                    val errorJson = JSONObject(errorStream)
                    val errors = errorJson.optJSONArray("errors")
                    if (errors != null && errors.length() > 0) {
                        errors.optJSONObject(0)?.optString("message")
                    } else {
                        null
                    }
                }.getOrNull().orEmpty().ifBlank { null }
                connection.disconnect()
                return@runCatching (false to (parsedMessage ?: "HTTP $responseCode"))
            }

            connection.disconnect()
            true to null
        }.onFailure { exception ->
            Log.e("SendGridEmail", "Exception in debug fallback send: ${exception.message}", exception)
        }.getOrElse { exception ->
            false to (exception.message ?: "network error")
        }
    }

    private fun buildVerificationEmailHtml(patientName: String, code: String): String {
        val safeName = escapeHtml(patientName.ifBlank { getString(R.string.settings_default_name) })
        val safeCode = escapeHtml(code)

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Email Verification - SoleMate</title>
            </head>
            <body style="font-family: Arial, sans-serif; margin: 0; padding: 0; background-color: #f4f4f9; color: #333;">
                <div style="width: 100%; max-width: 600px; margin: 0 auto; background-color: #ffffff; padding: 30px; border-radius: 8px; box-shadow: 0 0 15px rgba(0, 0, 0, 0.1);">
                    <h1 style="color: #0E3B66; font-size: 26px; text-align: center;">Verify Your Email Address</h1>
                    <p> Hello,</p>
                    <p>We are reaching out because $safeName has added you to share health data via the SoleMate system. To ensure security, please verify your email address.</p>
                    <p>Please use the following verification code to confirm your email:</p>
                    <h2 style="text-align: center; font-size: 24px; color: #0E3B66;">$safeCode</h2>
                    <p>Once the patient enters the code in the app, you will gain access to the shared data.</p>
                    <p>If you did not request access or if this message was sent in error, please disregard this email.</p>
                    <p>Thank you for your cooperation!</p>
                    <p style="font-size: 14px; color: #555; font-style: italic;">Note: This verification code will expire in 24 hours.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildSharingStoppedEmailHtml(recipientEmail: String, safePatientName: String): String {
        val recipientLabel = escapeHtml(recipientEmail.substringBefore('@').ifBlank { "Recipient" })

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Alert Access Update - SoleMate</title>
            </head>
            <body style="font-family: Arial, sans-serif; margin: 0; padding: 0; background-color: #f4f4f9; color: #333;">
                <div style="width: 100%; max-width: 600px; margin: 0 auto; background-color: #ffffff; padding: 30px; border-radius: 8px; box-shadow: 0 0 15px rgba(0, 0, 0, 0.08);">
                    <div style="text-align: center; padding-bottom: 15px; border-bottom: 2px solid #0E3B66;">
                        <h1 style="color: #0E3B66; font-size: 24px; margin: 0;">Alert Sharing Update</h1>
                    </div>

                    <p style="font-size: 16px; line-height: 1.6;">Hello,</p>

                    <p style="font-size: 16px; line-height: 1.6;">
                        We would like to inform you that your alert access through the SoleMate system has been updated.
                    </p>

                    <div style="background-color: #f1f5f9; border-left: 4px solid #0E3B66; padding: 15px; margin: 20px 0; border-radius: 5px; font-size: 16px; line-height: 1.6;">
                        <strong>$safePatientName</strong> has stopped sharing health alerts with you.
                    </div>

                    <p style="font-size: 16px; line-height: 1.6; font-weight: bold; color: #0E3B66;">
                        You will no longer receive email notifications when alerts are triggered.
                    </p>

                    <p style="font-size: 16px; line-height: 1.6;">
                        If this change was unexpected or you believe you should still receive alerts, please contact the patient directly.
                    </p>

                    <p style="font-size: 13px; color: #666; margin-top: 20px; text-align: center;">
                        This is an automated message. No action is required.
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildAlertSharingDisabledEmailHtml(recipientEmail: String, safePatientName: String): String {
        val recipientLabel = escapeHtml(recipientEmail.substringBefore('@').ifBlank { "Recipient" })

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Alert Sharing Disabled - SoleMate</title>
            </head>
            <body style="font-family: Arial, sans-serif; margin: 0; padding: 0; background-color: #f4f4f9; color: #333;">
                <div style="width: 100%; max-width: 600px; margin: 0 auto; background-color: #ffffff; padding: 30px; border-radius: 8px; box-shadow: 0 0 15px rgba(0, 0, 0, 0.08);">
                    <div style="text-align: center; padding-bottom: 15px; border-bottom: 2px solid #0E3B66;">
                        <h1 style="color: #0E3B66; font-size: 24px; margin: 0;">Alert Sharing Disabled</h1>
                    </div>

                    <p style="font-size: 16px; line-height: 1.6;">Hello,</p>

                    <p style="font-size: 16px; line-height: 1.6;">
                        We would like to inform you that <strong>$safePatientName</strong> has turned off alert sharing in the SoleMate Application.
                    </p>

                    <div style="background-color: #f1f5f9; border-left: 4px solid #0E3B66; padding: 15px; margin: 20px 0; border-radius: 5px; font-size: 16px; line-height: 1.6;">
                        As a result, you will no longer receive email notifications when alerts are triggered for this user.
                    </div>

                    <p style="font-size: 16px; line-height: 1.6;">
                        If you believe this change was made unintentionally, please contact the user directly.
                    </p>

                    <p style="font-size: 13px; color: #666; margin-top: 20px; text-align: center;">
                        This is an automated message. No action is required.
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
