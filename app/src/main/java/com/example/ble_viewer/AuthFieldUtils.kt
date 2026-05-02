package com.example.ble_viewer

import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.addTextChangedListener

object AuthFieldUtils {

    fun showError(editText: EditText, errorView: TextView?, message: String) {
        editText.setBackgroundResource(R.drawable.auth_input_outline_error)
        errorView?.text = message
        errorView?.visibility = android.view.View.VISIBLE
    }

    fun clearError(editText: EditText, errorView: TextView?) {
        editText.setBackgroundResource(R.drawable.auth_input_outline)
        errorView?.text = ""
        errorView?.visibility = android.view.View.GONE
    }

    fun bindClearOnChange(editText: EditText, errorView: TextView?) {
        editText.addTextChangedListener {
            if (!it.isNullOrBlank()) {
                clearError(editText, errorView)
            }
        }
    }
}
