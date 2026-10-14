package com.verifyblind.mobile.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.verifyblind.mobile.R

/**
 * Marka logosunu hafif gölgesiyle (y=6dp, yumuşaklık ~5dp) verilen yükseklikte çizer.
 * İşlem (`BrandPulseView`) ve başarı (`BrandSuccessView`) ekranları aynı görünümü paylaşsın diye tek yerde.
 */
internal class BrandLogoPainter(resources: Resources, heightDp: Float) {

    private val density = resources.displayMetrics.density

    // inScaled=false: drawable/ (mdpi) kaynağı yoğunluğa göre 3-4 kat büyütülüp bellekte şişmesin;
    // ölçeklemeyi çizerken kendimiz yapıyoruz.
    private val logo: Bitmap = BitmapFactory.decodeResource(
        resources, R.drawable.logo, BitmapFactory.Options().apply { inScaled = false }
    )
    private val heightPx = heightDp * density
    private val scale = heightPx / logo.height

    // Gölge logonun kendi şeklinden, kaynak piksellerinde bir kez hesaplanır.
    private val shadowOffset = IntArray(2)
    private val shadow: Bitmap = logo.extractAlpha(
        Paint().apply { maskFilter = BlurMaskFilter(7.8f * density / scale, BlurMaskFilter.Blur.NORMAL) },
        shadowOffset
    )
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(56, 15, 23, 42) }
    private val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, cx: Float, cy: Float) {
        canvas.save()
        canvas.translate(cx - logo.width * scale / 2f, cy - heightPx / 2f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(shadow, shadowOffset[0].toFloat(), shadowOffset[1] + 6f * density / scale, shadowPaint)
        canvas.drawBitmap(logo, 0f, 0f, logoPaint)
        canvas.restore()
    }
}
