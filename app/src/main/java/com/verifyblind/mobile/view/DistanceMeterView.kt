package com.verifyblind.mobile.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln

/**
 * MESAFE GÖSTERGESİ — duruş dizisinde "ne kadar yaklaşmalıyım" sorusunun cevabı.
 *
 * Yatay bir çubuk: sol uzak, sağ yakın. Hedef bant yeşil bölge, yüzün şu anki konumu beyaz
 * işaret. Kullanıcı işareti yeşil bölgeye getirir.
 *
 * **Neden oval yetmedi (2026-09-24 saha testi):** kamera önizlemesi kırpılarak gösteriliyor ve
 * analiz karesiyle önizlemenin en-boy oranı farklı. Ekrandaki ovali dolduran yüz, kodun istediği
 * yüz/kadraj oranında OLMAYABİLİYOR — kullanıcı ovale uyuyor, ekran "yaklaştırın / uzaklaştırın"
 * demeye devam ediyordu. Gösterge önizleme geometrisinden bağımsız: doğrudan ölçülen oranı çizer.
 *
 * Ölçek logaritmik: mesafe değişimi çarpımsal (uzak→yakın 2×), eşit adımlar eşit aralık görünsün.
 */
class DistanceMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val scaleMin = 0.15f
    private val scaleMax = 0.85f

    private var bandMin = 0f
    private var bandMax = 0f
    private var current = -1f
    private var inBand = false
    private var leftLabel = ""
    private var rightLabel = ""

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99000000") }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B34CAF50") }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#222222")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val rect = RectF()

    fun setLabels(left: String, right: String) {
        leftLabel = left
        rightLabel = right
        invalidate()
    }

    /** Hedef bant (yüz/kadraj oranı). */
    fun setTarget(min: Float, max: Float) {
        if (bandMin != min || bandMax != max) {
            bandMin = min
            bandMax = max
            invalidate()
        }
    }

    /** Şu anki oran; −1 = yüz yok (işaret gizlenir). */
    fun setCurrent(fraction: Float) {
        val nowInBand = fraction in bandMin..bandMax
        if (current != fraction || inBand != nowInBand) {
            current = fraction
            inBand = nowInBand
            invalidate()
        }
    }

    private fun xOf(fraction: Float, left: Float, width: Float): Float {
        val f = fraction.coerceIn(scaleMin, scaleMax)
        val t = (ln(f) - ln(scaleMin)) / (ln(scaleMax) - ln(scaleMin))
        return left + t * width
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 14f * density
        val labelH = labelPaint.textSize + 4f * density
        val trackH = 10f * density
        val left = pad
        val width = this.width - 2 * pad
        val cy = labelH + (height - labelH) / 2f

        rect.set(left, cy - trackH / 2, left + width, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)

        if (bandMax > bandMin) {
            rect.set(xOf(bandMin, left, width), cy - trackH / 2, xOf(bandMax, left, width), cy + trackH / 2)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, bandPaint)
        }

        canvas.drawText(leftLabel, left, labelH - 4f * density, labelPaint)
        val rw = labelPaint.measureText(rightLabel)
        canvas.drawText(rightLabel, left + width - rw, labelH - 4f * density, labelPaint)

        if (current > 0f) {
            val x = xOf(current, left, width)
            val r = 8f * density
            markerPaint.color = if (inBand) Color.parseColor("#4CAF50") else Color.WHITE
            canvas.drawCircle(x, cy, r, markerPaint)
            canvas.drawCircle(x, cy, r, markerStroke)
        }
    }
}
