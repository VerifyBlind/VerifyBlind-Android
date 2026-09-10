package com.verifyblind.mobile

import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import com.google.mlkit.vision.face.FaceLandmark
import com.verifyblind.mobile.databinding.ActivityLoginFaceBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.LivenessAnalyzer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Girişte canlı yüz karesi toplayan ekran.
 *
 * NEDEN VAR: giriş anına kadar her kontrol TELEFONUN meşru olduğunu kanıtlıyor (bilet, bağlama,
 * nonce, MAC, holder-of-key) — hiçbiri telefonu TUTAN kişiyi kanıtlamıyor. Cihaz anahtarı
 * AUTH_DEVICE_CREDENTIAL ile de açıldığından PIN'i bilen bir aile ferdi kart sahibi adına
 * doğrulanabiliyordu. Bu ekran o boşluğu kapatan karenin kaynağıdır.
 *
 * [LivenessActivity] ile farkı — ve neden ayrı bir ekran:
 *   • JEST YOK. Giriş ~2 saniyede bitmeli, yoksa 2FA/step-up kullanım alanı ölür. Jest eklemek
 *     bu ekranın var oluş amacını bozar.
 *   • Tek kare. Aday listesi, streaming, "en iyi kare" yarışı yok.
 *   • Cihaz-içi karşılaştırma YOK. Otoriter karar enclave'dedir; burada bir eşik uygulamak
 *     yanlış-red üretirdi (canlı benzerlik akışının ilk ölçümü: 6 streaming karesinin 4'ünde
 *     cihaz reddederdi, enclave hepsini geçirdi).
 *
 * 🔴 K6: selfie ve anti-spoof kırpması AYNI KAREDEN üretilir. Benzerlik bir kareden, canlılık
 * başkasından alınırsa gerçek bir açık doğar (fotoğraf tut + kendi yüzünü göster) — bu yüzden
 * ikisi tek `captureFrame` çağrısında, tek bitmap'ten çıkar.
 */
class LoginFaceActivity : BaseActivity() {

    companion object {
        /** Kare "yeterince iyi" sayılmadan önce beklenen en düşük netlik (112×112 gradyan enerjisi). */
        private const val MIN_SHARPNESS = 45f

        /**
         * Kare toplama için üst sınır. Aşılırsa kullanıcı sonsuza kadar bakmaz; ekran hata ile
         * kapanır ve giriş reddedilir (fail-closed: "kare alamadık" ASLA "geçti" değildir).
         */
        private const val CAPTURE_TIMEOUT_MS = 20_000L

        /** Kalite iyileşmesini beklemek için harcanacak süre — dolunca eldeki en iyi kare gönderilir. */
        private const val SETTLE_MS = 1200L

        const val EXTRA_USER_SELFIE = "user_selfie"
        const val EXTRA_ANTISPOOF_CROP = "antispoof_crop"
        const val EXTRA_FRAME_METRICS = "frame_metrics"
    }

    private lateinit var binding: ActivityLoginFaceBinding
    private lateinit var cameraExecutor: ExecutorService

    private var faceEmbedder: com.verifyblind.mobile.util.FaceEmbedder? = null

    private var userSelfiePath: String? = null
    private var antiSpoofCropPath: String? = null
    private var frameMetricsJson: String? = null

    private var bestQuality = -1f
    private var startedAt = 0L
    private var firstGoodFrameAt = 0L
    private var lastLuma = 0f
    @Volatile private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginFaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // Oval kılavuz — LivenessActivity ile AYNI boyut. Varsayılan SIZE_SMALL (%40) "uzak dur,
        // yüz küçük görünsün" demek: kullanıcı geri çekilir, yüz kutusu küçülür, netlik düşer ve
        // kabul edilebilir kare çok geç gelir. Cihazda yaşandı — Android girişi iOS'a göre belirgin
        // yavaştı, sebebi buydu (iOS'ta böyle bir küçültme yok).
        binding.faceOvalOverlay.visibility = View.VISIBLE
        binding.faceOvalOverlay.setSize(com.verifyblind.mobile.view.FaceOvalOverlayView.SIZE_LARGE)
        binding.faceOvalOverlay.setState(com.verifyblind.mobile.view.FaceOvalOverlayView.STATE_WAITING)

        cameraExecutor = Executors.newSingleThreadExecutor()
        faceEmbedder = runCatching { com.verifyblind.mobile.util.FaceEmbedder(this) }.getOrNull()
        startedAt = System.currentTimeMillis()

        try {
            startCamera()
        } catch (t: Throwable) {
            AppLog.error("Kamera başlatma başarısız", "LoginFace", t)
            failAndFinish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

                val analysisBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // Kayıt akışıyla AYNI kamera ayarları: "güzelleştirme"/agresif yumuşatma kapalı.
                // Enclave iki akışta da aynı modeli çalıştırıyor — girdinin de aynı olması gerek,
                // yoksa girişteki skorlar kayıttakilerle kıyaslanamaz hâle gelir.
                try {
                    val ext = androidx.camera.camera2.interop.Camera2Interop.Extender(analysisBuilder)
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_EFFECT_MODE_OFF)
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.EDGE_MODE,
                        android.hardware.camera2.CameraMetadata.EDGE_MODE_FAST)
                } catch (e: Exception) {
                    Log.w("LoginFace", "Camera2 filtre ayarı atlandı: ${e.message}")
                }

                val imageAnalysis = analysisBuilder.build().also {
                    it.setAnalyzer(cameraExecutor, LivenessAnalyzer(
                        onFaceDetected = { face, imageProxy -> processFace(face, imageProxy) },
                        onFrameLuma = { luma -> lastLuma = luma }
                    ))
                }

                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis
                )

                binding.viewFinder.post {
                    try {
                        val factory = binding.viewFinder.meteringPointFactory
                        val point = factory.createPoint(
                            binding.viewFinder.width / 2f, binding.viewFinder.height / 2f)
                        val action = androidx.camera.core.FocusMeteringAction.Builder(
                            point,
                            androidx.camera.core.FocusMeteringAction.FLAG_AF or
                                androidx.camera.core.FocusMeteringAction.FLAG_AE
                        ).disableAutoCancel().build()
                        boundCamera.cameraControl.startFocusAndMetering(action)
                    } catch (e: Exception) {
                        Log.w("LoginFace", "Metering ayarlanamadı: ${e.message}")
                    }
                }

                binding.root.postDelayed({ if (!finished) onTimeout() }, CAPTURE_TIMEOUT_MS)
            } catch (exc: Throwable) {
                AppLog.error("Kamera başlatma başarısız", "LoginFace", exc)
                runOnUiThread { failAndFinish() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFace(face: com.google.mlkit.vision.face.Face, imageProxy: androidx.camera.core.ImageProxy) {
        if (finished) return
        try {
            captureFrame(imageProxy, face)
        } catch (e: Exception) {
            AppLog.error("Kare işleme başarısız", "LoginFace", e)
        }
    }

    private var lastCaptureTime = 0L

    /**
     * Tek kareden selfie + anti-spoof kırpmasını üretir. Kayıt akışındaki `captureFrame` ile AYNI
     * boru hattı (aynı hizalama, aynı 2,7× oran, aynı boyutlar) — enclave iki akışta da aynı
     * modelleri çalıştırdığı için girdi de aynı olmalı.
     *
     * Bitmap zinciri `finally`'de serbest bırakılır: 400ms'de bir ~8 MB ara bitmap üretiliyor ve
     * hiçbiri kareden sonra yaşamıyor. GC'yi beklemek doğrulamanın ORTASINDA process sonlanmasına
     * yol açabiliyor (kayıt akışında yaşandı).
     */
    private fun captureFrame(imageProxy: androidx.camera.core.ImageProxy, face: com.google.mlkit.vision.face.Face) {
        if (System.currentTimeMillis() - lastCaptureTime < 400) return
        lastCaptureTime = System.currentTimeMillis()

        val faceBox = face.boundingBox

        var srcRef: android.graphics.Bitmap? = null
        var fullRef: android.graphics.Bitmap? = null
        var croppedRef: android.graphics.Bitmap? = null
        var alignedRef: android.graphics.Bitmap? = null

        try {
            val bitmap = imageProxy.toBitmap() ?: return
            srcRef = bitmap

            val matrix = android.graphics.Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            val fullBitmap = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            fullRef = fullBitmap

            val margin = faceBox.width() * 0.4f
            val left   = (faceBox.left   - margin).coerceAtLeast(0f)
            val top    = (faceBox.top    - margin).coerceAtLeast(0f)
            val right  = (faceBox.right  + margin).coerceAtMost(fullBitmap.width.toFloat())
            val bottom = (faceBox.bottom + margin).coerceAtMost(fullBitmap.height.toFloat())
            val width  = right - left
            val height = bottom - top
            if (width <= 50 || height <= 50) return

            val croppedBitmap = android.graphics.Bitmap.createBitmap(
                fullBitmap, left.toInt(), top.toInt(), width.toInt(), height.toInt())
            croppedRef = croppedBitmap

            val leftEyeInCrop  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let  { PointF(it.x - left, it.y - top) }
            val rightEyeInCrop = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { PointF(it.x - left, it.y - top) }

            val alignedBitmap = faceEmbedder?.getAlignedBitmap(croppedBitmap, leftEyeInCrop, rightEyeInCrop)
            alignedRef = alignedBitmap

            val sharpness = alignedBitmap?.let { computeSharpness(it) } ?: -1f
            val poseOk = kotlin.math.abs(face.headEulerAngleY) < 20f &&
                         kotlin.math.abs(face.headEulerAngleX) < 20f

            runOnUiThread {
                val warn = when {
                    sharpness in 0f..MIN_SHARPNESS -> getString(R.string.login_face_warn_blur)
                    !poseOk -> getString(R.string.login_face_warn_pose)
                    else -> null
                }
                binding.tvQualityWarning.text = warn ?: ""
                binding.tvQualityWarning.visibility = if (warn != null) View.VISIBLE else View.GONE
                binding.tvStatus.setText(
                    if (warn == null) R.string.login_face_status_hold else R.string.login_face_status_looking)
            }

            // Kalite skoru: netlik + poz. Cihaz BENZERLİK ölçmez (bloklamaz) — yalnız hangi karenin
            // enclave'e gideceğini seçer.
            val quality = (if (sharpness > 0f) sharpness else 0f) + (if (poseOk) 50f else 0f)
            if (quality <= bestQuality) return

            val saveTarget = alignedBitmap ?: croppedBitmap
            val selfieFile = File(cacheDir, "login_selfie.png")
            // PNG (lossless): ArcFace girişi tam bu 112×112 piksel; bu boyutta JPEG blok artefaktı
            // embedding'i bozabilir.
            java.io.FileOutputStream(selfieFile).use {
                saveTarget.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            userSelfiePath = selfieFile.absolutePath

            // Anti-spoof: AYNI karenin 2,7× geniş kırpması (80×80). MiniFASNetV2 bağlam + arka plan
            // ister — dar yüz kırpması ekran/fotoğraf saldırısını ayırt etmeye yetmez.
            val asCenterX = faceBox.exactCenterX()
            val asCenterY = faceBox.exactCenterY()
            val asHalfW = faceBox.width() * 2.7f / 2f
            val asHalfH = faceBox.height() * 2.7f / 2f
            val asLeft = (asCenterX - asHalfW).coerceAtLeast(0f).toInt()
            val asTop  = (asCenterY - asHalfH).coerceAtLeast(0f).toInt()
            val asW = ((asCenterX + asHalfW).coerceAtMost(fullBitmap.width.toFloat()) - asLeft).toInt().coerceAtLeast(1)
            val asH = ((asCenterY + asHalfH).coerceAtMost(fullBitmap.height.toFloat()) - asTop).toInt().coerceAtLeast(1)
            val wideCrop = android.graphics.Bitmap.createBitmap(fullBitmap, asLeft, asTop, asW, asH)
            val scaled80 = android.graphics.Bitmap.createScaledBitmap(wideCrop, 80, 80, true)
            val asFile = File(cacheDir, "login_antispoof_crop.jpg")
            java.io.FileOutputStream(asFile).use {
                scaled80.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            antiSpoofCropPath = asFile.absolutePath
            if (wideCrop !== scaled80) scaled80.recycle()
            wideCrop.recycle()

            val faceFrac = if (imageProxy.width > 0) faceBox.width().toFloat() / imageProxy.width else -1f
            // Cihaz ölçüleri enclave'de DOĞRULANMAZ — yalnız teşhis satırına yazılır. device_match_score
            // bilerek YOK: girişte cihaz benzerlik ölçmüyor, sıfır göndermek "hiç benzemedi" gibi okunurdu.
            frameMetricsJson = com.google.gson.Gson().toJson(
                com.verifyblind.mobile.util.SimilarityStreamer.metricsOf(
                    deviceMatchScore = null,
                    luma = lastLuma.toInt(),
                    sharpness = sharpness.toInt(),
                    quality = quality.toInt(),
                    yaw = face.headEulerAngleY.toInt(),
                    pitch = face.headEulerAngleX.toInt(),
                    roll = face.headEulerAngleZ.toInt(),
                    faceWidthRatio = (faceFrac * 100).toInt(),
                    // Girişte JEST YOK — bu iki alan kayıt akışının jest sayaçları; null gitmeleri
                    // ölçüm satırında "giriş karesi" ile "kayıt karesi"ni ayırt etmenin de yolu.
                    gestureCount = null,
                    wrongGestureCount = null,
                    elapsedMs = (System.currentTimeMillis() - startedAt).toInt(),
                ))

            bestQuality = quality
            if (firstGoodFrameAt == 0L && poseOk && sharpness > MIN_SHARPNESS) {
                firstGoodFrameAt = System.currentTimeMillis()
            }

            // İyi bir kare bulduktan sonra kısa bir süre daha iyileşme bekle, sonra gönder.
            // Anında dönmek en iyi kareyi değil İLK kabul edilebilir kareyi seçerdi.
            if (firstGoodFrameAt > 0 && System.currentTimeMillis() - firstGoodFrameAt >= SETTLE_MS) {
                runOnUiThread { succeedAndFinish() }
            }
        } finally {
            alignedRef?.let { if (it !== croppedRef) it.recycle() }
            croppedRef?.let { if (it !== fullRef) it.recycle() }
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    private fun computeSharpness(bm: android.graphics.Bitmap): Float {
        return try {
            val w = bm.width; val h = bm.height
            if (w < 4 || h < 4) return -1f
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            var sum = 0.0; var count = 0
            var y = 1
            while (y < h - 1) {
                val row = y * w
                var x = 1
                while (x < w - 1) {
                    val c = lumaOf(px[row + x])
                    val gx = lumaOf(px[row + x + 1]) - c
                    val gy = lumaOf(px[row + w + x]) - c
                    sum += (gx * gx + gy * gy).toDouble()
                    count++
                    x += 2
                }
                y += 2
            }
            if (count > 0) (sum / count).toFloat() else -1f
        } catch (e: Exception) { -1f }
    }

    private fun lumaOf(p: Int): Int {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /**
     * Süre doldu. Elde kabul edilebilir bir kare varsa onu gönderiyoruz — kullanıcıyı mükemmel
     * kare için sonsuza kadar bekletmek, enclave'in zaten geçirebileceği bir kareyi çöpe atardı
     * (cihaz kapısının yanlış-red ürettiği bilinen kalıp).
     */
    private fun onTimeout() {
        if (userSelfiePath != null && antiSpoofCropPath != null) {
            AppLog.info("Süre doldu, eldeki en iyi kare gönderiliyor (kalite=${bestQuality.toInt()})", "LoginFace")
            succeedAndFinish()
        } else {
            AppLog.warning("Süre doldu, kullanılabilir kare yok — giriş iptal", "LoginFace")
            failAndFinish()
        }
    }

    private fun succeedAndFinish() {
        if (finished) return
        val selfie = userSelfiePath
        val crop = antiSpoofCropPath
        // Kırpma olmadan selfie GÖNDERİLMEZ: enclave canlılığı fail-closed uyguluyor, eksik kırpma
        // orada nasılsa reddedilir — kullanıcıyı ağ turu sonrası değil, burada uyar.
        if (selfie == null || crop == null) { failAndFinish(); return }
        finished = true
        AppLog.info("Giriş karesi hazır (kalite=${bestQuality.toInt()})", "LoginFace")
        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra(EXTRA_USER_SELFIE, selfie)
            putExtra(EXTRA_ANTISPOOF_CROP, crop)
            putExtra(EXTRA_FRAME_METRICS, frameMetricsJson)
        })
        finish()
    }

    private fun failAndFinish() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun applySystemBarInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroy() {
        super.onDestroy()
        finished = true
        runCatching { cameraExecutor.shutdown() }
    }
}
