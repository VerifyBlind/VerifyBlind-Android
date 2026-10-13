package com.verifyblind.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.verifyblind.mobile.R

/**
 * İşlem ("Lütfen Bekleyiniz") ekranının ortasındaki marka logosu; arkasında iki soluk halka sırayla
 * yayılıp kaybolur. Logo kıpırdamaz — yalnız halkalar, ekranın çalıştığını belli etmek için.
 *
 * Animasyon yalnız görünürken döner: ViewFlipper başka çocuğa geçince, akış ekranı kapanınca ya da
 * uygulama arka plana gidince kendiliğinden durur. Bu yüzden ekranı açıp kapatan onca yere
 * start/stop çağrısı eklemek gerekmiyor.
 *
 * Ölçüler iOS `BrandPulseLogo` ile aynı: logo 104dp yükseklik, halka çapı 112dp, ölçek 0.7→1.45,
 * opaklık 0.9→0, 2.4 sn, ikinci halka yarım tur geriden.
 */
class BrandPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    // inScaled=false: drawable/ (mdpi) kaynağı yoğunluğa göre 3-4 kat büyütülüp bellekte şişmesin;
    // ölçeklemeyi çizerken kendimiz yapıyoruz.
    private val logo: Bitmap = BitmapFactory.decodeResource(
        resources, R.drawable.logo, BitmapFactory.Options().apply { inScaled = false }
    )
    private val logoHeightPx = 104f * density
    private val logoScale = logoHeightPx / logo.height

    // Hafif gölge (y=6dp, yumuşaklık ~5dp): logonun kendi şeklinden, kaynak piksellerinde bir kez hesaplanır.
    private val shadowOffset = IntArray(2)
    private val shadow: Bitmap = logo.extractAlpha(
        Paint().apply { maskFilter = BlurMaskFilter(7.8f * density / logoScale, BlurMaskFilter.Blur.NORMAL) },
        shadowOffset
    )
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(56, 15, 23, 42) }
    private val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringBaseRadius = 56f * density
    private val ease = PathInterpolator(0.2f, 0.6f, 0.3f, 1f)

    private var animator: ValueAnimator? = null
    private var phase = 0f

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

        if (ValueAnimator.areAnimatorsEnabled()) {
            drawRing(canvas, cx, cy, phase)
            drawRing(canvas, cx, cy, (phase + 0.5f) % 1f)
        } else {
            // Sistem animasyonları kapalıysa (erişilebilirlik): tek, durağan halka.
            ringPaint.color = Color.argb(20, 0, 102, 204)
            canvas.drawCircle(cx, cy, ringBaseRadius, ringPaint)
        }

        canvas.save()
        canvas.translate(cx - logo.width * logoScale / 2f, cy - logoHeightPx / 2f)
        canvas.scale(logoScale, logoScale)
        canvas.drawBitmap(shadow, shadowOffset[0].toFloat(), shadowOffset[1] + 6f * density / logoScale, shadowPaint)
        canvas.drawBitmap(logo, 0f, 0f, logoPaint)
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, p: Float) {
        val t = ease.getInterpolation(p)
        // Halka rengi #0066CC, %16 opaklık; üstüne 0.9→0 sönme.
        ringPaint.color = Color.argb((41 * 0.9f * (1f - t)).toInt(), 0, 102, 204)
        canvas.drawCircle(cx, cy, ringBaseRadius * (0.7f + 0.75f * t), ringPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldRun && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!shouldRun) {
            stopAnimation()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }
}
