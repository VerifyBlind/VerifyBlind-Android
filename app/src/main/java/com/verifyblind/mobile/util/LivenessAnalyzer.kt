package com.verifyblind.mobile.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class LivenessAnalyzer(
    private val onFaceDetected: (face: Face, imageProxy: ImageProxy) -> Unit
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
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Capture dimensions before processing
            val imgWidth = image.width
            val imgHeight = image.height
            
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
}

