package com.verifyblind.mobile.camera

import android.animation.ValueAnimator
import android.util.Log
import android.util.Size
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.verifyblind.mobile.R
import com.verifyblind.mobile.databinding.ActivityMainBinding
import com.verifyblind.mobile.nfc.PassportReader
import com.verifyblind.mobile.util.MrzAnalyzer
import com.verifyblind.mobile.util.QrAnalyzer
import java.util.concurrent.ExecutorService

/**
 * CameraManager — Kamera başlatma/durdurma, QR/MRZ analiz delegasyonu.
 *
 * MainActivity'den ayrıştırılmış sorumluluklar:
 * - CameraX provider kurulumu ve preview binding
 * - QR ve MRZ analyzer kurulumu
 * - Kamera durdurma ve unbind
 * - Zoom kontrolleri
 */
class CameraManager(
    private val lifecycleOwner: LifecycleOwner,
    private val binding: ActivityMainBinding,
    private val cameraExecutor: ExecutorService
) {
    var camera: Camera? = null
        private set

    private var scanLineAnimator: ValueAnimator? = null
    private var arrowAnimator: ValueAnimator? = null

    /**
     * Kamerayı başlatır ve analiz moduna göre QR veya MRZ analyzer kurar.
     *
     * @param isQr true ise QR tarama, false ise MRZ tarama modu
     * @param onQrDetected QR tespit edildiğinde çağrılır (qrData: String)
     * @param onMrzDetected MRZ tespit edildiğinde çağrılır (docNo, dob, expiry, documentType)
     */
    fun startCamera(
        isQr: Boolean,
        onQrDetected: ((String) -> Unit)? = null,
        onMrzDetected: ((String, String, String, String) -> Unit)? = null
    ) {
        var isProcessing = false
        val context = (lifecycleOwner as android.app.Activity)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            binding.viewFlipper.visibility = View.VISIBLE
            binding.mainNavHost.visibility = View.GONE
            binding.viewFlipper.displayedChild = 2
            // Overlay'i kamera moduyla aynı yerde, kameranın gerçekten başladığı anda
            // ayarla. Caller'a bırakılınca bir önceki akıştan (örn. kart ekleme MRZ modu)
            // kalan overlay QR taramaya sızabiliyordu.
            setCameraOverlay(isQr)

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    if (isQr) {
                        it.setAnalyzer(cameraExecutor, QrAnalyzer { qrData ->
                            context.runOnUiThread {
                                if (isProcessing) return@runOnUiThread
                                isProcessing = true

                                try {
                                    val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(100)
                                    }
                                } catch (e: Exception) { }

                                stopCamera(resetToHome = false)
                                onQrDetected?.invoke(qrData)
                            }
                        })
                    } else {
                        it.setAnalyzer(cameraExecutor, MrzAnalyzer { docNo, dob, expiry, documentType ->
                            context.runOnUiThread {
                                if (isProcessing) return@runOnUiThread
                                isProcessing = true
                                onMrzDetected?.invoke(docNo, dob, expiry, documentType)
                            }
                        })
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
                camera?.cameraControl?.setZoomRatio(1.0f)
            } catch (exc: Exception) {
                Log.e("VerifyBlind", "Kamera bağlanamadı: ${exc.message}")
            }

            startScanLineAnimation()

        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Kamerayı durdurur ve CameraX sağlayıcısını unbind eder.
     */
    fun stopCamera(resetToHome: Boolean = true) {
        stopScanLineAnimation()
        stopArrowAnimation()
        val context = (lifecycleOwner as android.app.Activity)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Kamera overlay'ini QR veya MRZ moduna göre ayarlar.
     */
    fun setCameraOverlay(isQr: Boolean) {
        val context = binding.root.context
        if (isQr) {
            binding.tvOverlayInstruction.text = context.getString(R.string.scan_qr_instruction)
            binding.tvOverlaySubtitle.text = ""
            binding.layoutCardVisual.visibility = View.GONE
            binding.ivMrzArrow.visibility = View.GONE
            binding.viewOverlayFrame.visibility = View.VISIBLE
            binding.layoutZoomControls.visibility = View.GONE
            stopArrowAnimation()
        } else {
            binding.tvOverlayInstruction.text = context.getString(R.string.scan_mrz_instruction)
            binding.tvOverlaySubtitle.text = context.getString(R.string.scan_mrz_subtitle)
            binding.layoutCardVisual.visibility = View.VISIBLE
            binding.ivMrzArrow.visibility = View.VISIBLE
            binding.viewOverlayFrame.visibility = View.VISIBLE
            binding.layoutZoomControls.visibility = View.GONE
            startArrowAnimation()
        }
    }

    private fun startScanLineAnimation() {
        val scanLine = binding.viewScanLine
        scanLine.visibility = View.VISIBLE
        val frame = binding.viewOverlayFrame

        fun doStart() {
            val frameHeight = frame.height.toFloat()
            if (frameHeight == 0f) return
            scanLineAnimator?.cancel()
            scanLineAnimator = ValueAnimator.ofFloat(0f, frameHeight - scanLine.height).apply {
                duration = 1800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    scanLine.translationY = anim.animatedValue as Float
                }
                start()
            }
        }

        if (frame.height > 0) {
            doStart()
        } else {
            frame.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (frame.height > 0) {
                        frame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        doStart()
                    }
                }
            })
        }
    }

    private fun stopScanLineAnimation() {
        scanLineAnimator?.cancel()
        scanLineAnimator = null
        binding.viewScanLine.visibility = View.GONE
    }

    private fun startArrowAnimation() {
        val arrow = binding.ivMrzArrow
        arrowAnimator?.cancel()
        arrowAnimator = ValueAnimator.ofFloat(0f, 10f, 0f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                arrow.translationY = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun stopArrowAnimation() {
        arrowAnimator?.cancel()
        arrowAnimator = null
        binding.ivMrzArrow.translationY = 0f
    }

    /**
     * Zoom seviyesini artırır.
     */
    fun zoomIn() {
        val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 5f
        if (currentZoom < maxZoom) {
            camera?.cameraControl?.setZoomRatio(currentZoom + 1.0f)
        }
    }

    /**
     * Zoom seviyesini düşürür.
     */
    fun zoomOut() {
        val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        if (currentZoom > minZoom) {
            camera?.cameraControl?.setZoomRatio(currentZoom - 1.0f)
        }
    }
}
