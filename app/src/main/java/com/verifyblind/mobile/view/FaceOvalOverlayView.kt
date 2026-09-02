package com.verifyblind.mobile.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Custom overlay view that draws a face silhouette for positioning guidance.
 * Supports two sizes: SMALL (far) and LARGE (close).
 */
class FaceOvalOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STATE_WAITING = 0
        const val STATE_ALIGNED = 1
        const val STATE_HIDDEN = 2
        
        // Backwards compatibility
        const val STATE_FAR = STATE_WAITING
        const val STATE_OK = STATE_ALIGNED
        
        // Size modes
        const val SIZE_SMALL = 0  // For "move back" phase
        const val SIZE_LARGE = 1  // For "move close" phase
        
        // Alignment tolerance (40%)
        const val ALIGNMENT_TOLERANCE = 0.40f

        /** Kalan sürenin bu oranın altında kalması = kehribar halka (iOS lowTimeFraction paritesi). */
        const val LOW_TIME_FRACTION = 0.27f
    }

    private var currentState = STATE_WAITING
    private var currentSize = SIZE_SMALL
    
    // Paints
    private val overlayPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private val silhouettePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    
    /** Kalan süre halkası (0..1); negatif → halka çizilmez. */
    private var timeProgress = -1f

    private val progressPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    
    private val silhouetteRect = RectF()
    private val silhouettePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (currentState == STATE_HIDDEN) return
        
        // Oval, bu view'in KENDİ kutusuna sığdırılır ve ortalanır. View artık tüm ekran değil,
        // talimat metinleriyle alt bilgi satırı arasındaki bant; genişlik oranı tek başına
        // kullanılırsa kısa ekranlarda oval bandı taşıyor ve yazıların altına giriyordu
        // (kullanıcı geri bildirimi 2026-08-21). Yükseklik sınırı her çözünürlükte sığmayı garanti eder.
        val widthFraction = when (currentSize) {
            SIZE_SMALL -> 0.40f   // uzak dur (yüz küçük görünsün)
            SIZE_LARGE -> 0.75f   // yaklaş (yüz büyük görünsün)
            else -> 0.55f
        }
        val maxByHeight = height * 0.94f / 1.35f
        val silhouetteWidth = minOf(width * widthFraction, maxByHeight)
        val silhouetteHeight = silhouetteWidth * 1.35f

        val left = (width - silhouetteWidth) / 2
        val top = (height - silhouetteHeight) / 2
        
        silhouetteRect.set(left, top, left + silhouetteWidth, top + silhouetteHeight)
        
        // Build path
        silhouettePath.reset()
        silhouettePath.addOval(silhouetteRect, Path.Direction.CW)
        
        // Draw overlay with cutout
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(bitmap)
        tempCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        tempCanvas.drawPath(silhouettePath, clearPaint)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        
        // Draw border
        if (timeProgress >= 0f) {
            // Kalan süre halkası: kenarlığın KENDİSİ erir. Rakamlı sayaç gösterilmez — sayaç baskı
            // kuruyor ve kafa çevrikken zaten görünmüyor; erimekte olan bir kenarlık "devam et" der.
            silhouettePaint.color = Color.parseColor("#E0E0E0")
            canvas.drawPath(silhouettePath, silhouettePaint)
            progressPaint.color =
                if (timeProgress <= LOW_TIME_FRACTION) Color.parseColor("#F29B12")
                else Color.parseColor("#FF4444")
            canvas.drawArc(silhouetteRect, -90f, 360f * timeProgress, false, progressPaint)
        } else {
            silhouettePaint.color = when (currentState) {
                STATE_WAITING -> Color.parseColor("#FF4444")
                STATE_ALIGNED -> Color.parseColor("#4CAF50")
                else -> Color.WHITE
            }
            canvas.drawPath(silhouettePath, silhouettePaint)
        }
    }

    /** Aktif hareketin kalan süre oranı (1 → tam, 0 → doldu). Negatif değer halkayı kapatır. */
    fun setTimeProgress(progress: Float) {
        val clamped = if (progress < 0f) -1f else progress.coerceAtMost(1f)
        if (clamped == timeProgress) return
        timeProgress = clamped
        invalidate()
    }
    
    fun getTargetRect(): RectF = RectF(silhouetteRect)
    
    fun checkAlignment(faceRect: RectF): Float {
        if (silhouetteRect.isEmpty) return 1.0f
        
        val targetWidth = silhouetteRect.width()
        val targetHeight = silhouetteRect.height()
        
        val topError = abs(faceRect.top - silhouetteRect.top) / targetHeight
        val bottomError = abs(faceRect.bottom - silhouetteRect.bottom) / targetHeight
        val leftError = abs(faceRect.left - silhouetteRect.left) / targetWidth
        val rightError = abs(faceRect.right - silhouetteRect.right) / targetWidth
        
        return max(max(topError, bottomError), max(leftError, rightError))
    }
    
    fun isAligned(faceRect: RectF): Boolean = checkAlignment(faceRect) <= ALIGNMENT_TOLERANCE
    
    fun setState(state: Int) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }
    
    fun setSize(size: Int) {
        if (currentSize != size) {
            currentSize = size
            invalidate()
        }
    }
    
    fun setVisible(visible: Boolean) {
        visibility = if (visible) VISIBLE else GONE
    }
}
