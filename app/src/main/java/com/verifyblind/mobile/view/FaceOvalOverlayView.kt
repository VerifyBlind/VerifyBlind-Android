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
    
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    
    private val silhouetteRect = RectF()
    private val silhouettePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (currentState == STATE_HIDDEN) return
        
        // Calculate silhouette based on size mode
        val silhouetteWidth: Float
        val silhouetteHeight: Float
        val topOffset: Float
        
        when (currentSize) {
            SIZE_SMALL -> {
                // Small frame - user should be far (face appears small)
                silhouetteWidth = width * 0.40f
                silhouetteHeight = silhouetteWidth * 1.35f
                topOffset = 3.0f
            }
            SIZE_LARGE -> {
                // Large frame - user should be close (face appears large)
                silhouetteWidth = width * 0.75f
                silhouetteHeight = silhouetteWidth * 1.35f
                topOffset = 2.0f
            }
            else -> {
                silhouetteWidth = width * 0.55f
                silhouetteHeight = silhouetteWidth * 1.35f
                topOffset = 2.8f
            }
        }
        
        val left = (width - silhouetteWidth) / 2
        val top = (height - silhouetteHeight) / topOffset
        
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
        silhouettePaint.color = when (currentState) {
            STATE_WAITING -> Color.parseColor("#FF4444")
            STATE_ALIGNED -> Color.parseColor("#4CAF50")
            else -> Color.WHITE
        }
        canvas.drawPath(silhouettePath, silhouettePaint)
        
        // Draw guide lines
        val guideLinePaint = Paint().apply {
            color = silhouettePaint.color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        canvas.drawLine(silhouetteRect.left + 20, silhouetteRect.top, 
                       silhouetteRect.right - 20, silhouetteRect.top, guideLinePaint)
        canvas.drawLine(silhouetteRect.left + 20, silhouetteRect.bottom, 
                       silhouetteRect.right - 20, silhouetteRect.bottom, guideLinePaint)
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
