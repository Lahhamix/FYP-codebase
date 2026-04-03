package com.example.ble_viewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private var pendingLanguageTransition = false
        private const val EXTRA_LANGUAGE_TRANSITION = "extra_language_transition"
        const val EXTRA_SCROLL_TO_RESOURCES = "extra_scroll_to_resources"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }

    private var suppressFinishAnimation = false

    private lateinit var settingsUsername: TextView
    private lateinit var settingsEmail: TextView
    private lateinit var languageValue: TextView
    private lateinit var settingsScrollView: View
    private lateinit var textSizeSlider: SeekBar
    private lateinit var textSizeValue: TextView
    private lateinit var biometricSwitch: MaterialSwitch

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
        biometricSwitch = findViewById(R.id.settings_biometric_switch)
        updateUsername()
        updateLanguageChip()
        setupBiometricToggle()
        setupTextSizeControls()

        if (intent.getBooleanExtra(EXTRA_SCROLL_TO_RESOURCES, false)) {
            settingsScrollView.post {
                val resourcesSection = findViewById<View>(R.id.settings_resources_section)
                (settingsScrollView as android.widget.ScrollView).smoothScrollTo(0, resourcesSection.top)
            }
        }

        findViewById<Button>(R.id.button_edit_profile).setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_edit_profile_coming_soon), Toast.LENGTH_SHORT).show()
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

        findViewById<LinearLayout>(R.id.settings_language_row).setOnClickListener {
            showLanguagePicker()
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

    private fun setupBiometricToggle() {
        val prefs = getSharedPreferences("SolematePrefs", MODE_PRIVATE)
        biometricSwitch.isChecked = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        biometricSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !BiometricAuthHelper.isAvailable(this)) {
                biometricSwitch.isChecked = false
                prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
                Toast.makeText(this, getString(R.string.toast_biometric_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, isChecked).apply()
        }
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
