package com.verifyblind.mobile.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * MRZ tarama ekranı için köşe braketleri çizen özel View.
 * Tam dikdörtgen çerçeve yerine sadece 4 köşeyi gösterir.
 */
class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokeWidthPx = 4f * resources.displayMetrics.density
    private val cornerLengthPx = 28f * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF")
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cl = cornerLengthPx
        val sw = strokeWidthPx / 2f

        // Üst-sol köşe
        canvas.drawLine(sw, sw, sw + cl, sw, paint)
        canvas.drawLine(sw, sw, sw, sw + cl, paint)

        // Üst-sağ köşe
        canvas.drawLine(w - sw - cl, sw, w - sw, sw, paint)
        canvas.drawLine(w - sw, sw, w - sw, sw + cl, paint)

        // Alt-sol köşe
        canvas.drawLine(sw, h - sw, sw + cl, h - sw, paint)
        canvas.drawLine(sw, h - sw - cl, sw, h - sw, paint)

        // Alt-sağ köşe
        canvas.drawLine(w - sw - cl, h - sw, w - sw, h - sw, paint)
        canvas.drawLine(w - sw, h - sw - cl, w - sw, h - sw, paint)
    }
}
