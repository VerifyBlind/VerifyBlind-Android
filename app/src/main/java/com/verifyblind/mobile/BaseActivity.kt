package com.verifyblind.mobile

import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    /**
     * Bilgilendirme diyaloğu.
     *
     * [actionLabel]/[onAction] verilirse diyalog "ne yapılacağını anlatan" değil "yaptıran" bir
     * diyaloğa dönüşür: eylem POZİTİF butona geçer, kapatma negatife düşer. Kullanıcının
     * uygulamadan çıkıp doğru ayar ekranını kendi bulması gereken durumlar (ekran kilidi
     * kurulumu gibi) akışın orada bitmesine yol açıyordu.
     *
     * [onDismiss] son parametre olarak KALMALI — mevcut çağıranlar onu trailing lambda ile
     * geçiyor.
     */
    fun showMessage(
        title: String,
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        runOnUiThread {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)

            if (actionLabel != null && onAction != null) {
                builder.setPositiveButton(actionLabel) { _, _ -> onAction() }
                builder.setNegativeButton(getString(R.string.common_ok)) { _, _ -> onDismiss?.invoke() }
            } else {
                builder.setPositiveButton(getString(R.string.common_ok)) { _, _ -> onDismiss?.invoke() }
            }

            builder.show()
        }
    }
}
