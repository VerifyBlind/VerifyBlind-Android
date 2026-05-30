package com.verifyblind.mobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FaceEmbedder(val context: Context) {

    private var interpreter: Interpreter? = null
    private val INPUT_SIZE = 112
    private val EMBEDDING_SIZE = 192

    // Canonical 5-point eye positions for 112x112 MobileFaceNet (InsightFace ArcFace standard)
    private val DST_LEFT_EYE  = PointF(38.29f, 51.70f)
    private val DST_RIGHT_EYE = PointF(73.53f, 51.50f)

    init {
        try {
            System.out.println("VerifyBlind_AI: Initializing FaceEmbedder...")
            val modelOptions = Interpreter.Options()
            modelOptions.setNumThreads(4)
            val modelFile = FileUtil.loadMappedFile(context, "mobilefacenet.tflite")
            interpreter = Interpreter(modelFile, modelOptions)
            System.out.println("VerifyBlind_AI: Model Loaded Successfully!")
        } catch (e: Exception) {
            System.out.println("VerifyBlind_AI: Model Load Failed! " + e.message)
            e.printStackTrace()
        }
    }

    /**
     * Returns embedding from a bitmap using direct resize (fallback, no alignment).
     */
    fun getEmbedding(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) return null
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            runInference(resized)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Returns embedding after applying face alignment (similarity transform) using
     * left and right eye positions in the bitmap's coordinate space.
     *
     * The transform rotates + scales the face so that the eye centers land exactly
     * on the MobileFaceNet canonical positions, eliminating tilt and distance
     * variation before inference.
     *
     * Falls back to getEmbedding() if eye positions are null or inter-eye distance
     * is too small to be reliable.
     */
    /**
     * Yüzü similarity transform ile 112x112'ye hizalar ve bitmap döndürür.
     * Bu bitmap hem embedding hesabında hem de enclave'e göndermek için kullanılır.
     * Landmark yoksa ya da güvenilir değilse doğrudan scale fallback döner.
     */
    fun getAlignedBitmap(
        bitmap: Bitmap,
        leftEye: PointF?,
        rightEye: PointF?
    ): Bitmap {
        if (leftEye == null || rightEye == null) {
            return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        val dx = rightEye.x - leftEye.x
        val dy = rightEye.y - leftEye.y
        val srcDist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (srcDist < 5f) {
            return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        val dstDx = DST_RIGHT_EYE.x - DST_LEFT_EYE.x
        val dstDy = DST_RIGHT_EYE.y - DST_LEFT_EYE.y
        val dstDist = Math.sqrt((dstDx * dstDx + dstDy * dstDy).toDouble()).toFloat()

        val scale = dstDist / srcDist
        val angleDeg = Math.toDegrees(
            Math.atan2(dy.toDouble(), dx.toDouble()) - Math.atan2(dstDy.toDouble(), dstDx.toDouble())
        ).toFloat()

        val matrix = Matrix()
        matrix.postTranslate(-leftEye.x, -leftEye.y)
        matrix.postRotate(-angleDeg)
        matrix.postScale(scale, scale)
        matrix.postTranslate(DST_LEFT_EYE.x, DST_LEFT_EYE.y)

        return try {
            val aligned = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            Canvas(aligned).drawBitmap(bitmap, matrix, null)
            aligned
        } catch (e: Exception) {
            e.printStackTrace()
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
    }

    /**
     * Hizalanmış bitmap üzerinden embedding döndürür.
     * getAlignedBitmap() + runInference() — aynı bitmap enclave'e de gönderilecekse
     * önce getAlignedBitmap() çağır, sonra bu metodu kullan.
     */
    fun getEmbeddingAligned(
        bitmap: Bitmap,
        leftEye: PointF?,
        rightEye: PointF?
    ): FloatArray? {
        if (interpreter == null) return null
        val aligned = getAlignedBitmap(bitmap, leftEye, rightEye)
        return runInference(aligned)
    }

    /**
     * Runs TFLite inference on a bitmap that is already INPUT_SIZE x INPUT_SIZE.
     */
    private fun runInference(bitmap112: Bitmap): FloatArray? {
        return try {
            val byteBuffer = convertBitmapToByteBuffer(bitmap112)
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interpreter?.run(byteBuffer, output)
            val raw = output[0]
            val sumSq = raw.sumOf { (it * it).toDouble() }
            val norm = Math.sqrt(sumSq).toFloat()
            if (norm > 0) FloatArray(EMBEDDING_SIZE) { i -> raw[i] / norm } else raw
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 1 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        // MobileFaceNet normalization: (pixel - 128) / 128  → [-1, 1]
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val input = intValues[pixel++]
                byteBuffer.putFloat(((input shr 16 and 0xFF) - 128.0f) / 128.0f) // R
                byteBuffer.putFloat(((input shr 8  and 0xFF) - 128.0f) / 128.0f) // G
                byteBuffer.putFloat(((input        and 0xFF) - 128.0f) / 128.0f) // B
            }
        }
        return byteBuffer
    }

    companion object {
        fun cosineSimilarity(e1: FloatArray, e2: FloatArray): Float {
            if (e1.size != e2.size) return 0f

            var dot = 0f
            var normA = 0f
            var normB = 0f

            for (i in e1.indices) {
                dot   += e1[i] * e2[i]
                normA += e1[i] * e1[i]
                normB += e2[i] * e2[i]
            }

            if (normA == 0f || normB == 0f) return 0f
            return dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
        }
    }
}
