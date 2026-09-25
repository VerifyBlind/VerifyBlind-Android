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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceLandmark
import android.widget.Toast
import com.verifyblind.mobile.databinding.ActivityLoginFaceBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.EventCollector
import com.verifyblind.mobile.util.LivenessAnalyzer
import com.verifyblind.mobile.util.LivenessFeedback
import com.verifyblind.mobile.util.commandRes
import com.verifyblind.mobile.util.hintRes
import com.verifyblind.mobile.view.FaceFrameOverlayView
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
 *   • TEK HAREKET (2026-09-26, kullanıcı kararı). Eskiden hiç hareket yoktu ve tek engel pasif
 *     canlılık modeliydi: televizyonda gösterilen bir FOTOĞRAF bile eşiği geçebiliyordu. Hareket
 *     QR nonce'undan türetilir ([com.verifyblind.mobile.util.LoginEvent]); enclave nötr ve hareket
 *     karelerinde de kart sahibinin yüzünü arar. Kılavuz yok — kullanıcı onu kart eklerken gördü.
 *   • Tek "en iyi" kare, hareketten ÖNCE seçilir. Aday listesi, streaming yok.
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
         * Kare toplama için üst sınır — kullanıcıya tanınan AZAMİ fırsat süresi.
         *
         * Eşiği geçemeyen kullanıcı bu süre boyunca gözlüğünü çıkarabilir, ışığa dönebilir,
         * açısını düzeltebilir. Dolduğunda eldeki EN İYİ kare yine de gönderilir — çünkü cihaz
         * skoru enclave kararı DEĞİLDİR (farklı model, farklı eşik) ve burada reddetmek
         * enclave'in geçireceği bir kullanıcıyı kapıda durdurmak olurdu.
         *
         * Hiç kare toplanamadıysa giriş iptal edilir (fail-closed: "kare alamadık" ≠ "geçti").
         */
        private const val CAPTURE_TIMEOUT_MS = 30_000L

        /**
         * Benzerlik eşiği geçildikten SONRA beklenen süre. Anında dönmek en iyi kareyi değil
         * eşiği ilk aşan kareyi seçerdi; bu 1 saniyede daha iyisi gelirse o gider.
         */
        private const val SETTLE_MS = 1000L

        /**
         * "Yeterince benziyor" sınırı: ekrandaki yüzdenin yeşile döndüğü VE ekranın erken
         * bitebildiği eşik. Kayıt akışındaki 0.65 ile aynı sayı.
         *
         * ⚠️ Bu bir KAPI DEĞİLDİR. Altında kalmak submit'i engellemez; yalnızca ekranın hemen
         * kapanmasını engeller, yani kullanıcıya düzeltme fırsatı verir. Süre dolunca eldeki en
         * iyi kare koşulsuz gider ve kararı enclave verir (ArcFace, eşik 0.20 — bu sayıyla
         * KIYASLANAMAZ: cihazda %54 gördüğümüz yüz enclave'de %61 aldı).
         */
        private const val SCORE_HINT_GOOD = 0.65f

        const val EXTRA_USER_SELFIE = "user_selfie"
        const val EXTRA_ANTISPOOF_CROP = "antispoof_crop"
        const val EXTRA_FRAME_METRICS = "frame_metrics"

        /** İstenen hareketin kodu ([EventCollector.Event.code]); yoksa hareketsiz biter. */
        const val EXTRA_LOGIN_EVENT = "login_event"

        /** Hareket kanıtının (ChoreographyProof JSON) dosya yolu — kareler Intent'e sığmaz. */
        const val EXTRA_MOVE_PROOF = "move_proof"

        /** İptalin sebebi — [FAIL_REASON_MOVE] ise çağıran "hareketi göremedik" der. */
        const val EXTRA_FAIL_REASON = "fail_reason"
        const val FAIL_REASON_MOVE = "move"

        /**
         * Hareket kaç kez denenebilir. Nonce ancak gönderimde tükeniyor; ekranda yeniden denemek
         * bedava — ama sınırsız deneme, video sunan saldırgana doğru anı beklemek için sınırsız
         * süre demek (kayıttaki sıfırlama sınırıyla aynı mantık).
         */
        private const val MAX_MOVE_ATTEMPTS = 3

        private const val MOVE_PROOF_FILE = "login_move_proof.json"

        /**
         * Bilete mühürlü yüz referansı (Base64 JPEG) — ekrandaki canlı % göstergesi için.
         *
         * ⚠️ Bu YALNIZ geri bildirimdir. Otoriter karşılaştırma enclave'de yapılır ve gerçek
         * kapı odur; buradaki sayı submit'i ENGELLEMEZ. Cihaz eşiği bir güvenlik kontrolü
         * olamaz (yerel bir sayı, kötü niyetli istemci yamalayabilir) ve bloklayıcı yapılırsa
         * yanlış-red üretir: canlı benzerlik akışının ilk ölçümünde cihaz 6 karenin 4'ünü
         * reddederken enclave hepsini geçirmişti.
         */
        const val EXTRA_FACE_REF_B64 = "face_ref_b64"
    }

    private lateinit var binding: ActivityLoginFaceBinding
    private lateinit var cameraExecutor: ExecutorService

    private var faceEmbedder: com.verifyblind.mobile.util.FaceEmbedder? = null

    private var userSelfiePath: String? = null
    private var antiSpoofCropPath: String? = null
    private var frameMetricsJson: String? = null

    /** Bilet referansının embedding'i — null ise % gösterilmez (hesap yapılmaz). */
    private var refEmbedding: FloatArray? = null
    /** Oturum boyunca görülen en yüksek benzerlik — ekrandaki sayı geri düşmesin diye. */
    private var bestMatchScore = 0f

    private var bestQuality = -1f
    private var startedAt = 0L
    /** "Yeterince benziyor + kalite tamam" durumunun başladığı an; 0 = henüz değil. */
    private var goodSinceMs = 0L
    private var lastLuma = 0f
    @Volatile private var finished = false

    // ── Tek hareket ──
    private var loginEvent: EventCollector.Event? = null
    private var moveCollector: EventCollector? = null
    /** En iyi kare seçildi, kareler artık harekete gidiyor (ağır bitmap işi durdu). */
    @Volatile private var movePhase = false
    private var moveAttempts = 0
    private var moveNudged = false
    private var moveProofPath: String? = null
    private var moveTicker: Runnable? = null
    /** Bu karenin iç dudak açıklığı — analizci onFaceDetected'dan HEMEN önce yazar. */
    @Volatile private var pendingLipOpen: Float? = null
    private lateinit var feedback: LivenessFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginFaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // Yüz çerçevesi — LivenessActivity ile AYNI görünüm (tek boyut; oval değil, bkz.
        // FaceFrameOverlayView).
        binding.faceFrameOverlay.visibility = View.VISIBLE
        binding.faceFrameOverlay.setState(com.verifyblind.mobile.view.FaceFrameOverlayView.STATE_WAITING)

        cameraExecutor = Executors.newSingleThreadExecutor()
        faceEmbedder = runCatching { com.verifyblind.mobile.util.FaceEmbedder(this) }.getOrNull()
        startedAt = System.currentTimeMillis()
        loginEvent = EventCollector.Event.of(intent.getIntExtra(EXTRA_LOGIN_EVENT, 0))
        feedback = LivenessFeedback(this)

        // Referans embedding'i BİR KEZ — kamera kuyruğunu her karede meşgul etmesin.
        // Başarısız olursa yalnız % göstergesi kaybolur; akış aynen sürer, çünkü gerçek
        // karşılaştırma zaten enclave'de yapılıyor.
        intent.getStringExtra(EXTRA_FACE_REF_B64)?.takeIf { it.isNotEmpty() }?.let { b64 ->
            cameraExecutor.execute {
                refEmbedding = runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching null
                    // Referansın gözlerini bul → kayıt akışındaki chip embedding ile AYNI hizalama.
                    val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
                        com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                            .setPerformanceMode(
                                com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                            .setLandmarkMode(
                                com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .build())
                    val refFace = runCatching {
                        Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))).firstOrNull()
                    }.getOrNull()
                    faceEmbedder?.getEmbeddingAligned(
                        bmp,
                        refFace?.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                        refFace?.getLandmark(FaceLandmark.RIGHT_EYE)?.position)
                }.getOrNull()
                if (refEmbedding == null) {
                    AppLog.warning("Yüz referansı embedding'i üretilemedi — % göstergesi kapalı", "LoginFace")
                }
            }
        }

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
                        onFaceDetected = { face, imageProxy, _ -> processFace(face, imageProxy) },
                        onFrameLuma = { luma -> lastLuma = luma },
                        // Dudak konturu yalnız ağız açma hareketinde: ikinci dedektör kare hızını düşürür.
                        contourWanted = { moveCollector?.wantsContour == true },
                        onContour = { lip -> pendingLipOpen = lip },
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
        // 🔴 imageProxy HER YOLDA kapatılmalı — LivenessAnalyzer'ın sözleşmesi bu ("Callback MUST
        // close imageProxy!"). STRATEGY_KEEP_ONLY_LATEST sınırlı sayıda buffer tutar; kapatılmayan
        // kare kuyruğu doldurur ve kamera YENİ KARE ÜRETMEYİ BIRAKIR.
        //
        // Cihazda yaşandı: ekrandaki benzerlik yüzdesi ilk karede yazılıp SABİT kaldı (gözlük
        // çıkarmak, duruş düzeltmek hiçbir şeyi değiştirmedi) ve akış her seferinde 20 sn'lik
        // zaman aşımına düştü. Sebep kalite mantığı değil, kare akışının durmasıydı.
        // `finished` yolu da dahil: erken dönüşte kapatmamak aynı sızıntıyı yapar.
        try {
            if (finished) return
            if (movePhase) {
                val lip = pendingLipOpen.also { pendingLipOpen = null }
                moveCollector?.takeIf { it.isActive }?.offer(imageProxy, face, lip)
            } else {
                captureFrame(imageProxy, face)
            }
        } catch (e: Exception) {
            AppLog.error("Kare işleme başarısız", "LoginFace", e)
        } finally {
            imageProxy.close()
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

            // Cihaz-içi benzerlik — YALNIZ ekrandaki % için. Submit'i ENGELLEMEZ (bkz.
            // EXTRA_FACE_REF_B64): otoriter karar enclave'de, cihaz eşiği yanlış-red kaynağı.
            // Skor da enclave skoruyla KIYASLANAMAZ: burada MobileFaceNet, orada ArcFace R50.
            val refEmb = refEmbedding
            if (refEmb != null && alignedBitmap != null) {
                faceEmbedder?.getEmbedding(alignedBitmap)?.let { selfieEmb ->
                    val sim = com.verifyblind.mobile.util.FaceEmbedder.cosineSimilarity(refEmb, selfieEmb)
                    if (sim > bestMatchScore) bestMatchScore = sim
                }
            }

            val showScore = refEmb != null
            val scorePercent = (bestMatchScore * 100).toInt()
            runOnUiThread {
                // Kuyrukta kalmış bir kare güncellemesi hareket metnini ezmesin.
                if (movePhase) return@runOnUiThread
                val warn = when {
                    sharpness in 0f..MIN_SHARPNESS -> getString(R.string.login_face_warn_blur)
                    !poseOk -> getString(R.string.login_face_warn_pose)
                    else -> null
                }
                binding.tvQualityWarning.text = warn ?: ""
                binding.tvQualityWarning.visibility = if (warn != null) View.VISIBLE else View.GONE
                // Durum metni ne BEKLEDİĞİMİZİ söylemeli. Kalite tamamken skor düşükse sorun
                // kadraj değil benzerliktir; kullanıcıya "sabit dur" demek yanıltıcı olurdu —
                // yapması gereken gözlüğünü çıkarmak, ışığa dönmek, gölgeden çıkmak.
                binding.tvStatus.setText(when {
                    warn != null -> R.string.login_face_status_looking
                    showScore && bestMatchScore < SCORE_HINT_GOOD -> R.string.login_face_status_adjust
                    else -> R.string.login_face_status_hold
                })

                if (showScore) {
                    binding.tvMatchScore.visibility = View.VISIBLE
                    binding.tvMatchScore.text = "%d%%".format(scorePercent)
                    // Renk eşiği SUNUM içindir, kapı değil: kullanıcı "iyi gidiyorum" görsün diye.
                    // Kayıt ekranındaki 0.65 ile aynı his, ama burada hiçbir şeyi engellemiyor.
                    binding.tvMatchScore.setTextColor(
                        if (bestMatchScore >= SCORE_HINT_GOOD)
                            ContextCompat.getColor(this@LoginFaceActivity, R.color.success)
                        else android.graphics.Color.RED)
                }
            }

            // Kalite skoru: netlik + poz. Cihaz BENZERLİK ölçmez (bloklamaz) — yalnız hangi karenin
            // enclave'e gideceğini seçer.
            val quality = (if (sharpness > 0f) sharpness else 0f) + (if (poseOk) 50f else 0f)

            // ÇIKIŞ KOŞULU: "yeterince benziyor" + 1 sn.
            //
            // Eskiden yalnız kalite (netlik+poz) yeterliydi ve ekran tatmin olur olmaz kareyi
            // gönderiyordu. Bu, kullanıcıya benzerliğini DÜZELTME fırsatı tanımıyordu: gözlüğünü
            // çıkaramadan, ışığa dönemeden kare gidiyor ve enclave reddedince kullanıcı ne
            // yapacağını bilmiyordu — üstelik ekranda skoru yazıyorken.
            //
            // Artık ekran skorun yükselmesini bekliyor. Referans yoksa (% hesaplanamıyorsa)
            // eski davranışa düşülür: kalite yeterliyse gönder.
            //
            // ⚠️ Bu bir KAPI DEĞİL: eşik geçilemezse CAPTURE_TIMEOUT_MS dolunca eldeki en iyi kare
            // yine gönderilir. Cihaz skorunu kapı yapmak yanlış-red üretirdi — canlı benzerlik
            // akışının ölçümünde cihaz 6 karenin 4'ünü reddederken enclave hepsini geçirmişti.
            val qualityOk = poseOk && sharpness > MIN_SHARPNESS
            val readyToFinish = if (refEmb != null) bestMatchScore >= SCORE_HINT_GOOD else qualityOk
            if (goodSinceMs == 0L && qualityOk && readyToFinish) {
                goodSinceMs = System.currentTimeMillis()
            } else if (!readyToFinish) {
                goodSinceMs = 0L   // skor düştü → sayaç sıfırlanır, acele edilmez
            }
            val settled = goodSinceMs > 0L &&
                System.currentTimeMillis() - goodSinceMs >= SETTLE_MS

            // Kalite iyileşmiyorsa yeni kare YAZILMAZ — ama elde geçerli bir kare varsa ve settle
            // dolduysa gönderilir.
            if (quality <= bestQuality) {
                if (settled && userSelfiePath != null && antiSpoofCropPath != null) {
                    runOnUiThread { onBestFrameReady() }
                }
                return
            }

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
            // Cihaz ölçüleri enclave'de DOĞRULANMAZ — yalnız teşhis satırına yazılır.
            // device_match_score yalnız referans embedding'i ÜRETİLEBİLDİYSE gider; üretilemediyse
            // null kalır, çünkü 0 göndermek ölçüm satırında "hiç benzemedi" gibi okunurdu.
            // ⚠️ Bu sayı enclave skoruyla KIYASLANAMAZ: MobileFaceNet ↔ ArcFace R50.
            frameMetricsJson = com.google.gson.Gson().toJson(
                com.verifyblind.mobile.util.SimilarityStreamer.metricsOf(
                    deviceMatchScore = if (refEmb != null)
                        (bestMatchScore * 100).toInt().coerceIn(0, 100) else null,
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

            // İyi bir kare bulduktan sonra kısa bir süre daha iyileşme bekle, sonra gönder.
            // Anında dönmek en iyi kareyi değil İLK kabul edilebilir kareyi seçerdi.
            if (settled) {
                runOnUiThread { onBestFrameReady() }
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
        // Hareket başladıysa kendi süreleri var (EventCollector) — bu süre yalnız kare seçimi için.
        if (movePhase) return
        if (userSelfiePath != null && antiSpoofCropPath != null) {
            AppLog.info("Süre doldu, eldeki en iyi kare gönderiliyor (kalite=${bestQuality.toInt()})", "LoginFace")
            onBestFrameReady()
        } else {
            AppLog.warning("Süre doldu, kullanılabilir kare yok — giriş iptal", "LoginFace")
            failAndFinish()
        }
    }

    // ── Tek hareket ──────────────────────────────────────────────────────────

    /**
     * En iyi kare hazır. Hareket isteniyorsa sıradaki adım o; istenmiyorsa (eski çağıran) biter.
     *
     * Kare seçimi hareketten ÖNCE biter: hareket sırasında ağır bitmap işi (döndürme, hizalama,
     * gömme) kare hızını düşürür ve çift kırpmanın ikincisini kaçırtır — kayıtta sahada yaşandı.
     */
    private fun onBestFrameReady() {
        if (finished || movePhase) return
        if (loginEvent == null) { succeedAndFinish(); return }
        movePhase = true
        binding.tvMatchScore.visibility = View.GONE
        binding.tvQualityWarning.visibility = View.GONE
        startMove()
    }

    private fun startMove() {
        val event = loginEvent ?: return
        moveAttempts++
        moveNudged = false
        moveCollector?.abandon()
        binding.faceFrameOverlay.setTimeProgress(1f)
        moveCollector = EventCollector(
            cacheDir = cacheDir,
            events = listOf(event),
            onGuidance = { g -> renderMove(g) },
            onTimeLeft = { f -> runOnUiThread { onMoveTimeLeft(f) } },
            onFailed = { failure -> runOnUiThread { onMoveFailed(failure) } },
            onComplete = { result -> onMoveComplete(result) },
        ).also { it.start() }
        startMoveTicker()
    }

    /** Yüz kaybolunca da süre işlesin diye kare akışından bağımsız saat (kayıttakinin aynısı). */
    private fun startMoveTicker() {
        stopMoveTicker()
        val r = object : Runnable {
            override fun run() {
                val ec = moveCollector ?: return
                if (!ec.isActive) return
                ec.tick()
                binding.root.postDelayed(this, 250)
            }
        }
        moveTicker = r
        binding.root.postDelayed(r, 250)
    }

    private fun stopMoveTicker() {
        moveTicker?.let { binding.root.removeCallbacks(it) }
        moveTicker = null
    }

    private fun onMoveTimeLeft(fraction: Float) {
        binding.faceFrameOverlay.setTimeProgress(fraction)
        if (fraction <= FaceFrameOverlayView.LOW_TIME_FRACTION && !moveNudged) {
            moveNudged = true
            feedback.nudge()
        }
    }

    /** Kayıt ekranındaki yönlendirmenin aynısı — tek adım, sayaç yok. */
    private fun renderMove(g: EventCollector.Guidance) {
        runOnUiThread {
            if (isFinishing || isDestroyed || finished) return@runOnUiThread
            if (g.stepDone) feedback.stepOk()

            val notice = when {
                g.resetReason != null -> getString(R.string.liveness_ev_reset_face)
                g.wrong != null -> getString(
                    R.string.liveness_wrong_move_detail,
                    getString(
                        if (g.wrong == EventCollector.Event.MOUTH_OPEN) R.string.liveness_did_mouth_open
                        else R.string.liveness_did_smile))
                else -> null
            }
            if (notice != null) feedback.wrong()

            val overlay = binding.faceFrameOverlay
            val (headline, detail) = when (g.phase) {
                EventCollector.Phase.SETTLE -> {
                    val placed = g.framing == EventCollector.Framing.OK
                    overlay.setState(
                        if (placed && !g.needsRelax) FaceFrameOverlayView.STATE_ALIGNED
                        else FaceFrameOverlayView.STATE_WAITING)
                    getString(
                        when {
                            g.framing == EventCollector.Framing.TOO_SMALL -> R.string.liveness_ev_closer
                            g.framing == EventCollector.Framing.TOO_LARGE -> R.string.liveness_ev_farther
                            !placed -> R.string.liveness_ev_place
                            g.needsRelax -> R.string.liveness_face_smile_relax
                            else -> R.string.liveness_ev_hold
                        }) to (notice ?: getString(R.string.liveness_ev_hold_hint))
                }
                EventCollector.Phase.EVENT -> {
                    overlay.setState(FaceFrameOverlayView.STATE_ALIGNED)
                    (if (g.needsRelax) getString(R.string.liveness_face_smile_relax)
                     else getString(g.event.commandRes)) to (notice ?: when {
                        g.needsRelax -> getString(R.string.liveness_ev_relax_hint)
                        g.eventCount == 1 -> getString(R.string.liveness_ev_again)
                        else -> getString(g.event.hintRes)
                    })
                }
                EventCollector.Phase.AFTER_EVENT -> {
                    overlay.setState(FaceFrameOverlayView.STATE_ALIGNED)
                    "✅" to ""
                }
                EventCollector.Phase.DONE -> return@runOnUiThread
            }
            showMoveText(headline, detail)
        }
    }

    /** Komut büyük ve koyu, nasıl yapılacağı altında — durum satırının yerinde, düzen değişmeden. */
    private fun showMoveText(headline: String, detail: String) {
        val text = android.text.SpannableStringBuilder(headline)
        val end = headline.length
        val flags = android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, end, flags)
        text.setSpan(android.text.style.RelativeSizeSpan(1.5f), 0, end, flags)
        text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK), 0, end, flags)
        if (detail.isNotEmpty()) text.append("\n").append(detail)
        binding.tvStatus.text = text
    }

    private fun onMoveFailed(failure: EventCollector.Failure) {
        stopMoveTicker()
        if (finished) return
        AppLog.info(
            "Giriş hareketi başarısız (${failure.name}, deneme $moveAttempts/$MAX_MOVE_ATTEMPTS) " +
                "iz=${moveCollector?.traceText?.takeLast(600) ?: ""}", "LoginFace")
        if (moveAttempts < MAX_MOVE_ATTEMPTS) {
            feedback.wrong()
            Toast.makeText(this, R.string.login_face_move_retry, Toast.LENGTH_SHORT).show()
            startMove()
        } else {
            failAndFinish(FAIL_REASON_MOVE)
        }
    }

    /** Kareleri okuyup kanıtı dosyaya yazar — dosya işi kamera kuyruğunda, ana iş parçacığında değil. */
    private fun onMoveComplete(result: EventCollector.Result) {
        AppLog.info(
            "Giriş hareketi tamam: kare=${result.steps.sumOf { 1 + it.eventPaths.size }} " +
                "yanlış=${result.wrongEvents} sıfırlama=${result.resets} süre=${result.elapsedMs}ms", "LoginFace")
        cameraExecutor.execute {
            val path = runCatching { writeMoveProof(result) }.getOrElse {
                AppLog.error("Giriş hareketi kanıtı yazılamadı", "LoginFace", it)
                null
            }
            runOnUiThread {
                stopMoveTicker()
                if (path == null) { failAndFinish(FAIL_REASON_MOVE); return@runOnUiThread }
                moveProofPath = path
                succeedAndFinish()
            }
        }
    }

    /**
     * Kayıttaki olay dizisi kanıtıyla AYNI biçim, tek adım. Kare dosyaları okunur okunmaz silinir:
     * kullanıcının canlı yüzü, gönderim dışında hiçbir işe yaramıyor.
     */
    private fun writeMoveProof(result: EventCollector.Result): String {
        fun b64(path: String): String {
            val file = File(path)
            try {
                return android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } finally {
                file.delete()
            }
        }
        val proof = com.verifyblind.mobile.api.ChoreographyProof(
            steps = result.steps.map { s ->
                com.verifyblind.mobile.api.ChoreographyProofStep(
                    neutral = listOf(b64(s.neutralPath)),
                    event = s.eventPaths.map { b64(it) },
                    attempts = s.attempts,
                )
            },
            elapsedMs = result.elapsedMs,
            resets = result.resets,
            wrongEvents = result.wrongEvents,
            trackingChanges = result.trackingChanges,
            trace = result.trace,
        )
        val file = File(cacheDir, MOVE_PROOF_FILE)
        file.writeText(com.google.gson.Gson().toJson(proof))
        return file.absolutePath
    }

    private fun succeedAndFinish() {
        if (finished) return
        val selfie = userSelfiePath
        val crop = antiSpoofCropPath
        // Kırpma olmadan selfie GÖNDERİLMEZ: enclave canlılığı fail-closed uyguluyor, eksik kırpma
        // orada nasılsa reddedilir — kullanıcıyı ağ turu sonrası değil, burada uyar.
        if (selfie == null || crop == null) { failAndFinish(); return }
        // Hareket istendiyse kanıtı olmadan gönderilmez — "yapamadık" asla "geçti" değildir.
        if (loginEvent != null && moveProofPath == null) { failAndFinish(FAIL_REASON_MOVE); return }
        finished = true
        AppLog.info("Giriş karesi hazır (kalite=${bestQuality.toInt()})", "LoginFace")
        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra(EXTRA_USER_SELFIE, selfie)
            putExtra(EXTRA_ANTISPOOF_CROP, crop)
            putExtra(EXTRA_FRAME_METRICS, frameMetricsJson)
            moveProofPath?.let { putExtra(EXTRA_MOVE_PROOF, it) }
        })
        finish()
    }

    private fun failAndFinish(reason: String? = null) {
        if (finished) return
        finished = true
        moveCollector?.abandon()
        setResult(RESULT_CANCELED, android.content.Intent().apply {
            reason?.let { putExtra(EXTRA_FAIL_REASON, it) }
        })
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
        stopMoveTicker()
        moveCollector?.abandon()
        if (::feedback.isInitialized) feedback.release()
        runCatching { cameraExecutor.shutdown() }
    }
}
