package com.example.ble_viewer

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LanguagePickerHelper {

    private const val EXTRA_LANGUAGE_TRANSITION = "extra_language_transition"

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

    fun show(activity: Activity, onLanguageChanged: (() -> Unit)? = null) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_language_picker, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        val currentTag = currentLanguageTag()

        languageOptions.forEach { option ->
            dialogView.findViewById<TextView>(option.flagViewId).text = flagEmojiFor(option.tag)
            dialogView.findViewById<TextView>(option.labelViewId).text = activity.getString(option.labelRes)

            styleLanguageOption(dialogView, option, option.tag == currentTag)

            dialogView.findViewById<View>(option.optionViewId).setOnClickListener {
                if (option.tag == currentTag) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                val slideDownExit = AnimationUtils.loadAnimation(activity, R.anim.slide_down_exit)
                dialogView.startAnimation(slideDownExit)
                slideDownExit.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}

                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        dialog.dismiss()
                        applyLanguage(activity, option.tag, onLanguageChanged)
                    }

                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun applyLanguage(activity: Activity, languageTag: String, onLanguageChanged: (() -> Unit)?) {
        val content = activity.findViewById<View>(android.R.id.content)
        content.animate()
            .alpha(0f)
            .setDuration(240L)
            .withEndAction {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
                val restartIntent = Intent(activity, activity::class.java).apply {
                    putExtra(EXTRA_LANGUAGE_TRANSITION, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity.startActivity(restartIntent)
                activity.overridePendingTransition(0, 0)
                activity.finish()
                onLanguageChanged?.invoke()
            }
            .start()
    }

    fun consumeTransitionFlag(activity: Activity): Boolean {
        return activity.intent.getBooleanExtra(EXTRA_LANGUAGE_TRANSITION, false)
    }

    private fun currentLanguageTag(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val tag = appLocales.toLanguageTags().takeIf { it.isNotBlank() }
            ?: java.util.Locale.getDefault().toLanguageTag()
        return tag.substringBefore('-').lowercase(java.util.Locale.ROOT)
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
            "ar" -> "\uD83C\uDDF1\uD83C\uDDE7"
            "fr" -> "\uD83C\uDDEB\uD83C\uDDF7"
            else -> "\uD83C\uDF10"
        }
    }
}