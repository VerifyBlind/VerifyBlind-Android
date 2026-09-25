package com.verifyblind.mobile.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Kamera önizlemesinin üstündeki yüz çerçevesi: beyaz maske, köşeleri yuvarlatılmış dikdörtgen
 * bir pencere, pencerenin köşelerinde durum renginde işaretler.
 *
 * ## Neden oval DEĞİL (2026-09-25)
 *
 * Eskiden pencere ovaldi. Özçekim arayüzünde yüzü oval bir çerçeveyle çevreleyen tasarımın ABD
 * tasarım patenti var (FaceTec). Kullanıcı kararı: bilinen bir patentin üstünde durulmaz. Aynı
 * sebeple TEK boyut var — farklı büyüklükte çerçeveler gösterip kullanıcıyı yaklaştırıp
 * uzaklaştırmak da patentli bir akış; eski SIZE_SMALL/LARGE/MEDIUM kaldırıldı.
 */
class FaceFrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STATE_WAITING = 0
        const val STATE_ALIGNED = 1
        const val STATE_HIDDEN = 2

        /** Kalan sürenin bu oranın altında kalması = kehribar çizgi (iOS lowTimeFraction paritesi). */
        const val LOW_TIME_FRACTION = 0.27f

        /** Pencere genişliğinin view genişliğine oranı ve en-boy oranı (4:5, portre). */
        private const val WIDTH_FRACTION = 0.78f
        private const val ASPECT = 1.25f
        private const val CORNER_FRACTION = 0.10f
        private const val BRACKET_FRACTION = 0.16f

        private val COLOR_WAITING = Color.parseColor("#FF4444")
        private val COLOR_ALIGNED = Color.parseColor("#4CAF50")
        private val COLOR_TRACK = Color.parseColor("#E0E0E0")
        private val COLOR_LOW_TIME = Color.parseColor("#F29B12")
    }

    private var currentState = STATE_WAITING

    /** Kalan süre (0..1); negatif → süre çizilmez. */
    private var timeProgress = -1f

    private val density = resources.displayMetrics.density

    private val maskPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val progressPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val bracketPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val window = RectF()
    private val windowPath = Path()
    private val progressPath = Path()
    private val brackets = Path()
    private val measure = PathMeasure()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Pencere view'in KENDİ kutusuna sığdırılır: view talimat metinleriyle alt bilgi satırı
        // arasındaki bant; kısa ekranlarda genişlik oranı tek başına pencereyi bandın dışına
        // taşırdı (oval döneminde yaşandı, 2026-08-21).
        val maxByHeight = h * 0.94f / ASPECT
        val ww = minOf(w * WIDTH_FRACTION, maxByHeight)
        val wh = ww * ASPECT
        val left = (w - ww) / 2f
        val top = (h - wh) / 2f
        window.set(left, top, left + ww, top + wh)

        val r = ww * CORNER_FRACTION
        windowPath.reset()
        // Süre çizgisi üst ortadan başlasın diye yol elle kurulur (addRoundRect sol üstten başlar).
        windowPath.moveTo(window.centerX(), window.top)
        windowPath.lineTo(window.right - r, window.top)
        windowPath.arcTo(window.right - 2 * r, window.top, window.right, window.top + 2 * r, -90f, 90f, false)
        windowPath.lineTo(window.right, window.bottom - r)
        windowPath.arcTo(window.right - 2 * r, window.bottom - 2 * r, window.right, window.bottom, 0f, 90f, false)
        windowPath.lineTo(window.left + r, window.bottom)
        windowPath.arcTo(window.left, window.bottom - 2 * r, window.left + 2 * r, window.bottom, 90f, 90f, false)
        windowPath.lineTo(window.left, window.top + r)
        windowPath.arcTo(window.left, window.top, window.left + 2 * r, window.top + 2 * r, 180f, 90f, false)
        windowPath.close()

        // Köşe işaretleri — pencerenin köşe yaylarının üstünde, iki kola uzanan L'ler.
        val b = ww * BRACKET_FRACTION
        brackets.reset()
        fun corner(cx: Float, cy: Float, sx: Float, sy: Float, startAngle: Float) {
            brackets.moveTo(cx + sx * (r + b), cy)
            brackets.lineTo(cx + sx * r, cy)
            val ox = if (sx > 0) cx else cx - 2 * r
            val oy = if (sy > 0) cy else cy - 2 * r
            brackets.arcTo(ox, oy, ox + 2 * r, oy + 2 * r, startAngle, if (sx * sy > 0) -90f else 90f, false)
            brackets.lineTo(cx, cy + sy * (r + b))
        }
        corner(window.left, window.top, +1f, +1f, 270f)
        corner(window.right, window.top, -1f, +1f, 270f)
        corner(window.right, window.bottom, -1f, -1f, 90f)
        corner(window.left, window.bottom, +1f, -1f, 90f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentState == STATE_HIDDEN || window.isEmpty) return

        // Maske + pencere: ayrı bir katmanda boyanıp pencere oyulur. Her karede bitmap ayırmak
        // yerine katman — önizleme üstünde saniyede onlarca kez çiziliyor.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawPath(windowPath, clearPaint)
        canvas.restoreToCount(layer)

        val stateColor = if (currentState == STATE_ALIGNED) COLOR_ALIGNED else COLOR_WAITING

        if (timeProgress >= 0f) {
            // Kalan süre çerçevenin KENDİSİNDE erir. Rakamlı sayaç baskı kuruyor; eriyen bir
            // çizgi "devam et" der.
            outlinePaint.color = COLOR_TRACK
            canvas.drawPath(windowPath, outlinePaint)
            measure.setPath(windowPath, false)
            progressPath.reset()
            measure.getSegment(0f, measure.length * timeProgress, progressPath, true)
            progressPaint.color = if (timeProgress <= LOW_TIME_FRACTION) COLOR_LOW_TIME else stateColor
            canvas.drawPath(progressPath, progressPaint)
        } else {
            outlinePaint.color = stateColor
            canvas.drawPath(windowPath, outlinePaint)
        }

        bracketPaint.color = stateColor
        canvas.drawPath(brackets, bracketPaint)
    }

    /** Aktif adımın kalan süre oranı (1 → tam, 0 → doldu). Negatif değer süre çizgisini kapatır. */
    fun setTimeProgress(progress: Float) {
        val clamped = if (progress < 0f) -1f else progress.coerceAtMost(1f)
        if (clamped == timeProgress) return
        timeProgress = clamped
        invalidate()
    }

    fun setState(state: Int) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }
}
