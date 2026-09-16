package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Anti-spoof kırpması — **üreticinin referans kodunun birebir aynısı**.
 *
 * ## Neden bu dosya var
 *
 * Bizim eski kırpmamız üreticininkiyle aynı DEĞİLDİ ve bu sessiz bir hataydı:
 *
 * ```
 * üretici:  kırpma kadrajdan taşarsa kutuyu İÇERİ KAYDIR  → her zaman TAM ölçek
 * bizimki:  taşarsa KES                                    → daha küçük, merkezden kaymış
 * ```
 *
 * Fark yüz kadrajda büyükken ya da kenardayken ortaya çıkıyor — yani tam olarak yakın
 * mesafede ve monitör denemelerinde. Model o durumlarda eğitildiğinden farklı bir
 * çerçeveleme görüyordu.
 *
 * Üretici ayrıca ölçeği kadraja SIĞACAK şekilde sınırlıyor
 * (`scale = min((h-1)/box_h, (w-1)/box_w, scale)`); biz bunu hiç yapmıyorduk.
 *
 * Kaynak: minivision-ai/Silent-Face-Anti-Spoofing, `src/generate_patches.py` → `_get_new_box`.
 *
 * ⚠️ **Ulaşılan ölçek de döndürülür.** Yüz kadrajda büyükse istenen 4,0 elde edilemez ve
 * sıkıştırılır; bunu kaydetmezsek toplanan veriyi yorumlayamayız — "4,0 işe yaramadı" mı
 * yoksa "hiç 4,0 besleyemedik" mi ayırt edilemez.
 */
object AntiSpoofCrop {

    /** Üreticinin iki ölçeği. 2,7 kapıyı besler, 4,0 yalnız ölçüm içindir. */
    const val SCALE_27 = 2.7f
    const val SCALE_40 = 4.0f

    /** Modelin girdi boyu. */
    const val OUTPUT = 80

    data class Result(
        val bitmap: Bitmap,
        /** Gerçekte uygulanabilen ölçek — istenenden küçük olabilir (kadraja sığmadıysa). */
        val achievedScale: Float,
    )

    /**
     * [faceBox] çevresinden [scale] katı bir bölge kırpar, [OUTPUT]×[OUTPUT]'a indirir.
     *
     * Taşma durumunda kutu kesilmez, kadrajın içine KAYDIRILIR — üreticinin davranışı.
     * Kırpılamayacak kadar küçük bir kaynakta `null` döner.
     */
    fun crop(source: Bitmap, faceBox: Rect, scale: Float): Result? {
        val srcW = source.width
        val srcH = source.height
        val boxW = faceBox.width().toFloat()
        val boxH = faceBox.height().toFloat()
        if (boxW <= 0f || boxH <= 0f || srcW < 2 || srcH < 2) return null

        // Üreticiyle aynı sınırlama: istenen ölçek kadraja sığmıyorsa küçültülür.
        val applied = min(min((srcH - 1) / boxH, (srcW - 1) / boxW), scale)
        if (applied <= 0f) return null

        val newW = boxW * applied
        val newH = boxH * applied
        val cx = faceBox.left + boxW / 2f
        val cy = faceBox.top + boxH / 2f

        var left = cx - newW / 2f
        var top = cy - newH / 2f
        var right = cx + newW / 2f
        var bottom = cy + newH / 2f

        // ⚠️ KES DEĞİL KAYDIR: taşan kenar kadar kutunun tamamı içeri itilir, boyut korunur.
        if (left < 0f) { right -= left; left = 0f }
        if (top < 0f) { bottom -= top; top = 0f }
        if (right > srcW - 1f) { left -= right - (srcW - 1f); right = srcW - 1f }
        if (bottom > srcH - 1f) { top -= bottom - (srcH - 1f); bottom = srcH - 1f }

        val x = left.toInt().coerceIn(0, srcW - 1)
        val y = top.toInt().coerceIn(0, srcH - 1)
        val w = (right.toInt() - x + 1).coerceIn(1, srcW - x)
        val h = (bottom.toInt() - y + 1).coerceIn(1, srcH - y)
        if (w < 8 || h < 8) return null

        var patch: Bitmap? = null
        return try {
            patch = Bitmap.createBitmap(source, x, y, w, h)
            val scaled = Bitmap.createScaledBitmap(patch, OUTPUT, OUTPUT, true)
            if (scaled !== patch) patch.recycle()
            Result(scaled, applied)
        } catch (e: Exception) {
            patch?.recycle()
            null
        }
    }

    /** Ulaşılan ölçeği ölçüm satırına sığacak tam sayıya çevirir (4,0 → 400). */
    fun scaleToMetric(achieved: Float): Int = (achieved * 100f).roundToInt()
}
