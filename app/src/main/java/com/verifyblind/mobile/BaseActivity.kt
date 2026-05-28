package com.verifyblind.mobile

import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    fun showMessage(title: String, message: String, onDismiss: (() -> Unit)? = null) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                    onDismiss?.invoke()
                }
                .setCancelable(false)
                .show()
        }
    }
}
