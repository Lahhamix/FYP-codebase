package com.example.ble_viewer

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object TextSizeScaleManager {

    const val PREFS_NAME = "SolematePrefs"
    const val KEY_TEXT_SIZE_MODE = "pref_text_size_mode"
    const val MODE_STANDARD = 0
    const val MODE_LARGE = 1
    const val MODE_EXTRA_LARGE = 2

    fun getMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TEXT_SIZE_MODE, MODE_STANDARD)
            .coerceIn(MODE_STANDARD, MODE_EXTRA_LARGE)
    }

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TEXT_SIZE_MODE, mode.coerceIn(MODE_STANDARD, MODE_EXTRA_LARGE))
            .apply()
    }

    fun labelResForMode(mode: Int): Int {
        return when (mode.coerceIn(MODE_STANDARD, MODE_EXTRA_LARGE)) {
            MODE_LARGE -> R.string.settings_text_size_large
            MODE_EXTRA_LARGE -> R.string.settings_text_size_extra_large
            else -> R.string.settings_text_size_standard
        }
    }

    fun scaleForMode(mode: Int): Float {
        return when (mode.coerceIn(MODE_STANDARD, MODE_EXTRA_LARGE)) {
            MODE_LARGE -> 1.15f
            MODE_EXTRA_LARGE -> 1.3f
            else -> 1f
        }
    }

    fun applyTo(activity: Activity) {
        applyTo(activity.findViewById(android.R.id.content), scaleForMode(getMode(activity)))
    }

    fun applyTo(activity: Activity, mode: Int) {
        applyTo(activity.findViewById(android.R.id.content), scaleForMode(mode))
    }

    fun applyTo(root: View, scale: Float) {
        if (root is TextView) {
            scaleTextView(root, scale)
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyTo(root.getChildAt(index), scale)
            }
        }
    }

    private fun scaleTextView(textView: TextView, scale: Float) {
        val baseSizePx = (textView.getTag(R.id.tag_base_text_size_sp) as? Float)
            ?: textView.textSize.also { textView.setTag(R.id.tag_base_text_size_sp, it) }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSizePx * scale)
    }
}