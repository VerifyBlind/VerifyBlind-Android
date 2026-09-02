package com.verifyblind.mobile.util

import android.os.Build
import java.util.Locale

/**
 * İşlem geçmişinde saklanan pazarlama model adı. Android'de gerçek pazarlama adı (ör. "Galaxy S23")
 * için yerleşik API yok → MANUFACTURER + MODEL kullanılır (üretici baş harfi büyük). Ör:
 * "Google Pixel 7", "Samsung SM-S911B". Ad bir işlem yapıldığında yakalanıp geçmiş kaydında şifreli
 * saklanır; cihazlar arası senkronda orijin cihaz adı korunur.
 */
object DeviceInfo {

    fun marketingName(): String {
        val manufacturer = capitalize(Build.MANUFACTURER?.trim().orEmpty())
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android"
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            // Model zaten üretici adıyla başlıyorsa tekrarlama (ör. "Pixel 7" + "Google").
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private fun capitalize(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}
