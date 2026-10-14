package com.verifyblind.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.verifyblind.mobile.R

/**
 * Kart eklendi ekranının logosu: işlem ekranında dalgalanan aynı logo yerinde kalır, sağ altına yeşil
 * onay rozeti oturur ve tek bir yeşil halka bir kez yayılır. Sonra ekran sakin kalır.
 *
 * `BrandPulseView` gibi saate göre çizilir (sistem animasyon ölçeğinden bağımsız). Animasyon ekran
 * her gösterildiğinde baştan oynar; ~1 sn sonra kare istemeyi bırakır.
 *
 * Ölçüler iOS `BrandSuccessLogo` ile aynı: 164dp kare, logo 120dp yükseklik, rozet 40dp
 * (merkezden +32/+40dp), rozet 0.45 sn yaylanarak büyür, halka 0.1 sn gecikmeyle 0.9 sn'de 0.8→1.35.
 */
class BrandSuccessView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val logo = BrandLogoPainter(resources, 120f)

    private val green = ContextCompat.getColor(context, R.color.success)
    private val background = ContextCompat.getColor(context, R.color.sv_background)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val badgeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = background
        // API 28 altında donanım tuvali şekil gölgesini yok sayar; rozet gölgesiz çizilir, sorun değil.
        setShadowLayer(4f * density, 0f, 3f * density, Color.argb(89, 16, 185, 129))
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f * (22f / 24f) * density
    }
    // 24 birimlik ikon alanında M5,12.5 L9.5,17 L19,7.5 — rozet merkezine göre, 22dp'ye ölçekli.
    private val checkPath = Path().apply {
        val u = 22f / 24f * density
        moveTo((5f - 12f) * u, (12.5f - 12f) * u)
        lineTo((9.5f - 12f) * u, (17f - 12f) * u)
        lineTo((19f - 12f) * u, (7.5f - 12f) * u)
    }

    private val popEase = PathInterpolator(0.3f, 1.6f, 0.5f, 1f)
    private val ringEase = PathInterpolator(0.2f, 0.6f, 0.3f, 1f)

    /** İlk çizimin zamanı; 0 = henüz gösterilmedi (bir sonraki çizim animasyonu başlatır). */
    private var startedAt = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (164 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Ekran gizlenince sıfırla: bir sonraki kart eklemede animasyon yeniden oynasın.
        if (!isShown) startedAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (startedAt == 0L) startedAt = now
        val elapsed = now - startedAt

        val cx = width / 2f
        val cy = height / 2f

        // Tek seferlik yeşil halka: 100 ms gecikme, 900 ms.
        val rp = ((elapsed - 100) / 900f).coerceIn(0f, 1f)
        if (rp > 0f && rp < 1f) {
            val t = ringEase.getInterpolation(rp)
            ringPaint.color = (green and 0x00FFFFFF) or ((0.8f * (1f - t) * 255).toInt() shl 24)
            canvas.drawCircle(cx, cy, (56f * density - ringPaint.strokeWidth / 2f) * (0.8f + 0.55f * t), ringPaint)
        }

        logo.draw(canvas, cx, cy)

        // Rozet: 450 ms'de 0'dan yaylanarak tam boya.
        val badgeScale = popEase.getInterpolation((elapsed / 450f).coerceIn(0f, 1f))
        if (badgeScale > 0f) {
            canvas.save()
            canvas.translate(cx + 32f * density, cy + 40f * density)
            canvas.scale(badgeScale, badgeScale)
            canvas.drawCircle(0f, 0f, 20f * density, badgeRimPaint)
            canvas.drawCircle(0f, 0f, 17f * density, badgeFillPaint)
            canvas.drawPath(checkPath, checkPaint)
            canvas.restore()
        }

        if (elapsed < 1000) postInvalidateOnAnimation()
    }
}
