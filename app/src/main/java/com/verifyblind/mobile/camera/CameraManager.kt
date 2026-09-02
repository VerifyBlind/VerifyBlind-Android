package com.verifyblind.mobile.camera

import android.animation.ValueAnimator
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import com.verifyblind.mobile.util.AppLog
import android.util.Size
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    /** Aktif zoom hedefi (QR modunda varsayılan 2.0). setZoomRatio asenkron olduğu için
     *  zoomState'i okumak yerine hedefi burada izleriz — hızlı çift dokunuşta stale okuma olmaz. */
    private var currentZoomTarget = 1.0f

    /**
     * Kamerayı başlatır ve analiz moduna göre QR veya MRZ analyzer kurar.
     *
     * @param isQr true ise QR tarama, false ise MRZ tarama modu
     * @param onQrDetected QR tespit edildiğinde çağrılır (qrData: String)
     * @param onMrzDetected MRZ tespit edildiğinde çağrılır (docNo, dob, expiry, documentType)
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
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

            // Çözünürlük 1080p'ye sabit (sektör tatlı noktası: hızlı + modülleri çözecek kadar net).
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // Bu çözünürlükte cihazın desteklediği en yüksek fps aralığı (daha çok kare = daha hızlı
            // okuma). AE hedef-fps oturum geneline (preview + analiz) uygulanır.
            val fpsRange = pickMaxFpsRange(context)
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (fpsRange != null) {
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            val imageAnalysis = analysisBuilder.build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
                // QR modunda varsayılan 2x açılır; MRZ'de 1x. setZoomRatio kamera açılışı
                // sırasında da kuyruğa alınır (CameraX cihaz sınırlarına kendisi kırpar).
                val defaultZoom = if (isQr) 2.0f else 1.0f
                camera?.cameraControl?.setZoomRatio(defaultZoom)
                currentZoomTarget = defaultZoom

                if (isQr) {
                    imageAnalysis.setAnalyzer(cameraExecutor, QrAnalyzer { qrData ->
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
                    imageAnalysis.setAnalyzer(cameraExecutor, MrzAnalyzer { docNo, dob, expiry, documentType ->
                        context.runOnUiThread {
                            if (isProcessing) return@runOnUiThread
                            isProcessing = true
                            onMrzDetected?.invoke(docNo, dob, expiry, documentType)
                        }
                    })
                }
            } catch (exc: Exception) {
                AppLog.warning("Kamera bağlanamadı: ${exc.message}", "VerifyBlind", exc)
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
            // QR modu: talimat metni üstte (iOS paritesi), alttaki talimat gizli.
            binding.tvQrInstruction.text = context.getString(R.string.scan_qr_instruction)
            binding.tvQrInstruction.visibility = View.VISIBLE
            binding.tvOverlayInstruction.visibility = View.GONE
            binding.tvOverlaySubtitle.text = ""
            binding.ivScanBrand.visibility = View.VISIBLE
            binding.layoutCardVisual.visibility = View.GONE
            binding.ivMrzArrow.visibility = View.GONE
            binding.viewOverlayFrame.visibility = View.VISIBLE
            binding.layoutZoomControls.visibility = View.VISIBLE
            resetZoomControls()
            stopArrowAnimation()
        } else {
            binding.tvQrInstruction.visibility = View.GONE
            binding.ivScanBrand.visibility = View.GONE
            binding.tvOverlayInstruction.visibility = View.VISIBLE
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
     * Zoom'u sabit bir orana (2x) ayarlar. Aynı orana tekrar basılırsa 1x'e döner.
     * Cihaz min/max sınırlarına göre kırpılır.
     */
    fun setZoom(ratio: Float) {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        // Aynı seviyeye tekrar basınca 1x'e dön (ayrı 1x butonu yok).
        val target = if (kotlin.math.abs(currentZoomTarget - ratio) < 0.01f) 1.0f else ratio
        currentZoomTarget = target
        val clamped = target.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
        binding.btnZoom20.isSelected = target == 2.0f
    }

    /**
     * QR moduna girişte zoom kontrolünü sıfırlar: 2x varsayılan seçili (zoom startCamera'da uygulanır).
     */
    private fun resetZoomControls() {
        currentZoomTarget = 2.0f
        binding.btnZoom20.isSelected = true
    }

    /**
     * Arka kameranın AE hedef-fps aralıklarından en yükseğini seçer. En yüksek üst sınır
     * (≤60), eşitlikte en geniş aralık (düşük ışıkta AE pozlamayı uzatabilsin → adaptif).
     * 60'a kadar; 120/240 slo-mo aralıkları taramaya zarar verir (çok kısa pozlama → karanlık).
     */
    private fun pickMaxFpsRange(context: Context): Range<Int>? {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            val ranges = cm.getCameraCharacteristics(backId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList() ?: return null
            val candidates = ranges.filter { it.upper <= 60 }.ifEmpty { ranges }
            val maxUpper = candidates.maxOf { it.upper }
            candidates.filter { it.upper == maxUpper }.minByOrNull { it.lower }
        } catch (e: Exception) {
            null
        }
    }
}
