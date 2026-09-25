package com.verifyblind.mobile

import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.verifyblind.mobile.databinding.ActivityLivenessBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.EventCollector
import com.verifyblind.mobile.util.LivenessAnalyzer
import com.verifyblind.mobile.view.FaceFrameOverlayView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LivenessActivity : BaseActivity() {

    companion object {
        const val MATCH_THRESHOLD = 0.65f
        // Netlik (computeSharpness) eşikleri — 112×112 yüz kırpması için ort. gradyan enerjisi.
        // ALTINDA "net değil" uyarısı verilir ve kalite bonusu sıfırlanır. CİHAZDA kalibre edilmeli.
        private const val BLUR_WARN_THRESHOLD = 45f
        // Koşu başladıktan sonra "yüz yok" demeden önce beklenen süre — kamera ısınsın, kullanıcı
        // telefonu yerleştirsin diye. Bu süre içinde uyarmak her koşuyu bir azarla açardı.
        private const val NO_FACE_GRACE_MS = 2000L
        // Yüzün kaç ms kayıp kalması uyarıyı hak eder. Dedektör yüzü kısa süre kaybedebiliyor;
        // eşik bunun üstünde olmalı yoksa uyarı yanıp söner.
        private const val NO_FACE_WARN_MS = 1500L
        private const val SHARP_QUALITY_REF = 250f   // bu enerjide tam +15 kalite bonusu

        /** Demo dizisi — gerçek sunucu dizisi yoksa (demo akışı handshake'siz de çalışabilir). */
        private val DEMO_EVENTS = listOf(
            EventCollector.Event.BLINK, EventCollector.Event.SMILE, EventCollector.Event.MOUTH_OPEN)
    }

    private lateinit var binding: ActivityLivenessBinding
    private lateinit var cameraExecutor: ExecutorService
    private var originalBrightness = -1f

    private var isDemo = false

    // Result Paths
    private var userSelfiePath: String? = null
    private var antiSpoofCropPath: String? = null

    /**
     * Üreticinin İKİNCİ ölçeği (4,0×) — **yalnız ölçüm, kapı değil.**
     *
     * Fotoğraf ölçümünde bu ölçek ekranlara daha yüksek "canlı" puanı verdi ve topluluk
     * 2,7'nin tek başınadan kötü ayırdı. Yine de gerçek boru hattı (ön kamera, 1080p)
     * farklı davranabilir; kırpmayı taşımak ikinci bir mobil sürümü gereksiz kılıyor.
     */
    private var antiSpoofCrop40Path: String? = null

    /**
     * Kırpmalarda GERÇEKTEN uygulanabilen ölçekler.
     *
     * Yüz kadrajda büyükse istenen ölçek kadraja sığmaz ve üreticinin kuralı onu küçültür.
     * Bunu kaydetmezsek "4,0 işe yaramadı" ile "hiç 4,0 besleyemedik" ayırt edilemez.
     */
    private var antiSpoofScale27 = 0f
    private var antiSpoofScale40 = 0f

    /**
     * OLAY DİZİSİ — sunucunun nonce'tan türettiği hareketler (bkz. [EventCollector]).
     *
     * Boşsa (sunucu göndermediyse ya da anlaşılamadıysa) akış BAŞLAMAZ: kanıtsız kayıt enclave'de
     * mağazadaki eski sürüm gibi kapısız geçerdi. Yeni istemcinin bu yola düşmesi bir sürüm
     * uyuşmazlığıdır, sessizce kabul edilmez.
     */
    private var events: List<EventCollector.Event> = emptyList()
    private var eventCollector: EventCollector? = null
    private var eventResult: EventCollector.Result? = null
    private var eventFailure: EventCollector.Failure? = null

    /** Toplayıcının kareden bağımsız saati — yüz kaybolunca da süre ve kayıp tespiti işlesin. */
    private var eventTicker: Runnable? = null

    /** "Süre azalıyor" dokunuşu adım başına bir kez. */
    private var nudgedStep = -1

    /** Kılavuz bu ekranda bir kez gösterilir; "Tekrar dene" onu yeniden göstermez. */
    private var guideShown = false

    /**
     * Bu karenin iç dudak açıklığı — analizör [processFace]'ten HEMEN ÖNCE, aynı iş parçacığında
     * yazar; toplayıcıya verilir ve tüketilir (bir sonraki kareye taşınmasın).
     */
    @Volatile private var pendingLipOpen: Float? = null

    /**
     * Çip fotoğrafının MODELE GİREN hâli (hizalanmış 112×112). Ham DG2 değil: teşhis için gereken
     * şey karşılaştırmanın girdisidir, belgenin kendisi değil. Buradan hiçbir yere GİTMEZ —
     * yalnız geri bildirim kutusunda kullanıcı AYRI bir kutuyu işaretlerse e-postaya ek olur.
     */
    private var chipAlignedPath: String? = null

    // AI Matching
    private var faceEmbedder: com.verifyblind.mobile.util.FaceEmbedder? = null
    private var chipEmbedding: FloatArray? = null
    private var isIdentityVerified = false
    // Chip fotoğrafı VERİLDİ ama decode edilemedi (ör. JPEG2000). Demo modundan (chip_photo_path yok)
    // ayırt etmek için: bu bayrak set'liyse yüz eşleştirme SESSİZCE ATLANMAZ — sert başarısız olur.
    @Volatile private var chipDecodeFailed = false
    private var bestMatchScore = 0f

    /** Son başarısızlığın sebebi (sabit küme) — teşhis bloğuna yazılır. */
    private var lastFailureReason: String? = null

    /**
     * Canlı benzerlik akışı — canlılık sürerken enclave'e kare gönderir.
     *
     * ⚠️ Ekrandaki 0.65 göstergesi ve renk geri bildirimi BUNDAN ETKİLENMEZ. Kullanıcı anlık
     * skorunu görüp ortamı düzeltmeli, gözlüğünü çıkarmalı; o baskı ürünün kalitesini koruyor.
     * Enclave onayı yalnızca İKİNCİ bir submit yolu açar (bkz. [finishSuccess]).
     *
     * null = streaming yok (demo, chip yok ya da enclave anahtarı elde değil) → bugünkü davranış.
     */
    private var streamer: com.verifyblind.mobile.util.SimilarityStreamer? = null

    /** Oturum başlangıcı — kare ölçüsündeki `elapsed_ms` bundan hesaplanır. */
    private var sessionStartedAt = 0L

    /**
     * Kaydedilen en iyi karenin ölçüleri (JSON) — submit'te **1. adayın** ölçüm satırı olur.
     *
     * Neden ekranda tutuluyor: bu sayılar O KAREYE ait ve submit anında yeniden ölçülemezler
     * (kamera çoktan kapanmış olur).
     */
    private var bestFrameMetricsJson: String? = null

    /** Enclave'in onayladığı karenin ölçüleri — **2. adayın** ölçüm satırı olur. */
    private var approvedFrameMetricsJson: String? = null

    /// onCreate'te kurulur — LAZY OLAMAZ: ilk erişim ilk doğru harekette [stepOk] olurdu ve
    /// SoundPool'un asenkron yüklemesi o an başlayacağı için ilk onay sesi yutulurdu
    /// (bkz. LivenessFeedback.loadedSamples). Kamera/ML hazırlanırken yükleme çoktan biter.
    private lateinit var feedback: com.verifyblind.mobile.util.LivenessFeedback

    /// Huni telemetrisi için handshake nonce'u (demo'da yok → demo istatistiği kirletmez).
    private val flowNonce: String? by lazy { intent.getStringExtra("flow_nonce") }
    private var flowFailureReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info("onCreate başladı", "Liveness")
        binding = ActivityLivenessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // Ses/titreşim geri bildirimi: ses yüklemesi ŞİMDİ başlasın ki ilk onayda hazır olsun
        // (bkz. `feedback` alanının notu).
        feedback = com.verifyblind.mobile.util.LivenessFeedback(this)

        isDemo = intent.getBooleanExtra("is_demo", false)

        // Olay dizisi: bilinmeyen bir kod gelirse (ileri sürüm sunucu) dizi KULLANILMAZ — yarım
        // anlaşılmış bir diziyi yürütmek enclave'de "yapı bozuk" reddi demek.
        val codes = intent.getIntArrayExtra("choreo_events")
        val parsed = codes?.toList()?.mapNotNull { EventCollector.Event.of(it) }
        events = when {
            codes != null && parsed != null && parsed.isNotEmpty() && parsed.size == codes.size -> parsed
            isDemo -> DEMO_EVENTS
            else -> emptyList()
        }
        Log.d("Liveness", "Olay dizisi: $events (demo=$isDemo)")

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Canlı benzerlik akışı: yalnız gerçek akışta ve yalnız çip verisi + enclave anahtarı
        // varken. Demo huniyi ve ölçümü kirletmez.
        val streamFlowId = intent.getStringExtra("flow_id")
        val streamPubKey = intent.getStringExtra("enclave_pub_key")
        val streamDg2Path = intent.getStringExtra("dg2_path")
        if (!isDemo && !streamFlowId.isNullOrEmpty() && !streamPubKey.isNullOrEmpty() && !streamDg2Path.isNullOrEmpty()) {
            streamer = com.verifyblind.mobile.util.SimilarityStreamer(streamFlowId, streamPubKey).also { st ->
                // DG2 bir KEZ gider; enclave gömme vektörünü RAM'de tutar ve sonraki karelerde
                // yalnız selfie + kırpma gönderilir.
                st.prepare(runCatching { java.io.File(streamDg2Path).readBytes() }.getOrNull())
            }
        }

        try {
            startCamera()
        } catch (t: Throwable) {
             AppLog.error("Kamera başlatma başarısız", "Liveness", t)
             showMessage(getString(R.string.liveness_error_title), t.message ?: getString(R.string.error_unknown))
        }

        // Initial UI
        binding.tvInstruction.text = getString(R.string.liveness_preparing_tv)

        // Set bottom hint with actual threshold
        val thresholdPct = (MATCH_THRESHOLD * 100).toInt()
        binding.tvBottomHint.text = getString(R.string.liveness_threshold_hint, thresholdPct)

        // Save current brightness and set to full for best face recognition
        originalBrightness = window.attributes.screenBrightness
        val lp = window.attributes
        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp

        // Initialize AI
        initFaceMatching()
    }

    private fun initFaceMatching() {
        val chipPath = intent.getStringExtra("chip_photo_path")
        AppLog.sensitive("Chip foto yolu", chipPath, "Liveness")

        if (chipPath != null) {
            val chipFile = File(chipPath)
            if (chipFile.exists()) {
                AppLog.info("Chip dosyası mevcut (boyut: ${chipFile.length()})", "Liveness")
                cameraExecutor.submit {
                    try {
                        faceEmbedder = com.verifyblind.mobile.util.FaceEmbedder(this)
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        val bitmap = android.graphics.BitmapFactory.decodeFile(chipPath, opts)

                        if (bitmap != null) {
                            AppLog.info("Chip bitmap çözüldü (${bitmap.width}x${bitmap.height})", "Liveness")

                            // Chip fotoğrafında yüz landmark tespiti yaparak aligned embedding al
                            var leftEyePos: PointF? = null
                            var rightEyePos: PointF? = null
                            try {
                                val chipImage = InputImage.fromBitmap(bitmap, 0)
                                val chipDetector = FaceDetection.getClient(
                                    FaceDetectorOptions.Builder()
                                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                        .build()
                                )
                                val faces = Tasks.await(chipDetector.process(chipImage))
                                val chipFace = faces.firstOrNull()
                                leftEyePos  = chipFace?.getLandmark(FaceLandmark.LEFT_EYE)?.position
                                rightEyePos = chipFace?.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                                AppLog.sensitive("Chip landmark", "leftEye=$leftEyePos rightEye=$rightEyePos", "Liveness")
                            } catch (e: Exception) {
                                Log.w("Liveness", "Chip yüz tespiti başarısız, yedek embedding kullanılıyor", e)
                            }

                            chipEmbedding = faceEmbedder?.getEmbeddingAligned(bitmap, leftEyePos, rightEyePos)
                            val method = if (leftEyePos != null) "ALIGNED" else "FALLBACK"
                            AppLog.info("Chip embedding üretildi ($method, size=${chipEmbedding?.size})", "Liveness")

                            // Aynı hizalamayı teşhis için de saklıyoruz. Eşleştirme yolu BİLEREK
                            // değiştirilmedi: getAlignedBitmap deterministik, ikinci çağrı birebir
                            // aynı 112×112'yi üretir ve biyometrik karar yolu tek satır bile kaymaz.
                            try {
                                faceEmbedder?.getAlignedBitmap(bitmap, leftEyePos, rightEyePos)?.let { aligned ->
                                    val f = File(cacheDir, "chip_aligned.png")
                                    java.io.FileOutputStream(f).use {
                                        aligned.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    chipAlignedPath = f.absolutePath
                                }
                            } catch (e: Exception) {
                                AppLog.info("Hizalı çip kırpımı saklanamadı (teşhis dışı etkisi yok): ${e.javaClass.simpleName}", "Liveness")
                            }

                            runOnUiThread {
                                val ivChip = findViewById<android.widget.ImageView>(R.id.ivLiveChipPhoto)
                                ivChip.setImageBitmap(bitmap)
                            }
                        } else {
                            // Dosya VAR ama decode null → büyük olasılıkla JPEG2000 DG2 (Android çözemez).
                            // MainActivity hızlı-başarısızlık'ı bunu normalde liveness'e gelmeden durdurur;
                            // buraya düşerse savunma-derinliği: bayrağı set et, doğrulama kapısı sert dursun.
                            chipDecodeFailed = true
                            AppLog.error("Chip bitmap çözme başarısız (null) — muhtemelen JPEG2000 DG2; belge desteklenmiyor", "Liveness")
                        }
                    } catch (e: Exception) {
                        AppLog.error("AI başlatma istisnası", "Liveness", e)
                    }
                }
            } else {
                AppLog.warning("Chip foto dosyası bulunamadı", "Liveness")
            }
        } else {
            // Demo chip_photo_path'i BİLEREK göndermez (yüz eşleştirme yok) — beklenen durum, uyarı
            // seviyesinde loglamak Sentry kotasını boşuna yakıyordu. Gerçek akışta ise yokluğu bir
            // arızadır; orada uyarı kalır ve finishSuccess() kapıyı sert kapatır.
            if (isDemo) AppLog.info("Chip foto yolu yok (demo) — yüz eşleştirme atlanıyor", "Liveness")
            else AppLog.warning("Chip foto yolu intent'te null", "Liveness")
            runOnUiThread { hideMatchingUI() }
        }
    }

    private fun hideMatchingUI() {
        binding.tvBottomHint.visibility = View.GONE
        findViewById<android.widget.TextView>(R.id.tvLiveScore)?.visibility = View.GONE
        findViewById<android.widget.ImageView>(R.id.ivLiveChipPhoto)?.visibility = View.GONE
    }

    /**
     * Başarısız denemeden vazgeçiş — reddedilen kareyi TEŞHİS için geri veririz.
     *
     * Kare şimdiye dek yalnız BAŞARIDA dışarı veriliyordu, yani geri bildirim kutusundaki
     * "fotoğrafı ekle" kutucuğu tam da ihtiyaç duyduğumuz durumda — eşleşme düştüğünde —
     * hiç görünmüyordu. Oysa en değerli kare reddedilen karedir: "neden %55'te kaldı"
     * sorusunu yalnız o yanıtlıyor.
     *
     * Dosya cihazda kalır, sunucuya KENDİLİĞİNDEN gitmez; yalnız kullanıcı kutucuğu açıkça
     * işaretlerse destek e-postasına ek olur. iOS `LivenessViewModel.diagnosticJPEG` paritesi.
     */
    private fun finishWithDiagnostics() {
        AppLog.warning("Canlılık ekranından vazgeçildi → akıştan çıkılıyor", "Liveness")
        val intent = Intent()
        intent.putExtra("user_selfie", userSelfiePath)
        intent.putExtra("chip_aligned", chipAlignedPath)
        intent.putExtra("liveness_diag", buildDiagnostics())
        intent.putExtra("liveness_failed", didFail)
        setResult(RESULT_CANCELED, intent)
        finish()
    }

    /**
     * Geri bildirim e-postasına eklenen teşhis satırları.
     *
     * Neden gerekli: destek kutusuna bugüne kadar 112×112'lik bir kırpım gidiyordu ve YANINDA HİÇ
     * SAYI YOKTU — "benzerlik yetersiz" diyen kullanıcının skorunu, kare parlaklığını, kafa açısını
     * bilmeden sebebi tahmin etmekten başka şey yapılamıyordu. Buradaki her alan zaten hesaplanıyor
     * ve yalnızca cihazdaki loga yazılıyordu.
     *
     * Hepsi SKALER: biyometrik veri değil, görüntü değil. Gizlilik maliyeti sıfır, teşhis değeri
     * fotoğraftan yüksek — luma tek başına "arkadan ışık" hipotezini doğrular ya da çürütür.
     */
    private fun buildDiagnostics(): String = buildString {
        append("Canlılık / Liveness: skor=%").append((bestMatchScore * 100).toInt())
        append(" (cihaz eşiği %").append((MATCH_THRESHOLD * 100).toInt()).append(")")
        append(" adım=").append(eventCollector?.completedSteps ?: 0).append("/").append(events.size)
        eventFailure?.let { append(" hata=").append(it.name) }
        append(" yanlış=").append(eventCollector?.wrongCount ?: 0)
        append(" çip=").append(
            when {
                chipEmbedding != null -> "var"
                chipDecodeFailed -> "çözülemedi"
                else -> "yok"
            }
        )
        lastFailureReason?.let { append(" sebep=").append(it) }
        append("\n")
        append("Kare / Frame: ").append(savedFrameMetrics ?: "kare kaydedilmedi")
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

                // Analysis — maksimum çözünürlük (landmark hassasiyeti için kritik)
                val analysisBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // Kamera "güzelleştirme"/efekt + agresif yumuşatma filtrelerini kapat → biyometri için
                // mümkün olan en sadık görüntü. (OEM beautify standart bir Camera2 anahtarı DEĞİL; bunlar
                // efekt/gürültü/edge için en yakın portatif kontroller — desteklenmeyen anahtar sessizce
                // yok sayılır. NR/EDGE = FAST: çok-kareli HIGH_QUALITY yumuşatma/aşırı keskinleştirme yok.)
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
                    Log.w("Liveness", "Camera2 filtre ayarı atlandı: ${e.message}")
                }

                val imageAnalysis = analysisBuilder
                    .build()
                    .also {
                        Log.d("Liveness", "Analizör başlatılıyor...")
                        it.setAnalyzer(cameraExecutor, LivenessAnalyzer(
                            onFaceDetected = { face, imageProxy, others -> processFace(face, imageProxy, others) },
                            onFrameLuma = { luma -> onFrameLuma(luma) },
                            // Dudak konturu yalnız ağız açma adımında: ikinci dedektör kare hızını düşürür.
                            contourWanted = { eventCollector?.wantsContour == true },
                            onContour = { lip -> pendingLipOpen = lip },
                        ))
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                // Yalnız Preview + ImageAnalysis — CameraX'in HER cihazda garanti ettiği kombinasyon.
                // 3. use case (VideoCapture) eklemek StreamSharing'e zorluyor; bazı ön kameralar
                // (ör. OnePlus 8 Pro) bu yüzey kombinasyonunu desteklemeyip "No supported surface
                // combination" fırlatıyordu. Video kaydı zaten kullanılmıyordu (ölü kod), kaldırıldı.
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

                // AE/AF: yüzün geleceği merkeze odak + pozlama metering — sahnede gezinmeyi durdurur,
                // pozlama "av peşinde" gidip bazı kareleri yakıp karartmaz. disableAutoCancel → kalıcı.
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
                        Log.w("Liveness", "Metering ayarlanamadı: ${e.message}")
                    }
                }

                // Start Logic after camera init
                runOnUiThread {
                    startActionPhase()
                }

            } catch (exc: Throwable) { // Throwable olarak değiştirildi (Error'ları da yakalar)
                AppLog.error("Kamera başlatma başarısız", "Liveness", exc)
                runOnUiThread {
                    showMessage(getString(R.string.liveness_camera_error_title), exc.localizedMessage ?: getString(R.string.liveness_camera_start_failed))
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // --- KOŞU ---

    private fun startActionPhase() {
        // Yeni koşu: eski en iyi kare silinir, eşleşme sıfırdan ölçülür.
        userSelfiePath?.let { java.io.File(it).delete() }
        userSelfiePath = null
        bestMatchScore = 0f
        bestSavedMatchScore = -1f
        bestSavedQualityScore = -1f
        isIdentityVerified = false

        binding.faceFrameOverlay.visibility = View.VISIBLE
        binding.faceFrameOverlay.setState(FaceFrameOverlayView.STATE_WAITING)
        binding.faceFrameOverlay.setTimeProgress(-1f)

        findViewById<android.widget.TextView>(R.id.tvLiveScore)?.let {
            it.text = ""
            it.setTextColor(android.graphics.Color.WHITE)
        }

        savedFrameMetrics = null
        lastFaceTimeMs = 0L
        noFaceWarning = null

        if (events.isEmpty()) {
            AppLog.error("Olay dizisi yok — sunucu göndermedi ya da anlaşılamadı; akış başlatılmıyor", "Liveness")
            showMessage(getString(R.string.liveness_error_title), getString(R.string.liveness_ev_missing)) { finish() }
            return
        }

        if (!guideShown) {
            guideShown = true
            showGuide()
            return
        }
        beginRun()
    }

    /**
     * Başlamadan önceki kılavuz: ışık, telefonun tutuluşu, aksesuarlar ve hareketlerin NASIL
     * yapılacağı. Kamera arkada ısınır; kullanıcı "Başla"ya basınca koşu başlar.
     */
    private fun showGuide() {
        binding.tvGuideMovesTitle.text = getString(R.string.liveness_guide_moves_title, events.size)
        binding.guideOverlay.visibility = View.VISIBLE
        binding.btnGuideStart.setOnClickListener {
            binding.guideOverlay.visibility = View.GONE
            beginRun()
        }
    }

    private fun beginRun() {
        sessionStartedAt = System.currentTimeMillis()
        runStartedAt = System.currentTimeMillis()
        lastFaceTimeMs = 0L
        if (isDemo) runDemoEvents(0) else startEventPhase()
    }

    // --- DEMO: Sahte canlılık — gerçek hareket beklemeden her adımı sahneler ---

    /** Demo akışı: hareketi göster, 1 sn bekle, ✅ ile işaretle ve sonrakine geç. */
    private fun runDemoEvents(step: Int) {
        if (isFinishing || isDestroyed) return
        if (step >= events.size) {
            finishSuccess()
            return
        }
        binding.tvStepCounter.text = "${step + 1}/${events.size}"
        binding.tvInstruction.text = eventText(events[step])
        binding.tvSubInstruction.text = eventHint(events[step])
        binding.tvSubInstruction.visibility = View.VISIBLE
        binding.faceFrameOverlay.setState(FaceFrameOverlayView.STATE_ALIGNED)
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            feedback.stepOk()   // demo gerçek akışı temsil etmeli (aynı ses/titreşim)
            binding.tvInstruction.text = "✅"
            binding.tvSubInstruction.text = ""
            binding.root.postDelayed({ runDemoEvents(step + 1) }, 300)
        }, 1000)
    }

    // --- OLAY DİZİSİ ---

    private fun startEventPhase() {
        eventCollector?.abandon()
        eventResult = null
        eventFailure = null
        nudgedStep = -1
        binding.tvSubInstruction.visibility = View.VISIBLE
        binding.faceFrameOverlay.setTimeProgress(1f)

        eventCollector = EventCollector(
            cacheDir = cacheDir,
            events = events,
            onGuidance = { g -> renderEventGuidance(g) },
            onTimeLeft = { f -> runOnUiThread { onTimeLeft(f) } },
            onFailed = { failure -> runOnUiThread { onEventsFailed(failure) } },
            // Hareket başına süre ve yanlış sayısı — "hangi hareket zor" sorusunun cevabı.
            onEventResolved = { ev, durationMs, wrong, timedOut ->
                com.verifyblind.mobile.util.FlowTelemetry.gestureResolved(
                    step = ev.telemetryStep,
                    durationMs = durationMs,
                    wrongCount = wrong,
                    timedOut = timedOut,
                    nonce = flowNonce,
                )
            },
            onComplete = { result ->
                eventResult = result
                AppLog.info(
                    "Olay dizisi tamam: adım=${result.steps.size} " +
                        "kare=${result.steps.sumOf { 1 + it.eventPaths.size }} " +
                        "sıfırlama=${result.resets} yanlış=${result.wrongEvents} " +
                        "takip-değişimi=${result.trackingChanges} süre=${result.elapsedMs}ms",
                    "Liveness"
                )
                runOnUiThread {
                    stopEventTicker()
                    finishSuccess()
                }
            },
        ).also { it.start() }
        startEventTicker()
    }

    private fun startEventTicker() {
        stopEventTicker()
        val r = object : Runnable {
            override fun run() {
                val ec = eventCollector ?: return
                if (!ec.isActive) return
                ec.tick()
                binding.root.postDelayed(this, 250)
            }
        }
        eventTicker = r
        binding.root.postDelayed(r, 250)
    }

    private fun stopEventTicker() {
        eventTicker?.let { binding.root.removeCallbacks(it) }
        eventTicker = null
    }

    /** Kalan süre çerçevede erir; azaldığında adım başına bir kez sessiz dokunuş. */
    private fun onTimeLeft(fraction: Float) {
        binding.faceFrameOverlay.setTimeProgress(fraction)
        val step = eventCollector?.completedSteps ?: 0
        if (fraction <= FaceFrameOverlayView.LOW_TIME_FRACTION && nudgedStep != step) {
            nudgedStep = step
            feedback.nudge()
        }
    }

    /**
     * Olay dizisi başarısız. Sunucuya giden sebep sabit kümeden ([EventCollector.Failure.flowReason]);
     * ayrıntı teşhis bloğunda.
     */
    private fun onEventsFailed(failure: EventCollector.Failure) {
        stopEventTicker()
        eventFailure = failure
        // 🔴 İZ KAYDI SENTRY'YE: "neden takıldı / neden yanlış hareket" sorusunun cevabı başka
        // hiçbir yerde yok (cihaz log tamponu dakikalar içinde siliniyor). Mesaj sabit (Sentry her
        // denemeyi ayrı sorun açmasın), ayrıntı ek alanda.
        AppLog.warning(
            "Olay dizisi başarısız: ${failure.name}", "Liveness",
            extras = mapOf(
                "event_trace" to (eventCollector?.traceText ?: ""),
                "event_progress" to "${eventCollector?.completedSteps ?: 0}/${events.size}",
            ),
        )
        when (failure) {
            EventCollector.Failure.TOO_MANY_WRONG -> showFailureSummary(
                customTitle = getString(R.string.liveness_too_many_errors_title),
                customMessage = getString(R.string.liveness_too_many_errors_message),
                flowReason = failure.flowReason,
            )
            EventCollector.Failure.TOO_MANY_RESETS -> showFailureSummary(
                customTitle = getString(R.string.liveness_ev_resets_title),
                customMessage = getString(R.string.liveness_ev_resets_message),
                flowReason = failure.flowReason,
            )
            EventCollector.Failure.TIMEOUT_SETTLE -> showFailureSummary(
                customTitle = getString(R.string.liveness_ev_settle_timeout_title),
                customMessage = getString(R.string.liveness_ev_settle_timeout_message),
                flowReason = failure.flowReason,
            )
            EventCollector.Failure.TIMEOUT_EVENT -> showFailureSummary(isTimeout = true, flowReason = failure.flowReason)
        }
    }

    private fun eventText(event: EventCollector.Event): String = getString(
        when (event) {
            EventCollector.Event.BLINK -> R.string.liveness_face_blink
            EventCollector.Event.SMILE -> R.string.liveness_face_smile
            EventCollector.Event.MOUTH_OPEN -> R.string.liveness_face_mouth_open
            EventCollector.Event.DOUBLE_BLINK -> R.string.liveness_face_double_blink
        }
    )

    /** Hareketin NASIL yapılacağı — komutun altında, komutla aynı anda. */
    private fun eventHint(event: EventCollector.Event): String = getString(
        when (event) {
            EventCollector.Event.BLINK -> R.string.liveness_ev_hint_blink
            EventCollector.Event.SMILE -> R.string.liveness_ev_hint_smile
            EventCollector.Event.MOUTH_OPEN -> R.string.liveness_ev_hint_mouth_open
            EventCollector.Event.DOUBLE_BLINK -> R.string.liveness_ev_hint_double_blink
        }
    )

    /**
     * Olay dizisinin görsel rehberliği.
     *
     * Hareket, yüz yerleşip gevşedikten ve nötr kare alındıktan SONRA söylenir — önceden
     * gösterilirse kullanıcı hareketi erken yapar ve gevşemesi gerekir. Çerçeve kırmızı = henüz
     * değil, yeşil = tamam; kalan süre çerçevede erir.
     */
    private fun renderEventGuidance(g: EventCollector.Guidance) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            binding.tvStepCounter.text = "${g.stepIndex + 1}/${g.stepCount}"
            if (g.stepDone) feedback.stepOk()

            // Adım baştan başladıysa ya da yanlış hareket yapıldıysa kullanıcı NEDENİNİ görmeli.
            val notice = when {
                g.resetReason != null -> getString(R.string.liveness_ev_reset_face)
                g.wrong != null -> getString(
                    R.string.liveness_wrong_move_detail,
                    getString(
                        if (g.wrong == EventCollector.Event.MOUTH_OPEN) R.string.liveness_did_mouth_open
                        else R.string.liveness_did_smile))
                else -> null
            }
            if (notice != null) {
                feedback.wrong()
                Toast.makeText(this, notice, Toast.LENGTH_SHORT).show()
            }

            when (g.phase) {
                EventCollector.Phase.SETTLE -> {
                    val placed = g.framing == EventCollector.Framing.OK
                    binding.faceFrameOverlay.setState(
                        if (placed && !g.needsRelax) FaceFrameOverlayView.STATE_ALIGNED
                        else FaceFrameOverlayView.STATE_WAITING)
                    binding.tvInstruction.text = getString(
                        when {
                            g.framing == EventCollector.Framing.TOO_SMALL -> R.string.liveness_ev_closer
                            g.framing == EventCollector.Framing.TOO_LARGE -> R.string.liveness_ev_farther
                            !placed -> R.string.liveness_ev_place
                            g.needsRelax -> R.string.liveness_face_smile_relax
                            else -> R.string.liveness_ev_hold
                        })
                    binding.tvSubInstruction.text = notice ?: getString(R.string.liveness_ev_hold_hint)
                }
                EventCollector.Phase.EVENT -> {
                    binding.faceFrameOverlay.setState(FaceFrameOverlayView.STATE_ALIGNED)
                    binding.tvInstruction.text =
                        if (g.needsRelax) getString(R.string.liveness_face_smile_relax)
                        else eventText(g.event)
                    binding.tvSubInstruction.text = notice ?: when {
                        g.needsRelax -> getString(R.string.liveness_ev_relax_hint)
                        g.eventCount == 1 -> getString(R.string.liveness_ev_again)
                        else -> eventHint(g.event)
                    }
                }
                EventCollector.Phase.AFTER_EVENT -> {
                    binding.faceFrameOverlay.setState(FaceFrameOverlayView.STATE_ALIGNED)
                    binding.tvInstruction.text = "✅"
                    binding.tvSubInstruction.text = ""
                }
                EventCollector.Phase.DONE -> Unit
            }
        }
    }

    // ── Yüz sürekliliği ────────────────────────────────────────────────────────
    //
    // 🔴 Kapatmaya çalıştığımız saldırı: ekranda kart sahibinin yüzü, hareketleri KADRAJDAKİ
    // BAŞKA BİRİ yapıyor. Benzerlik bir kaynaktan, canlılık başka kaynaktan geliyor.
    //
    // ML Kit her yüze bir takip numarası veriyordu (`enableTracking()` açık) ama kod bunu
    // hiç okumuyordu; fazladan yüzler de `faces[0]` alınıp sessizce atılıyordu.

    /** İlk görülen takip numarası — akış boyunca aynı kişinin beklendiği referans. */
    private var establishedTrackingId: Int? = null

    /**
     * Takip numarasının kaç kez değiştiği — YALNIZ ÖLÇÜM.
     *
     * Yüz kaybolup geri gelince ML Kit YENİ numara verir ve o an "aynı kişi döndü" ile
     * "başkası girdi" birbirinden ayırt EDİLEMEZ. Bu yüzden numara değişimi tek başına red
     * sebebi yapılmadı; kaç sıklıkta olduğunu öğrenip sonra karar vereceğiz.
     */
    private var trackingIdChanges = 0

    /** Ardışık kaç karede ikinci bir yüz görüldü. Tek karelik hayalet tespit reddetmemeli. */
    private var multiFaceFrames = 0

    /** ~1 saniye boyunca ikinci yüz → akış durur. 400ms fren yok, kare hızı ~10-30fps. */
    private val multiFaceFrameLimit = 12

    @Volatile private var continuityFailed = false

    /**
     * Her karede yüz sürekliliğini işler.
     *
     * Kadrajda ana yüzün yarısından büyük ikinci bir yüz [multiFaceFrameLimit] kare boyunca
     * ARDIŞIK görülürse akış başarısız olur. Arkadan geçen birinin tek karede görünmesi
     * yetmez; küçük/uzak yüzler analizörde zaten elenir.
     */
    private fun noteFaceContinuity(face: com.google.mlkit.vision.face.Face, otherFaceCount: Int) {
        if (isDemo || continuityFailed) return

        face.trackingId?.let { id ->
            if (establishedTrackingId == null) establishedTrackingId = id
            else if (establishedTrackingId != id) {
                trackingIdChanges++
                establishedTrackingId = id
            }
        }

        if (otherFaceCount > 0) {
            multiFaceFrames++
            if (multiFaceFrames >= multiFaceFrameLimit) {
                continuityFailed = true
                AppLog.warning(
                    "Canlılık durduruldu: kadrajda ikinci yüz ($multiFaceFrames kare)", "Liveness")
                runOnUiThread {
                    eventCollector?.abandon()
                    stopEventTicker()
                    streamer?.release("too_many_errors")
                    showMessage(
                        getString(R.string.liveness_multi_face_title),
                        getString(R.string.liveness_multi_face_message)
                    ) { finish() }
                }
            }
        } else {
            multiFaceFrames = 0
        }
    }

    // --- KARE AKIŞI ---

    private fun processFace(face: com.google.mlkit.vision.face.Face, imageProxy: androidx.camera.core.ImageProxy,
                            otherFaceCount: Int = 0) {
        lastFaceTimeMs = System.currentTimeMillis()
        noteFaceContinuity(face, otherFaceCount)
        try {
            if (isDemo) {
                captureFrame(imageProxy, face, calculateQualityScore(face, imageProxy.width, imageProxy.height))
                return
            }
            // Kılavuz ekrandayken ya da dizi bittiyse kareler işlenmez.
            val ec = eventCollector
            if (ec == null || !ec.isActive) return

            val lip = pendingLipOpen.also { pendingLipOpen = null }
            ec.offer(imageProxy, face, lip)
            // 🔴 Olay beklenirken selfie adayı (bitmap + ArcFace) ERTELENİR: bu iş ana iş
            // parçacığında çalışıyor ve sonraki kare ancak bu kare kapanınca geliyor. Sahada çift
            // kırpmanın ikincisi arada kaldı. Adaylar yerleşme ve onay anlarında zaten toplanıyor.
            if (!ec.quietPhase) {
                captureFrame(imageProxy, face, calculateQualityScore(face, imageProxy.width, imageProxy.height))
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun calculateQualityScore(face: com.google.mlkit.vision.face.Face, imgW: Int, imgH: Int): Float {
        var score = 100f

        // 1. Head Euler Angles (Penalty for looking away)
        val x = Math.abs(face.headEulerAngleX) // Up/Down
        val y = Math.abs(face.headEulerAngleY) // Left/Right
        val z = Math.abs(face.headEulerAngleZ) // Tilt

        if (x > 10) score -= (x - 10) * 2
        if (y > 10) score -= (y - 10) * 2
        if (z > 10) score -= (z - 10) * 2

        // 2. Eyes Open (Penalty for blinking)
        val leftEye = face.leftEyeOpenProbability ?: 0.5f // Default 0.5 if missing
        val rightEye = face.rightEyeOpenProbability ?: 0.5f

        if (leftEye < 0.8f) score -= (0.8f - leftEye) * 50
        if (rightEye < 0.8f) score -= (0.8f - rightEye) * 50

        // 3. Centering (Penalty for being on edge)
        val centerX = face.boundingBox.centerX()
        val centerY = face.boundingBox.centerY()
        val imgCX = imgW / 2
        val imgCY = imgH / 2

        val distX = Math.abs(centerX - imgCX)
        val distY = Math.abs(centerY - imgCY)

        score -= (distX.toFloat() / imgW) * 20
        score -= (distY.toFloat() / imgH) * 20

        // 4. Size (Penalty for too small/far)
        if (face.boundingBox.width() < imgW * 0.25f) score -= 30

        return score.coerceIn(0f, 100f)
    }

    // ── Ortam kalitesi uyarısı (ışık) ─────────────────────────────────────────
    // Y-luma'dan karanlık/aşırı-parlak tespiti → anlık kırmızı label (dialog DEĞİL).
    // Her karede çağrılır ama UI'a yalnızca durum DEĞİŞİNCE dokunur (main-thread spam'i önler).
    @Volatile private var lastQualityWarning: String? = "__init__"
    @Volatile private var lastLuma: Float = -1f   // en son ölçülen ortalama parlaklık (0-255)

    /**
     * Sunucuya GİDEN kareye ait kalite ölçüleri. Pasif canlılık (anti-spoof) reddi tek bir skaler
     * olarak geliyor ve görüntüyü SAKLAMIYORUZ (ZK); geriye dönük "neden sahte sanıldı" sorusunu
     * ancak bu skalerler yanıtlayabilir — ışık, netlik, poz, yüzün kadrajdaki payı.
     */
    @Volatile private var savedFrameMetrics: String? = null

    @Volatile private var lumaWarning: String? = null   // ışık (her kare — analiz thread'i)
    @Volatile private var blurWarning: String? = null   // netlik (best-frame yakalamada)
    @Volatile private var noFaceWarning: String? = null // yüz kadrajda değil (analiz thread'i)

    // Yüzün en son ne zaman görüldüğü ve koşunun ne zaman başladığı — ikisi de analiz
    // thread'inde yazılıp okunur. Yüz bulunamayan kareler `LivenessAnalyzer` içinde sessizce
    // atılıyor; "hiç yüz gelmiyor" bilgisini ancak buradaki zaman farkından çıkarabiliyoruz.
    @Volatile private var lastFaceTimeMs = 0L
    @Volatile private var runStartedAt = 0L

    // Bu ekranda en az bir kez başarısızlık özeti gösterildi mi. Sonuçla birlikte MainActivity'ye
    // taşınır: hatayla karşılaşmış birine "neden yarıda bıraktınız?" demek yanlış soru.
    private var didFail = false

    private fun onFrameLuma(luma: Float) {
        lastLuma = luma

        // "Yüzünüz çerçevede değil" — ışık/netlik uyarılarının ÖNÜNDE gelir.
        //
        // Yüz bulunamayan kare sessizce atılıyordu: kullanıcı 15 saniye boyunca hiçbir geri
        // bildirim almadan bekliyor, sonunda "Süre doldu — hareket tamamlanmadı" yiyordu.
        //
        // Yüzün neden bulunamadığı DEĞİŞKEN (kadraj, ışık, dedektörün kendi arızası) ve buradan
        // bilinemez. Mesele sebep değil, sessizlik: uygulama kare gelmediğini zaten biliyorken
        // susup faturayı kullanıcıya kesiyordu. iOS'ta aynı boşluk vardı, aynı anda kapatıldı.
        val now = System.currentTimeMillis()
        val runningLongEnough = runStartedAt > 0 && now - runStartedAt > NO_FACE_GRACE_MS
        val faceGone = lastFaceTimeMs == 0L || now - lastFaceTimeMs > NO_FACE_WARN_MS
        noFaceWarning = if (!isDemo && runningLongEnough && faceGone)
            getString(R.string.liveness_quality_no_face) else null
        lumaWarning = when {
            luma < 55f  -> getString(R.string.liveness_quality_dark)
            luma > 235f -> getString(R.string.liveness_quality_bright)
            else        -> null
        }
        publishQualityWarning()
    }

    /** Yüz (öncelikli) + ışık + netlik uyarısını tek label'da birleştirir; yalnız değişince UI'a dokunur. */
    private fun publishQualityWarning() {
        // Sıra önemli: yüz kadrajda değilken "ortam karanlık" demek yanlış hedefi gösterir.
        val msg = noFaceWarning ?: lumaWarning ?: blurWarning
        if (msg == lastQualityWarning) return
        lastQualityWarning = msg
        runOnUiThread {
            binding.tvQualityWarning.text = msg ?: ""
            binding.tvQualityWarning.visibility = if (msg != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * 112×112 hizalanmış yüz kırpmasında ortalama gradyan enerjisi (Brenner benzeri) — netlik/odak
     * ölçüsü; yüksek = net. Sabit 112 boyut sayesinde eşik anlamlı (yine de cihazda ince ayar gerekebilir).
     * Bulanık/kirli lens veya hareket bulanıklığı düşük değer verir.
     */
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

    private fun finishSuccess() {
        // Kalite ölçüleri BAŞARIDA da yazılır: sunucudaki anti-spoof reddi buradan SONRA gelir,
        // yani "canlılık geçti ama sunucu sahte dedi" vakasında elimizdeki tek ipucu bu satır.
        AppLog.info(
            "Liveness başarı: skor=${(bestMatchScore * 100).toInt()}% " +
                "[${savedFrameMetrics ?: "kare ölçüsü yok"}]",
            "Liveness"
        )
        feedback.done()
        if (isDemo) {
            // Demo: gerçek selfie/yüz eşleşmesi gerekmez, doğrudan başarıyla dön
            binding.root.postDelayed({
                restoreBrightness()
                val intent = Intent()
                intent.putExtra("user_selfie", userSelfiePath)
                intent.putExtra("antispoof_crop", antiSpoofCropPath)
                intent.putExtra("chip_aligned", chipAlignedPath)
                intent.putExtra("liveness_diag", buildDiagnostics())
                setResult(RESULT_OK, intent)
                finish()
            }, 500)
            return
        }
        // Check if we have a valid selfie
        if (userSelfiePath == null) {
            runOnUiThread {
                 showMessage(getString(R.string.liveness_selfie_error_title), getString(R.string.liveness_selfie_error_message)) {
                     startActionPhase()
                 }
            }
            return
        }

        // Check AI Verification
        if (chipEmbedding != null) {
            // ⚠️ SUBMIT'İN İKİ YOLU VAR (canlı benzerlik akışı):
            //   (1) cihaz skoru 0.65'i geçti  → isIdentityVerified
            //   (2) enclave "benzerlik geçti" dedi → streamer.hasEnclaveApproval
            //
            // İkincisi bir güvenlik gevşemesi DEĞİLDİR: cihazdaki 0.65 hiçbir zaman güvenlik
            // kontrolü değildi (yerel bir boolean, yamalanabilir) ve gerçek karar hep
            // enclave'de. Burada olan şey, enclave'in ZATEN onayladığı bir kareyi cihazın
            // kendi ön elemesiyle çöpe atmasını engellemek. Diğer koşullar (hareketler, selfie
            // varlığı) aynen aranır.
            if (!isIdentityVerified && streamer?.hasEnclaveApproval != true) {
                showFailureSummary(isTimeout = false)
                return
            }
        } else if (chipDecodeFailed) {
            // Chip fotoğrafı verildi ama çözülemedi (JPEG2000 vb.). Yüz eşleştirmesi YAPILAMADI →
            // SESSİZCE geçme (eski davranış bir güvenlik boşluğuydu: doğrulanmamış kayıt). Sert dur.
            AppLog.error("Yüz eşleştirme atlanamaz: chip fotoğrafı decode edilemedi — kayıt durduruldu", "Liveness")
            showFailureSummary(isTimeout = false)
            return
        } else if (intent.getStringExtra("chip_photo_path") == null) {
            // Buraya YALNIZ gerçek akış düşer — demo yukarıda erken return ediyor. Yani chip_photo_path'in
            // hiç gönderilmemiş olması "beklenen durum" değil, MainActivity'nin chip fotoğrafını diske
            // yazamadığı bir arızadır (faceImage'i olmayan belgeyi DocumentSupport zaten liveness'e hiç
            // bırakmıyor). Yüz eşleştirmesi YAPILAMADI → chipDecodeFailed ile aynı sonuç: doğrulanmamış
            // kayıt geçmemeli. Intent'ten okunur, alan değil: yarış yok, karar anında kesin.
            AppLog.error("Yüz eşleştirme atlanamaz: chip fotoğrafı intent'te yok — kayıt durduruldu", "Liveness")
            showFailureSummary(isTimeout = false)
            return
        } else {
            // Chip yolu VERİLMİŞTİ ama embedding elde yok. Üç ayrı sebebi olabilir ve buradan hangisi
            // olduğu ayırt EDİLEMEZ: (a) dosya bulunamadı, (b) FaceEmbedder istisna attı,
            // (c) embedding hâlâ cameraExecutor'da hesaplanıyor — kullanıcı hareketleri erken bitirdi.
            // (c) meşru bir kullanıcıyı reddetmek olurdu, o yüzden davranış BİLEREK değiştirilmedi.
            // Kapatmak için async işin bitişini işaretleyen ayrı bir bayrak gerekir; cihazda test ister.
            AppLog.warning("Chip fotoğrafı verildi ama embedding yok — AI kontrolü atlanıyor", "Liveness")
        }

        // Akış bitti — gömme vektörünü serbest bırak ve akışın NASIL bittiğini bildir.
        // "submitted": kullanıcı canlılığı geçti ve kayıt gönderiliyor.
        streamer?.release("submitted")

        // Wait a bit for file finalize (Safety)
        binding.root.postDelayed({
            restoreBrightness()
            val intent = Intent()
            intent.putExtra("user_selfie", userSelfiePath)
            intent.putExtra("antispoof_crop", antiSpoofCropPath)
            // Üreticinin ikinci ölçeği — YALNIZ ÖLÇÜM. Ulaşılan ölçekler de gider: yüz
            // kadrajda büyükse istenen 4,0 sıkışır ve veri onsuz yorumlanamaz.
            intent.putExtra("antispoof_crop40", antiSpoofCrop40Path)
            intent.putExtra("antispoof_scale27", antiSpoofScale27)
            intent.putExtra("antispoof_scale40", antiSpoofScale40)
            // 2. ADAY: enclave'in canlılık sırasında onayladığı kare — yalnız 1. adaydan
            // FARKLIYSA taşınır. Aynı kareyse tek fotoğraf gönderilir (MainViewModel karar verir).
            //
            // ⚠️ Onaylanan kareyi taşımak ŞART: enclave'in geçirdiği kare ile cihazın "en iyi"
            // saydığı kare farklı olabilir — ölçmek istediğimiz sapma tam olarak budur.
            intent.putExtra("approved_selfie", streamer?.approvedSelfiePath)
            intent.putExtra("approved_crop", streamer?.approvedCropPath)
            // Adayların KARE ÖLÇÜLERİ: submit anında yeniden ölçülemezler (kamera kapalı).
            intent.putExtra("best_frame_metrics", bestFrameMetricsJson)
            intent.putExtra("approved_frame_metrics", approvedFrameMetricsJson)
            // Adayların KAYNAK KARE numaraları: final satırı ile onu üreten streaming satırını
            // birleştirir. -1 = o kare streaming'e hiç gönderilmedi (fren elemiş olabilir).
            intent.putExtra("best_source_seq", streamer?.lastSentSeq ?: -1)
            intent.putExtra("approved_source_seq", streamer?.approvedSeq ?: -1)
            // Başarıda da taşınır: sunucudaki anti-spoof reddi bu adımdan SONRA geliyor, yani
            // "canlılık geçti ama kayıt düştü" vakasında elimizdeki tek kare ölçüsü bu.
            intent.putExtra("chip_aligned", chipAlignedPath)
            intent.putExtra("liveness_diag", buildDiagnostics())

            // OLAY DİZİSİ KANITI — kareler düz listede, adımı ve türü (0 nötr, 1 olay) paralel
            // dizilerde; Intent iç içe liste taşımıyor.
            eventResult?.let { r ->
                val paths = mutableListOf<String>()
                val stepsOf = mutableListOf<Int>()
                val kinds = mutableListOf<Int>()
                r.steps.forEachIndexed { i, st ->
                    paths += st.neutralPath; stepsOf += i; kinds += 0
                    st.eventPaths.forEach { paths += it; stepsOf += i; kinds += 1 }
                }
                intent.putExtra("ev_frames", paths.toTypedArray())
                intent.putExtra("ev_frame_steps", stepsOf.toIntArray())
                intent.putExtra("ev_frame_kinds", kinds.toIntArray())
                intent.putExtra("ev_attempts", r.steps.map { it.attempts }.toIntArray())
                intent.putExtra("ev_elapsed_ms", r.elapsedMs)
                intent.putExtra("ev_resets", r.resets)
                intent.putExtra("ev_wrong_events", r.wrongEvents)
                intent.putExtra("ev_tracking_changes", r.trackingChanges)
                intent.putExtra("ev_trace", r.trace)
            }

            setResult(RESULT_OK, intent)
            finish()
        }, 500)
    }

    private fun restoreBrightness() {
        val lp = window.attributes
        lp.screenBrightness = if (originalBrightness < 0)
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else
            originalBrightness
        window.attributes = lp
    }

    /**
     * Sistem çubuğu güvenli alanı.
     *
     * targetSdk 35+ ile uygulama zorunlu olarak edge-to-edge çizer: insets uygulanmadığında içerik
     * navigasyon çubuğunun ALTINA taşar. Bu ekranda çip fotoğrafı + canlı skor satırı parent'ın
     * altına sabitli olduğu için 3 tuşlu navigasyonda kısmen çubuğun arkasında kalıyordu (kullanıcı
     * geri bildirimi 2026-08-21). Kök dolgusu her çözünürlükte ve her navigasyon modunda doğru
     * çalışır — sabit dp'lerle uğraşmaya gerek yok: komutlar üstte, çip satırı altta, kamera aradaki
     * alanda kalır. Kamera önizlemesi ve çerçeve katmanı aynı kutu içinde küçüldüğü için hizaları bozulmaz.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreBrightness()
        cameraExecutor.shutdown()
        // onCreate kurulumdan önce patlarsa alan hiç atanmamış olur; lateinit erişimi onDestroy'u
        // ikinci bir çökmeye çevirmemeli.
        if (::feedback.isInitialized) feedback.release()
        // Akış nasıl biterse bitsin (vazgeçme, hata, başarı) enclave RAM'indeki gömme vektörü
        // bırakılır. Başarı yolunda zaten çağrıldı; burası SESSİZ çıkışı yakalar (geri tuşu,
        // uygulamanın kapatılması). Streamer ilk sebebi tuttuğu için buradaki "abandoned" ancak
        // gerçekten hiçbir sebep bildirilmediyse kazanır.
        //
        // 🔴 Aradığımız vaka tam olarak bu: enclave skoru eşiği geçerken "abandoned" ile biten
        // akış, cihazdaki ön eleme yüzünden kaybettiğimiz kullanıcıdır.
        streamer?.release(lastFailureReason ?: "abandoned")
        // Dizi yarıda kaldıysa kareler cache'te kalmasın: yüz görüntüsü taşıyorlar ve hiçbir
        // yere gitmeyecekler. Başarıda toplayıcı zaten DONE'dadır ve abandon dosyalara dokunmaz —
        // onları kayıt sonrası view model siler.
        eventCollector?.abandon()
        if (::binding.isInitialized) stopEventTicker()
    }

    private var lastCaptureTime = 0L
    private var bestSavedMatchScore = -1f // Track the match score of the saved file
    private var bestSavedQualityScore = -1f

    private fun captureFrame(imageProxy: androidx.camera.core.ImageProxy, face: com.google.mlkit.vision.face.Face, qualityScore: Float) {
        if (System.currentTimeMillis() - lastCaptureTime < 400) return // Throttle 400ms
        lastCaptureTime = System.currentTimeMillis()

        val faceBox = face.boundingBox

        // Bu fonksiyon 400ms'de bir ~8 MB'lık ara bitmap üretiyor (1920×1080 ARGB_8888) ve
        // hiçbiri kareden sonra yaşamıyor — kalıcı olan yalnız diske yazılan selfie_best.png
        // ve antispoof_crop.jpg. GC'yi beklemek yerine finally'de serbest bırakıyoruz: Android
        // 17 per-app bellek limitleri altında bu churn zRAM swap'e, oradan da doğrulamanın
        // ORTASINDA process sonlandırmaya yol açıyor. Hiçbir modelin gördüğü piksel değişmiyor.
        //
        // ⚠️ createBitmap/createScaledBitmap dönüşüm gereksizse KAYNAĞIN KENDİSİNİ döndürür
        // (rotation=0 → birim matris; crop tam sınırlarda; scale hedefi zaten 112×112).
        // Bu yüzden her recycle öncesi referans kimliği kontrol edilir — aksi halde çift-recycle
        // veya hâlâ kullanılan bir bitmap'in recycle'ı olur. (Aynı kalıp: wideCrop/scaled80.)
        var srcRef: android.graphics.Bitmap? = null
        var fullRef: android.graphics.Bitmap? = null
        var croppedRef: android.graphics.Bitmap? = null
        var alignedRef: android.graphics.Bitmap? = null

        try {
            val bitmap = imageProxy.toBitmap()
            srcRef = bitmap
            if (bitmap != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation)

                val fullBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                fullRef = fullBitmap

                val margin = faceBox.width() * 0.4f
                val left   = (faceBox.left   - margin).coerceAtLeast(0f)
                val top    = (faceBox.top    - margin).coerceAtLeast(0f)
                val right  = (faceBox.right  + margin).coerceAtMost(fullBitmap.width.toFloat())
                val bottom = (faceBox.bottom + margin).coerceAtMost(fullBitmap.height.toFloat())

                val width  = right - left
                val height = bottom - top

                if (width > 50 && height > 50) {
                    val croppedBitmap = android.graphics.Bitmap.createBitmap(
                        fullBitmap, left.toInt(), top.toInt(), width.toInt(), height.toInt()
                    )
                    croppedRef = croppedBitmap

                    // Landmark pozisyonlarını crop koordinat uzayına çevir
                    val leftEyeLandmark  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val leftEyeInCrop  = leftEyeLandmark?.let  { PointF(it.x - left, it.y - top) }
                    val rightEyeInCrop = rightEyeLandmark?.let { PointF(it.x - left, it.y - top) }

                    // 112x112 hizalanmış bitmap — hem scoring hem enclave'e gönderim için
                    val alignedBitmap = faceEmbedder?.getAlignedBitmap(croppedBitmap, leftEyeInCrop, rightEyeInCrop)
                    alignedRef = alignedBitmap

                    // Netlik (112×112 aligned) → anlık "net değil" uyarısı + best-frame için kalite bonusu.
                    // Bulanık/kirli lens veya hareket bulanıklığında düşük çıkar.
                    val sharpness = alignedBitmap?.let { computeSharpness(it) } ?: -1f
                    blurWarning = if (sharpness in 0f..BLUR_WARN_THRESHOLD)
                        getString(R.string.liveness_quality_blur) else null
                    publishQualityWarning()
                    val sharpBonus = if (sharpness >= 0f)
                        (sharpness / SHARP_QUALITY_REF).coerceIn(0f, 1f) * 15f else 0f
                    // Eşit benzerlikte best-frame seçimini en NET kareye kaydır (poz + netlik birleşik).
                    val effQuality = (qualityScore + sharpBonus).coerceAtMost(115f)

                    // CHECK MATCH SCORE — aligned bitmap üzerinden embedding
                    var currentMatchScore = 0f
                    if (chipEmbedding != null && faceEmbedder != null && alignedBitmap != null) {
                        val selfieEmbedding = faceEmbedder?.getEmbedding(alignedBitmap)
                        if (selfieEmbedding != null) {
                            currentMatchScore = com.verifyblind.mobile.util.FaceEmbedder.cosineSimilarity(chipEmbedding!!, selfieEmbedding)
                        }
                    }

                     // DECISION LOGIC v5:
                     var shouldSave = false
                     var reason = ""

                     if (chipEmbedding != null) {
                         // Case A: ANY Match Improvement (> 0.5% better)
                         if (currentMatchScore > bestSavedMatchScore + 0.005f) {
                             shouldSave = true
                             reason = "Daha İyi Benzerlik"
                         }
                         // Case B: Similar Match (within 0.5%) BUT Better Quality (+5 better)
                         else if (Math.abs(currentMatchScore - bestSavedMatchScore) < 0.005f && effQuality > bestSavedQualityScore + 5f) {
                             shouldSave = true
                             reason = "Daha Net Fotoğraf"
                         }
                         // Case C: First Save
                         else if (userSelfiePath == null) {
                             shouldSave = true
                             reason = "İlk Yakalama"
                         }
                     } else {
                         // Case No Chip: Just check Quality
                         if (effQuality > bestSavedQualityScore + 5f || userSelfiePath == null) {
                             shouldSave = true
                         }
                     }

                     // UPDATE UI PERMANENTLY WITH MAX SCORE (INTEGER)
                     val scorePercent = (bestMatchScore * 100).toInt()
                     val color = if (scorePercent >= (MATCH_THRESHOLD * 100).toInt())
                         ContextCompat.getColor(this@LivenessActivity, R.color.success)
                     else android.graphics.Color.RED
                     val finalMsg = "%d%%".format(scorePercent)

                     runOnUiThread {
                         if (chipEmbedding != null) {
                             val tvScore = findViewById<android.widget.TextView>(R.id.tvLiveScore)
                             tvScore?.text = finalMsg
                             tvScore?.setTextColor(color)
                         }
                     }

                     if (shouldSave) {
                         val faceFrac = if (imageProxy.width > 0)
                             faceBox.width().toFloat() / imageProxy.width else -1f
                         savedFrameMetrics =
                             "luma=${lastLuma.toInt()} sharp=${sharpness.toInt()} " +
                             "quality=${effQuality.toInt()} yaw=${face.headEulerAngleY.toInt()} " +
                             "pitch=${face.headEulerAngleX.toInt()} roll=${face.headEulerAngleZ.toInt()} " +
                             "faceW=${(faceFrac * 100).toInt()}%"

                         // Hizalanmış 112x112 bitmap'i kaydet — enclave aynı görüntüyü işler.
                         // PNG (lossless): R50 girişi tam bu 112×112 pikseller; bu boyutta JPEG blok
                         // artefaktı embedding'i bozabilir, dosya zaten ~20-40 KB.
                         val saveTarget = alignedBitmap ?: croppedBitmap
                         val file = File(cacheDir, "selfie_best.png")
                         val fos = java.io.FileOutputStream(file)
                         saveTarget.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                         fos.flush()
                         fos.close()
                         userSelfiePath = file.absolutePath

                         // Anti-spoof kırpmaları — MiniFASNet bağlam + arka plan görmek ister.
                         //
                         // ⚠️ Kırpma ÜRETİCİNİN kuralıyla yapılıyor (bkz. AntiSpoofCrop): kadrajdan
                         // taşarsa kutu KESİLMEZ, içeri KAYDIRILIR. Eski kodumuz kesiyordu, yani yüz
                         // büyükken/kenardayken modele 2,7× DEĞİL daha dar ve merkezden kaymış bir
                         // görüntü gidiyordu — eğitildiğinden farklı.
                         //
                         // 2,7 kapıyı besler; 4,0 YALNIZ ÖLÇÜM (üreticinin ikinci ölçeği; fotoğraf
                         // ölçümünde ekranları daha "canlı" bulduğu için karara sokulmadı).
                         try {
                             com.verifyblind.mobile.util.AntiSpoofCrop.crop(
                                 fullBitmap, faceBox, com.verifyblind.mobile.util.AntiSpoofCrop.SCALE_27
                             )?.let { r ->
                                 val f = File(cacheDir, "antispoof_crop.jpg")
                                 java.io.FileOutputStream(f).use {
                                     r.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                                 }
                                 antiSpoofCropPath = f.absolutePath
                                 antiSpoofScale27 = r.achievedScale
                                 r.bitmap.recycle()
                             }

                             com.verifyblind.mobile.util.AntiSpoofCrop.crop(
                                 fullBitmap, faceBox, com.verifyblind.mobile.util.AntiSpoofCrop.SCALE_40
                             )?.let { r ->
                                 val f = File(cacheDir, "antispoof_crop40.jpg")
                                 java.io.FileOutputStream(f).use {
                                     r.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                                 }
                                 antiSpoofCrop40Path = f.absolutePath
                                 // Ulaşılan ölçek ŞART: yüz kadrajda büyükse 4,0 sıkışır ve
                                 // aslında 2,x besleriz. Bunu kaydetmezsek veri yorumlanamaz.
                                 antiSpoofScale40 = r.achievedScale
                                 r.bitmap.recycle()
                             }
                         } catch (e: Exception) {
                             Log.w("Liveness", "Anti-spoof crop hatası (devam edilecek): ${e.message}")
                         }

                         bestSavedMatchScore = currentMatchScore
                         bestSavedQualityScore = effQuality

                         if (currentMatchScore > bestMatchScore) bestMatchScore = currentMatchScore

                         if (currentMatchScore > MATCH_THRESHOLD) {
                             isIdentityVerified = true
                         }

                         // Canlı benzerlik akışı: en iyi kare YENİLENDİĞİNDE enclave'e gönderilir.
                         // Kare akışı DEĞİL — yalnız iyileşen kare; enclave'in gördüğü, cihazın o
                         // ana kadarki en iyi hükmüdür. Selfie ve kırpma AYNI kareden gelir
                         // (aksi bir açık olurdu: benzerlik gerçek yüzden, canlılık başka kareden).
                         val frameMetrics = com.verifyblind.mobile.util.SimilarityStreamer.metricsOf(
                             deviceMatchScore = (currentMatchScore * 100).toInt().coerceIn(0, 100),
                             luma = lastLuma.toInt(),
                             sharpness = sharpness.toInt(),
                             quality = effQuality.toInt(),
                             yaw = face.headEulerAngleY.toInt(),
                             pitch = face.headEulerAngleX.toInt(),
                             roll = face.headEulerAngleZ.toInt(),
                             faceWidthRatio = (faceFrac * 100).toInt(),
                             gestureCount = eventCollector?.completedSteps ?: 0,
                             wrongGestureCount = eventCollector?.wrongCount ?: 0,
                             elapsedMs = if (sessionStartedAt > 0)
                                 (System.currentTimeMillis() - sessionStartedAt).toInt() else null,
                         )
                         // Kaydedilen kare değişti → 1. adayın ölçüleri de bu karenin ölçüleri.
                         bestFrameMetricsJson = com.google.gson.Gson().toJson(frameMetrics)

                         streamer?.let { st ->
                             val sp = userSelfiePath
                             if (sp != null) {
                                 st.submitFrame(sp, antiSpoofCropPath, frameMetrics) {
                                     // Enclave BU kareyi onayladı → 2. adayın ölçüleri budur.
                                     approvedFrameMetricsJson = com.google.gson.Gson().toJson(frameMetrics)
                                 }
                             }
                         }

                         Log.d("Liveness", "Selfie kaydedildi: Eşleşme=$currentMatchScore, Netlik=$sharpness, Neden=$reason")
                     }
                 }
            }
        } catch (e: Exception) {
            AppLog.error("Fotoğraf çekme başarısız", "Liveness", e)
        } finally {
            // Zincirin tersinden: aligned → cropped → full → src. finally olması önemli —
            // yukarıdaki catch istisnayı yutuyor, o yolda da bellek geri verilmeli.
            // Türetilmiş bitmap kaynağın kendisiyse (kimlik) recycle ETME, sahibi bir sonraki
            // adımda zaten serbest bırakacak.
            alignedRef?.let { if (it !== croppedRef) it.recycle() }
            croppedRef?.let { if (it !== fullRef) it.recycle() }
            fullRef?.let    { if (it !== srcRef)   it.recycle() }
            srcRef?.recycle()
        }
    }

    private fun showFailureSummary(
        isTimeout: Boolean = false,
        customTitle: String? = null,
        customMessage: String? = null,
        flowReason: String? = null,
        ) {
            didFail = true
        // Huni: sebep HATA ANINDA bildirilir (çıkışta değil — kullanıcı "Tekrar Dene" diyebiliyor).
        // Akış başına yalnız ilk sebep; tekrar denemeler istatistiği şişirmesin.
        if (!flowFailureReported) {
            flowFailureReported = true
            val reason = flowReason ?: if (isTimeout) "timeout_gesture" else "match_failed"
            // Skor da gider: "neden kaybettik" sorusunun cevabı match_failed'de tek başına
            // eksik — %64 ile %10 bambaşka vakalardır (bkz. FlowTelemetry.livenessFailed).
            com.verifyblind.mobile.util.FlowTelemetry.livenessFailed(
                reason, flowNonce, (bestMatchScore * 100).toInt()
            )
        }
        // Sebep huniye yalnız BİR kez gider (ilk sebep kazanır) ama teşhis bloğu her çıkışta
        // yeniden üretiliyor — bu yüzden burada, rapor kapısının DIŞINDA saklanır.
        lastFailureReason = flowReason ?: if (isTimeout) "timeout_gesture" else "match_failed"
        // 🔴 Akış burada KAPATILMAZ (streamer.release YOK): bu ekrandan "Tekrar dene" ile aynı
        // akışa dönülebiliyor. Eskiden burada kapatılıyordu ve tekrar denemede canlı benzerlik
        // ölü kalıyordu — enclave "prepare gerekli" diyordu. Kapanış ekran gerçekten kapanınca
        // (onDestroy) SON başarısızlık sebebiyle yapılır; ölçüm tablosu gerçek sebebi yine görür.
        // Telemetri: iOS bu olayı Sentry'ye yazıyordu, Android hiç yazmıyordu → Android'de canlılık
        // testinde takılan bir kullanıcı hiçbir iz bırakmıyordu. Yalnız yapısal alanlar: sebep,
        // tamamlanan adım sayısı, yanlış deneme sayısı ve en iyi eşleşme skoru (skaler).
        val reason = if (isTimeout) "timeout" else if (customTitle != null) "too_many_errors" else "match_or_selfie"
        AppLog.warning(
            "Liveness başarısız (reason=$reason " +
                "adım=${eventCollector?.completedSteps ?: 0}/${events.size} " +
                "yanlış=${eventCollector?.wrongCount ?: 0} skor=${(bestMatchScore * 100).toInt()}%) " +
                "[${savedFrameMetrics ?: "kare ölçüsü yok"}]",
            "Liveness"
        )
        runOnUiThread {
            try {
                // STOP CAMERA COMPLETELY
                val cameraProvider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                stopEventTicker()
                binding.faceFrameOverlay.setTimeProgress(-1f)
                binding.viewFinder.visibility = android.view.View.INVISIBLE // Hide preview surface

                // Inflate existing XML layout
                val dialogView = layoutInflater.inflate(R.layout.dialog_biometric_fail, null)
                val imgChip = dialogView.findViewById<android.widget.ImageView>(R.id.imgChipPhoto)
                val imgSelfie = dialogView.findViewById<android.widget.ImageView>(R.id.imgSelfie)
                val btnRetry = dialogView.findViewById<android.view.View>(R.id.btnRetry)
                val btnCancel = dialogView.findViewById<android.view.View>(R.id.btnCancel)

                val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvFailTitle)
                val tvMessage = dialogView.findViewById<android.widget.TextView>(R.id.tvFailMessage)

                // Layouts to hide/show
                val layoutImages = dialogView.findViewById<android.view.View>(R.id.layoutImages)
                val layoutLabels = dialogView.findViewById<android.view.View>(R.id.layoutLabels)

                // Find Score TextView directly by ID
                val tvScore = dialogView.findViewById<android.widget.TextView>(R.id.tvFailureScore)

                // 0. Set Text & Logic based on Timeout vs Match Fail
                if (customTitle != null && customMessage != null) {
                    tvTitle?.text = customTitle
                    tvMessage?.text = customMessage
                    // Hide Images & Labels
                    layoutImages?.visibility = android.view.View.GONE
                    layoutLabels?.visibility = android.view.View.GONE
                } else if (isTimeout) {
                    tvTitle?.text = getString(R.string.liveness_timeout_title)
                    tvMessage?.text = getString(R.string.liveness_timeout_message)

                    // Hide Images & Labels
                    layoutImages?.visibility = android.view.View.GONE
                    layoutLabels?.visibility = android.view.View.GONE
                } else {
                    tvTitle?.text = getString(R.string.liveness_match_failed_title)
                    tvMessage?.text = getString(R.string.liveness_match_failed_message)

                    // Show Images & Labels
                    layoutImages?.visibility = android.view.View.VISIBLE
                    layoutLabels?.visibility = android.view.View.VISIBLE

                    // 1. Set Images (SHOW EXACTLY WHAT AI SEES)
                    // Resize to 112x112 to show user the stretching/squashing effect
                    val chipPath = intent.getStringExtra("chip_photo_path")
                    if (chipPath != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(chipPath)
                        if (bitmap != null) {
                             val aiInput = android.graphics.Bitmap.createScaledBitmap(bitmap, 112, 112, true)
                             imgChip.setImageBitmap(aiInput)
                        }
                    }

                    if (userSelfiePath != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(userSelfiePath)
                        if (bitmap != null) {
                             val aiInput = android.graphics.Bitmap.createScaledBitmap(bitmap, 112, 112, true)
                             imgSelfie.setImageBitmap(aiInput)
                        }
                    }

                    // 2. Set Score
                    val scorePercent = (bestMatchScore * 100).toInt()
                    if (tvScore != null) {
                        tvScore.text = "%d%%".format(scorePercent)
                        tvScore.textSize = 16f
                        tvScore.gravity = android.view.Gravity.CENTER

                        // Logic fix: Green if good score, even if timeout happened
                        if (scorePercent >= (MATCH_THRESHOLD * 100).toInt()) {
                            tvScore.setTextColor(ContextCompat.getColor(this@LivenessActivity, R.color.success))
                        } else {
                            tvScore.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }

                // 3. Dialog
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@LivenessActivity)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

                // Make background transparent to avoid double-background (standard dialog bg + card bg)
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

                btnRetry.setOnClickListener {
                    AppLog.info("'Tekrar Dene' → koşu yeniden başlatılıyor", "Liveness")
                    dialog.dismiss()
                    // startCamera() kamerayı yeniden bağlar ve bittiğinde koşuyu başlatır.
                    binding.viewFinder.visibility = android.view.View.VISIBLE
                    startCamera()
                }

                btnCancel.setOnClickListener {
                    finishWithDiagnostics()
                }

                dialog.show()

            } catch (e: Exception) {
                AppLog.error("Dialog hatası", "Liveness", e)
                Toast.makeText(this@LivenessActivity, "${getString(R.string.error_data_prefix)}${e.message}", Toast.LENGTH_LONG).show()
                finishWithDiagnostics()
            }
        }
    }
}
