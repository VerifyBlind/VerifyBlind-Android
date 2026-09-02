package com.verifyblind.mobile.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class LivenessAnalyzer(
    private val onFaceDetected: (face: Face, imageProxy: ImageProxy) -> Unit,
    // Her karede (yüz bulunsa da bulunmasa da) ortalama parlaklık (0..255).
    // Karanlık/aşırı-parlak ortam uyarısı için kullanılır.
    private val onFrameLuma: ((luma: Float) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // For Eyes/Smile
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .enableTracking()
                .build()
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Ucuz parlaklık ölçümü: Y (luma) düzleminden seyrek örnekleme.
            // Yüz tespitinden BAĞIMSIZ — karanlıkta yüz bulunamasa bile uyarı verebilmek için.
            onFrameLuma?.invoke(averageLuma(mediaImage))

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        // Return the largest/first face with ImageProxy for capture
                        // RESPONSIBILITY: Callback MUST close imageProxy!
                        onFaceDetected(faces[0], imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /** YUV_420_888 Y düzleminden ~2048 örnekle ortalama parlaklık (0..255). Hatada 128 (nötr). */
    private fun averageLuma(image: android.media.Image): Float {
        return try {
            val y = image.planes[0].buffer
            val n = y.remaining()
            if (n <= 0) return 128f
            val step = maxOf(1, n / 2048)
            var sum = 0L
            var count = 0
            var i = 0
            while (i < n) {
                sum += (y.get(i).toInt() and 0xFF)
                count++
                i += step
            }
            if (count > 0) sum.toFloat() / count else 128f
        } catch (e: Exception) {
            128f
        }
    }
}

