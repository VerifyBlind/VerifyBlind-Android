package com.verifyblind.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * İşlem ("Lütfen Bekleyiniz") ekranının ortasındaki marka logosu; arkasında iki soluk halka sırayla
 * yayılıp kaybolur. Logo kıpırdamaz — yalnız halkalar, ekranın çalıştığını belli etmek için.
 *
 * Halkalar saate göre çizilir (iOS `TimelineView` gibi), `ValueAnimator` ile değil: animatör sistem
 * "animasyon ölçeği"ne bağlı ve bazı telefonlarda (pil tasarrufu, üretici ayarları) bu 0'a çekilince
 * halkalar donuyordu. Her kare bir sonrakini ister; görünüm çizilmediği an (ViewFlipper başka ekrana
 * geçti, uygulama arka planda) zincir kendiliğinden kopar, görünür olunca ilk çizimle yeniden başlar.
 * Bu yüzden ekranı açıp kapatan yerlere start/stop çağrısı gerekmiyor.
 *
 * Ölçüler iOS `BrandPulseLogo` ile aynı: logo 104dp yükseklik, halka çapı 112dp, ölçek 0.7→1.45,
 * opaklık 0.9→0, 2.4 sn, ikinci halka yarım tur geriden.
 */
class BrandPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val logo = BrandLogoPainter(resources, 104f)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringBaseRadius = 56f * density
    private val ease = PathInterpolator(0.2f, 0.6f, 0.3f, 1f)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // En büyük halka (56dp × 1.45) sığsın diye 164dp kare.
        val size = (164 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        val phase = (SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat()
        drawRing(canvas, cx, cy, phase)
        drawRing(canvas, cx, cy, (phase + 0.5f) % 1f)
        logo.draw(canvas, cx, cy)

        postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, p: Float) {
        val t = ease.getInterpolation(p)
        // Halka rengi #0066CC, %16 opaklık; üstüne 0.9→0 sönme.
        ringPaint.color = Color.argb((41 * 0.9f * (1f - t)).toInt(), 0, 102, 204)
        canvas.drawCircle(cx, cy, ringBaseRadius * (0.7f + 0.75f * t), ringPaint)
    }

    private companion object {
        const val PERIOD_MS = 2400L
    }
}
