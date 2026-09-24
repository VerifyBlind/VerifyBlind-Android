package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ARKA PLAN DOKUSU — "ORB'un eşleştireceği bir desen var mı".
 *
 * Parallaks adımından ([ParallaxCollector]) çıkarıldı; duruş dizisi ([StanceCollector]) aynı
 * ölçüyü kullanıyor. Tek yerde durmalı: eşik (14) bu ölçüyle kalibre edildi ve sunucu da
 * mesaj seçimini bu sayıya bakarak yapıyor. İki kopya sessizce ayrışırsa eşik anlamını yitirir.
 *
 * ⚠️ Bu bir sahtecilik ölçüsü DEĞİL, ölçülebilirlik ölçüsü. Monitör düzeneği kalibrasyonda
 * listenin en yüksek dokusunu aldı.
 */
object BackgroundTexture {

    /**
     * Yüz DIŞINDAKİ bölgenin gradyan enerjisi.
     *
     * @param keepFraction örneklenecek MERKEZ pencerenin kenar oranı (1 = tüm kare).
     * @param grow yüz kutusunun kaç katının dışlanacağı. Uzak karede 1,6 (kalibrasyon ölçüsü);
     * yakın karede yüz kadrajın büyük kısmını kapladığı için 1,15 — yoksa geriye yalnız üst ve
     * alt şeritler kalır ve ölçülen şey arka plan değil saç ile giysi olur.
     *
     * Seyrek örnekleme: tam çözünürlükte her pikseli okumak kare başına milyonlarca işlem
     * demek; desen ölçmek için gerek yok.
     */
    fun measure(full: Bitmap, faceBox: Rect, keepFraction: Float = 1f, grow: Float = 1.6f): Float {
        val w = full.width
        val h = full.height
        val step = max(2, max(w, h) / 160)
        val cx = faceBox.exactCenterX()
        val cy = faceBox.exactCenterY()
        val hw = faceBox.width() * grow / 2f
        val hh = faceBox.height() * grow / 2f

        val keepW = (w * keepFraction).toInt().coerceIn(1, w)
        val keepH = (h * keepFraction).toInt().coerceIn(1, h)
        val xStart = max((w - keepW) / 2, step)
        val yStart = max((h - keepH) / 2, step)
        val xEnd = min((w + keepW) / 2, w - step)
        val yEnd = min((h + keepH) / 2, h - step)

        var sum = 0.0
        var n = 0
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                if (!(x > cx - hw && x < cx + hw && y > cy - hh && y < cy + hh)) {
                    val c = lum(full.getPixel(x, y))
                    val gx = lum(full.getPixel(x + step, y)) - c
                    val gy = lum(full.getPixel(x, y + step)) - c
                    sum += (abs(gx) + abs(gy)).toDouble()
                    n++
                }
                x += step
            }
            y += step
        }
        // 🔴 n küçükse "doku yok" DEMEZ, "ölçemedik" der — ama ikisi de aynı dala düşüyor.
        return if (n < 50) 0f else (sum / n).toFloat()
    }

    private fun lum(p: Int): Int =
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
}
