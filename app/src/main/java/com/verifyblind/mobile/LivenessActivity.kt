package com.verifyblind.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.verifyblind.mobile.api.LivenessAction
import com.verifyblind.mobile.databinding.ActivityLivenessBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.LivenessAnalyzer
import com.verifyblind.mobile.view.FaceOvalOverlayView
import android.graphics.RectF
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
        // Yüzün kaç ms kayıp kalması uyarıyı hak eder. Baş çevirmede dedektör yüzü kısa süre
        // kaybedebiliyor; eşik bunun üstünde olmalı yoksa uyarı yanıp söner.
        private const val NO_FACE_WARN_MS = 1500L
        private const val SHARP_QUALITY_REF = 250f   // bu enerjide tam +15 kalite bonusu
    }

    private lateinit var binding: ActivityLivenessBinding
    private lateinit var cameraExecutor: ExecutorService
    private var originalBrightness = -1f

    // State
    private var challenges: List<LivenessAction> = emptyList()
    private var currentChallengeIndex = 0
    private var isDemo = false
    
    // Result Paths
    private var userSelfiePath: String? = null
    private var antiSpoofCropPath: String? = null
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
     * Aktif hareketin ölçümü: komut EKRANA GELDİĞİ an ve o hareket için yapılan yanlış sayısı.
     *
     * Neden komut anından: kullanıcının o hareketi çözmesi ne kadar sürdü sorusunun cevabı bu.
     * Sayaç (`startGestureTimer`) yanlış hareketten ve onay animasyonundan sonra yeniden başlıyor,
     * yani sayaçtan ölçmek "kaç saniyede yaptı"yı değil "son denemesi kaç saniye sürdü"yü verirdi.
     * Gülümsemedeki "önce yüzünüzü gevşetin" ara adımı da bilerek süreye dâhil: kullanıcı açısından
     * o bekleme de gülümseme komutunun bir parçası.
     */
    private var gestureStartedAt = 0L
    private var gestureWrongCount = 0

    // Anti-Spoofing (Face Tracking)
    private var lockedTrackingId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info("onCreate başladı", "Liveness")
        binding = ActivityLivenessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // Parse Intent
        val challengeInts = intent.getIntegerArrayListExtra("challenges") ?: arrayListOf()
        challenges = challengeInts.map { LivenessAction.fromInt(it) }
        isDemo = intent.getBooleanExtra("is_demo", false)

        Log.d("Liveness", "Zorluklar: $challenges (demo=$isDemo)")

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        try {
            startCamera()
        } catch (t: Throwable) {
             AppLog.error("Kamera başlatma başarısız", "Liveness", t)
             showMessage(getString(R.string.liveness_error_title), t.message ?: getString(R.string.error_unknown))
        }
        
        // Initial UI
        binding.tvInstruction.text = getString(R.string.liveness_preparing_tv)
        // binding.progressBar.visibility = View.VISIBLE // Removed

        // Set bottom hint with actual threshold
        val thresholdPct = (MATCH_THRESHOLD * 100).toInt()
        binding.tvBottomHint.text = getString(R.string.liveness_threshold_hint, thresholdPct)

        // Save current brightness and set to full for best face recognition
        originalBrightness = window.attributes.screenBrightness
        val lp = window.attributes
        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp
        
        // Ensure 5 Challenges
        if (challenges.size < 5) {
            val mutable = challenges.toMutableList()
            while (mutable.size < 5) {
                // Add random or cycle
                val next = LivenessAction.values().filter { it != LivenessAction.None }.random()
                mutable.add(next)
            }
            challenges = mutable
        }
        
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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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
        append(" adım=").append(currentChallengeIndex).append("/").append(challenges.size)
        append(" yanlış=").append(wrongAttempts)
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

    private fun startCamera() {
        // ... (Keep existing startCamera logic, it's fine)
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
                            onFaceDetected = { face, imageProxy -> processFace(face, imageProxy) },
                            onFrameLuma = { luma -> onFrameLuma(luma) }
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
                // Do not finish immediately so user can see toast? 
                // finish() 
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Timer properties
    private var countDownTimer: CountDownTimer? = null

    // ── Zamanlama & hata bütçesi (iOS LivenessViewModel ile birebir) ──
    // Hareket başına süre HER BAŞARILI HAREKETTE sıfırlanır: ilerleyen kullanıcı zamana yenilmez,
    // yalnız gerçekten takılan oturum biter. Eskiden 5 hareket için TEK 30sn sayaç vardı ve ilk kez
    // deneyenler yüzleri EŞLEŞMİŞKEN reddediliyordu (Sentry, 2026-08-21: iki ardışık timeout).
    private val gestureTimeoutMs = 15_000L
    /// Oturum tavanının hareket bütçesinin ÜSTÜNE eklediği pay: her onayda 1sn ✅ animasyonu,
    /// yanlış harekette 1.5sn ceza, gülümseme gevşeme aşaması (≤5sn) ve yüz bulma süresi.
    private val sessionOverheadMs = 20_000L
    /// Oturum tavanı — hareket bütçesinden TÜRETİLİR, sabit değildir.
    ///
    /// Sabit 60sn yanlıştı: 5 hareket × 15sn = 75sn'lik hareket bütçesini karşılamıyordu, yani
    /// "her harekete 15 saniye" sözü 4. harekette sessizce bozuluyordu. Hareket süresi ya da
    /// challenge sayısı değişirse tavan kendiliğinden uyar; ikisi bir daha çelişemez.
    private val sessionTimeoutMs: Long
        get() = gestureTimeoutMs * maxOf(challenges.size, 5) + sessionOverheadMs
    /// Yanlış hareket bütçesi — kötüye kullanımın ASIL sınırı budur, saat değil. Jest dizisi oturum
    /// boyunca sabit olduğundan sınırsız deneme, diziyi deneme-yanılmayla öğrenmeye izin verirdi.
    private val maxWrongAttempts = 5
    /// Nötre dönmesi beklenen gülümseme için üst sınır; aşılırsa normal adıma geçilir (fail-open,
    /// eski davranış) — kullanıcı bu yüzden ASLA timeout yememeli.
    private val smileRelaxTimeoutMs = 5_000L

    private var wrongAttempts = 0
    private var sessionDeadline = 0L
    private var poseSettled = false
    /// Gülümseme "yükselişi" ölçülebilir mi — yani kullanıcının nötr olduğu EN AZ BİR kare görüldü mü?
    private var smileArmed = false
    private var smileRelaxShown = false
    private var smileArmDeadline = 0L
    /// Gülümseme kenar tespiti: nötr görülmeden gelen yüksek olasılık "yeni bir gülümseme" değildir.
    /// Sürekli gülümseyen biri aksi halde hedef-dışı gülümsemeyle bütçesini saniyeler içinde yakardı.
    private var smileNeutralSeen = false
    /// -1 = henüz kare analiz edilmedi (ilk challenge kamera açılır açılmaz sunuluyor).
    private var lastSmileSignal = -1f
    private var nudged = false

    private val feedback by lazy { com.verifyblind.mobile.util.LivenessFeedback(this) }

    /// Huni telemetrisi için handshake nonce'u (demo'da yok → demo istatistiği kirletmez).
    private val flowNonce: String? by lazy { intent.getStringExtra("flow_nonce") }
    private var flowFailureReported = false

    // Jest eşikleri (iOS LivenessGestureDetector ile birebir). detectGesture'ın yerel val'lerinden
    // sınıf düzeyine taşındı: processAction da (poz-nötr ve gülümseme kontrolü) aynı değerleri kullanıyor.
    private val YAW_THRESHOLD = 20f
    private val SMILE_THRESHOLD = 0.8f
    /// Bu değerin altı "nötr yüz" sayılır — gülümseme yükselişi ancak buradan sonra ölçülebilir.
    private val SMILE_RELAX_BELOW = 0.4f
    private val BLINK_THRESHOLD = 0.1f
        
    // --- PHASE 2: ACTIONS ---
    private fun startActionPhase() {
        currentChallengeIndex = 0
        
        // Reset best match and delete old photo when retrying/starting a new run
        userSelfiePath?.let { java.io.File(it).delete() }
        userSelfiePath = null
        bestMatchScore = 0f
        bestSavedMatchScore = -1f
        bestSavedQualityScore = -1f
        isIdentityVerified = false

        
        // Hide oval overlay during Action phase (or keep it as guide?)
        // Let's keep it visible but STATIC as a frame
        binding.faceOvalOverlay.visibility = View.VISIBLE
        binding.faceOvalOverlay.setSize(FaceOvalOverlayView.SIZE_LARGE)
        binding.faceOvalOverlay.setState(FaceOvalOverlayView.STATE_WAITING)
        
        // Clear UI score
        runOnUiThread {
            val tvScore = findViewById<android.widget.TextView>(R.id.tvLiveScore)
            if (tvScore != null) {
                tvScore.text = ""
                tvScore.setTextColor(android.graphics.Color.WHITE)
            }
        }
        
        wrongAttempts = 0
        savedFrameMetrics = null
        poseSettled = false
        smileArmed = false
        smileRelaxShown = false
        smileNeutralSeen = false
        lastSmileSignal = -1f
        sessionDeadline = System.currentTimeMillis() + sessionTimeoutMs
        runStartedAt = System.currentTimeMillis()
        lastFaceTimeMs = 0L
        noFaceWarning = null
        if (isDemo) runDemoChallenges() else showNextChallenge()
    }

    // --- DEMO: Sahte liveness — gerçek jest beklemeden her hareketi sahneler ---
    private fun runDemoChallenges() {
        demoAdvanceChallenge()
    }

    /**
     * Demo akışı: mevcut hareketi göster, 1 sn bekle, ✅ ile işaretle ve sonrakine geç.
     * Tüm hareketler bitince finishSuccess() ile tamamlanır.
     */
    private fun demoAdvanceChallenge() {
        if (isFinishing || isDestroyed) return
        if (currentChallengeIndex >= challenges.size) {
            finishSuccess()
            return
        }
        showNextChallenge()
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            feedback.stepOk()   // demo gerçek akışı temsil etmeli (aynı ses/titreşim)
            binding.tvInstruction.text = "✅"
            currentChallengeIndex++
            binding.root.postDelayed({ demoAdvanceChallenge() }, 300)
        }, 1000)
    }

    /**
     * Aktif hareketin sayacını (yeniden) başlatır — her yeni hareket sunulduğunda çağrılır.
     * Oturum tavanı ayrıca kontrol edilir; ikisinden biri dolarsa timeout.
     */
    private fun startGestureTimer() {
        countDownTimer?.cancel()
        nudged = false
        countDownTimer = object : CountDownTimer(gestureTimeoutMs, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = (millisUntilFinished.toFloat() / gestureTimeoutMs).coerceIn(0f, 1f)
                binding.faceOvalOverlay.setTimeProgress(progress)
                if (!nudged && progress <= FaceOvalOverlayView.LOW_TIME_FRACTION) {
                    nudged = true
                    feedback.nudge()   // sessiz dokunuş — kafa çevrikken de hissedilir
                }
                if (System.currentTimeMillis() >= sessionDeadline) {
                    cancel()
                    binding.faceOvalOverlay.setTimeProgress(0f)
                    // Oturum tavanı: genelde takılmış/terk edilmiş oturum.
                    showFailureSummary(isTimeout = true, flowReason = "timeout_session")
                }
            }

            override fun onFinish() {
                binding.faceOvalOverlay.setTimeProgress(0f)
                // Süresi dolan hareket HANGİSİYDİ — huninin "canlılıkta kaybettik"ten sonra
                // söyleyebildiği tek ayrıntı bu. Akış özetinden ÖNCE gönderilir.
                reportGesture(timedOut = true)
                // Tek hareket süresi: kullanıcı komutu anlamadı ya da yapamadı — farklı bir düzeltme.
                showFailureSummary(isTimeout = true, flowReason = "timeout_gesture")
            }
        }.start()
    }

    private fun showNextChallenge() {
        if (currentChallengeIndex >= challenges.size) {
            // All done
            finishSuccess()
            return
        }

        val action = challenges[currentChallengeIndex]
        poseSettled = false
        // Hareket ölçümü BURADAN başlar (bkz. gestureStartedAt).
        gestureStartedAt = System.currentTimeMillis()
        gestureWrongCount = 0
        binding.tvStepCounter.text = "${currentChallengeIndex + 1}/${challenges.size}"

        // Gülümseme HER ZAMAN bir GEÇİŞ olarak ölçülür: kullanıcının nötr olduğu bir kare görülmeden
        // hiçbir gülümseme kabul edilmez (aşağıda processAction'daki "arming"). Eskiden mutlak eşik
        // (smilingProbability > 0.8) tek başına yeterliydi → sürekli gülümseyen biri veya gülümseyen
        // bir fotoğraf challenge'ı anında geçiyordu. Karar KARE bazlı verilir, komut anında tek bir
        // örneklemeyle değil: ML Kit olasılığı kare kare oynuyor ve ilk challenge kamera açılır
        // açılmaz sunulduğu için o anda henüz hiç kare analiz edilmemiş oluyordu.
        smileArmed = false
        smileRelaxShown = false
        smileArmDeadline = System.currentTimeMillis() + smileRelaxTimeoutMs

        val text = when (action) {
            LivenessAction.FaceLeft -> getString(R.string.liveness_face_left)
            LivenessAction.FaceRight -> getString(R.string.liveness_face_right)
            LivenessAction.Blink -> getString(R.string.liveness_face_blink)
            LivenessAction.Smile -> getString(R.string.liveness_face_smile)
            else -> "???"
        }

        binding.tvInstruction.text = text
        binding.tvSubInstruction.text = getString(R.string.liveness_perform_action)
        binding.tvSubInstruction.visibility = View.VISIBLE
        startGestureTimer()
    }

    /** Nötr sayılacak kadar düşük mü? (-1 = henüz kare yok → nötr DEĞİL sayılır.) */
    private fun isSmileNeutral(probability: Float) =
        probability >= 0f && probability < SMILE_RELAX_BELOW

    // --- LOGIC ---
    // Single Phase: Actions
    private var bestSelfieScore = 0f

    private fun processFace(face: com.google.mlkit.vision.face.Face, imageProxy: androidx.camera.core.ImageProxy) {
        lastFaceTimeMs = System.currentTimeMillis()
        try {
            val score = calculateQualityScore(face, imageProxy.width, imageProxy.height)
            captureFrame(imageProxy, face, score)
            processAction(face)
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

    private var lastActionTime = 0L

    private fun processAction(face: com.google.mlkit.vision.face.Face) {
        if (isDemo) return // Demo'da hareketler runDemoChallenges() ile sahnelenir
        if (currentChallengeIndex >= challenges.size) return

        lastSmileSignal = face.smilingProbability ?: 0f

        // Kafa nötre döndüyse yanlış-hareket sayımı açılır: aksi halde FaceRight→FaceLeft dizisinde
        // kullanıcı, kendi az önceki DOĞRU hareketi hâlâ görülürken hata yiyordu.
        if (!poseSettled && kotlin.math.abs(face.headEulerAngleY) < YAW_THRESHOLD) poseSettled = true

        // Nötr yüz görüldü → bundan sonraki yükseliş YENİ bir gülümsemedir (kenar tespiti).
        if (isSmileNeutral(lastSmileSignal)) smileNeutralSeen = true

        val target = challenges[currentChallengeIndex]

        // Gülümseme "arming": nötr bir kare görülmeden gülümseme KABUL EDİLMEZ. Throttle'dan önce
        // çalışır ki geçiş akıcı olsun. Kullanıcı komut anında gülümsüyorsa talimat "yüzünüzü
        // gevşetin"e döner; nötre inince asıl komut geri gelir ve sayaç tam süreyle yeniden başlar.
        if (target == LivenessAction.Smile && !smileArmed) {
            val gaveUp = System.currentTimeMillis() >= smileArmDeadline
            if (isSmileNeutral(lastSmileSignal) || gaveUp) {
                smileArmed = true
                if (smileRelaxShown) {
                    runOnUiThread {
                        binding.tvInstruction.text = getString(R.string.liveness_face_smile)
                        binding.tvSubInstruction.text = getString(R.string.liveness_perform_action)
                        startGestureTimer()   // asıl gülümseme için tam süre
                    }
                }
            } else if (!smileRelaxShown) {
                smileRelaxShown = true
                runOnUiThread {
                    binding.tvInstruction.text = getString(R.string.liveness_face_smile_relax)
                    binding.tvSubInstruction.text = getString(R.string.liveness_face_smile_relax_hint)
                }
            }
            return
        }

        if (System.currentTimeMillis() - lastActionTime < 2000) return

        val detected = detectGesture(face) ?: return

        if (detected == target) {
            onGestureAccepted()
            return
        }

        // Hedef dışı KASITLI jest → hata bütçesinden düşer. Kafa nötre dönmediyse sayılmaz.
        //
        // Göz kırpma bir REFLEKSTİR — asla sayılmaz. Gülümseme İRADİDİR ve sayılır: yalnız kafa
        // dönüşünü saymak bütçeyi işlevsiz bırakıyordu (ekranı okuyamayan bir deneme-yanılma düzeneği
        // blink ve smile'ı bedavaya eler). Ama yalnızca NÖTRDEN YÜKSELİŞ sayılır — sürekli gülümseyen
        // biri, mutlak eşiğin üstünde durduğu için her karede hata yiyemez.
        if (!poseSettled) return
        val isHeadTurn = detected == LivenessAction.FaceLeft || detected == LivenessAction.FaceRight
        if (isHeadTurn) {
            onWrongGesture(detected)
        } else if (detected == LivenessAction.Smile && smileNeutralSeen) {
            smileNeutralSeen = false
            onWrongGesture(LivenessAction.Smile)
        }
    }

    private fun onGestureAccepted() {
        lastActionTime = System.currentTimeMillis()
        reportGesture(timedOut = false)
        feedback.stepOk()   // kafa çevrikken ekranı GÖREMİYOR — onayı ses/titreşim taşır
        runOnUiThread {
            // Sayaç ONAY animasyonu başlarken yeniden başlar: aksi halde son saniyede yapılan DOĞRU
            // bir hareketin ardından, sonraki talimat ekrana gelmeden timeout tetikleniyordu.
            startGestureTimer()
            binding.tvInstruction.text = "✅"
            binding.tvSubInstruction.text = ""
            currentChallengeIndex++
            binding.root.postDelayed({
                showNextChallenge()
            }, 1000)
        }
    }

    /**
     * Aktif hareketin sonucunu huniye bildirir: hangi hareket, kaç ms sürdü, kaç yanlıştan sonra.
     *
     * Neden bu ayrıntı toplanıyor da kare akışı toplanmıyor: bu satırlar AKIŞLA büyür, kareyle
     * değil — jest kümesi dört elemanlı ve sunucu `(flow_id, step)` benzersizliğiyle her hareketi
     * akış başına bir kez sayıyor. Her karenin ML Kit çıktısını göndermek ise kareyle büyürdü ve
     * hiçbir kararı değiştirmezdi.
     *
     * Ne kararı değiştirir: bir hareket ötekilerin üç katı sürüyorsa ya jest kümesinden çıkar ya
     * ekrandaki yönerge değişir. `gestureWrongCount` ayrı bir şey söyler — süre "zor mu" derken o
     * "komut anlaşılıyor mu" der.
     */
    private fun reportGesture(timedOut: Boolean) {
        if (isDemo) return
        if (gestureStartedAt == 0L) return
        val action = challenges.getOrNull(currentChallengeIndex) ?: return
        val step = when (action) {
            LivenessAction.FaceLeft  -> "gesture_left"
            LivenessAction.FaceRight -> "gesture_right"
            LivenessAction.Smile     -> "gesture_smile"
            LivenessAction.Blink     -> "gesture_blink"
            // Enclave hiç None göndermez; gelse de raporlanacak bir hareket yok.
            LivenessAction.None      -> return
        }
        com.verifyblind.mobile.util.FlowTelemetry.gestureResolved(
            step = step,
            durationMs = System.currentTimeMillis() - gestureStartedAt,
            wrongCount = gestureWrongCount,
            timedOut = timedOut,
            nonce = flowNonce,
        )
        // Aynı hareket iki kez raporlanmasın (sunucu da yutar, ama gereksiz istek atmayalım).
        gestureStartedAt = 0L
    }

    /**
     * Yanlış hareket: hata bütçesinden düşer ve AYNI hareket yeniden sorulur.
     *
     * Eskiden `currentChallengeIndex = 0` ile diziye baştan başlanıyordu. Bu hem meşru kullanıcıyı
     * cezalandırıyordu (tek yanlış dönüş = tüm hareketler yeniden) hem de saldırgana yarıyordu: dizi
     * sabit olduğu için öğrenilen önek hızlıca tekrar oynatılıp yalnız bir sonraki adım deneniyordu.
     * Artık sınırı saat değil, sayılabilir bir bütçe koyuyor.
     */
    private fun onWrongGesture(detected: LivenessAction) {
        lastActionTime = System.currentTimeMillis()
        wrongAttempts++
        gestureWrongCount++
        poseSettled = false
        feedback.wrong()
        if (wrongAttempts >= maxWrongAttempts) {
            countDownTimer?.cancel()
            showFailureSummary(
                customTitle = getString(R.string.liveness_too_many_errors_title),
                customMessage = getString(R.string.liveness_too_many_errors_message),
                flowReason = "too_many_errors"
            )
            return
        }
        // Kullanıcı NE yaptığını görmeli; yalnız "yanlış hareket" demek "ben ne yaptım ki?" bırakıyor.
        val did = getString(
            when (detected) {
                LivenessAction.FaceLeft -> R.string.liveness_did_face_left
                LivenessAction.FaceRight -> R.string.liveness_did_face_right
                else -> R.string.liveness_did_smile
            }
        )
        val detail = getString(R.string.liveness_wrong_move_detail, did)
        runOnUiThread {
            startGestureTimer()   // ceza animasyonu sırasında sayaç dolmasın
            Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
            binding.tvInstruction.text = getString(R.string.liveness_error_indicator)
            binding.tvSubInstruction.text = detail
            binding.tvSubInstruction.visibility = View.VISIBLE
            binding.root.postDelayed({
                showNextChallenge()
            }, 1500)
        }
    }
    
    private fun detectGesture(face: com.google.mlkit.vision.face.Face): LivenessAction? {
                
        // SWAPPED Directions based on User Feedback
        if (face.headEulerAngleY > YAW_THRESHOLD) return LivenessAction.FaceLeft
        if (face.headEulerAngleY < -YAW_THRESHOLD) return LivenessAction.FaceRight
        
        if ((face.smilingProbability ?: 0f) > SMILE_THRESHOLD) return LivenessAction.Smile
        
        if ((face.leftEyeOpenProbability ?: 1f) < BLINK_THRESHOLD && 
            (face.rightEyeOpenProbability ?: 1f) < BLINK_THRESHOLD) return LivenessAction.Blink
            
        return null
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
            countDownTimer?.cancel()
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
            if (!isIdentityVerified) {
                // FAILURE -> Dialog instead of Toast
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
    
        countDownTimer?.cancel() // STOP Timer on success
        
        // Wait a bit for file finalize (Safety)
        binding.root.postDelayed({
            restoreBrightness()
            val intent = Intent()
            intent.putExtra("user_selfie", userSelfiePath)
            intent.putExtra("antispoof_crop", antiSpoofCropPath)
            // Başarıda da taşınır: sunucudaki anti-spoof reddi bu adımdan SONRA geliyor, yani
            // "canlılık geçti ama kayıt düştü" vakasında elimizdeki tek kare ölçüsü bu.
            intent.putExtra("chip_aligned", chipAlignedPath)
            intent.putExtra("liveness_diag", buildDiagnostics())
            setResult(RESULT_OK, intent)
            finish()
        }, 500)
    }

    // Timer variables are defined at the top
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
     * alanda kalır. Kamera önizlemesi ve oval katman aynı kutu içinde küçüldüğü için hizaları bozulmaz.
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
        countDownTimer?.cancel()
        feedback.release()
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

                var debugStatus = ""

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
                             debugStatus = "YENİ EN İYİ! (Match: %.3f)".format(currentMatchScore)
                         }
                         // Case B: Similar Match (within 0.5%) BUT Better Quality (+5 better)
                         else if (Math.abs(currentMatchScore - bestSavedMatchScore) < 0.005f && effQuality > bestSavedQualityScore + 5f) {
                             shouldSave = true
                             reason = "Daha Net Fotoğraf"
                             debugStatus = "Kalite İyileşti (Q: %.0f)".format(effQuality)
                         }
                         // Case C: First Save
                         else if (userSelfiePath == null) {
                             shouldSave = true
                             reason = "İlk Yakalama"
                             debugStatus = "İlk Kayıt (Match: %.3f)".format(currentMatchScore)
                         }
                         else {
                             // REJECTED
                             debugStatus = "Red: Match %.3f < %.3f".format(currentMatchScore, bestSavedMatchScore)
                         }
                     } else {
                         // Case No Chip: Just check Quality
                         if (effQuality > bestSavedQualityScore + 5f || userSelfiePath == null) {
                             shouldSave = true
                             debugStatus = "Kalite İyileşti (No Chip)"
                         } else {
                             debugStatus = "Red: Kalite Düşük (No Chip)"
                         }
                     }
                     
                     // UPDATE UI PERMANENTLY WITH MAX SCORE (INTEGER)
                     // Use bestSavedMatchScore or bestMatchScore? bestMatchScore tracks session max.
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

                         // Anti-spoof: 2.7x geniş crop (80x80) — MiniFASNetV2 bağlam + arka plan gerektirir
                         try {
                             val asCenterX = faceBox.exactCenterX()
                             val asCenterY = faceBox.exactCenterY()
                             val asHalfW = (faceBox.width() * 2.7f / 2f)
                             val asHalfH = (faceBox.height() * 2.7f / 2f)
                             val asLeft  = (asCenterX - asHalfW).coerceAtLeast(0f).toInt()
                             val asTop   = (asCenterY - asHalfH).coerceAtLeast(0f).toInt()
                             val asW     = ((asCenterX + asHalfW).coerceAtMost(fullBitmap.width.toFloat()) - asLeft).toInt().coerceAtLeast(1)
                             val asH     = ((asCenterY + asHalfH).coerceAtMost(fullBitmap.height.toFloat()) - asTop).toInt().coerceAtLeast(1)
                             val wideCrop = android.graphics.Bitmap.createBitmap(fullBitmap, asLeft, asTop, asW, asH)
                             val scaled80 = android.graphics.Bitmap.createScaledBitmap(wideCrop, 80, 80, true)
                             val asFile = File(cacheDir, "antispoof_crop.jpg")
                             java.io.FileOutputStream(asFile).use { scaled80.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                             antiSpoofCropPath = asFile.absolutePath
                             if (wideCrop != scaled80) scaled80.recycle()
                             wideCrop.recycle()
                         } catch (e: Exception) {
                             Log.w("Liveness", "Anti-spoof crop hatası (devam edilecek): ${e.message}")
                         }

                         bestSavedMatchScore = currentMatchScore
                         bestSavedQualityScore = effQuality

                         if (currentMatchScore > bestMatchScore) bestMatchScore = currentMatchScore

                         if (currentMatchScore > MATCH_THRESHOLD) {
                             isIdentityVerified = true
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
        // Telemetri: iOS bu olayı Sentry'ye yazıyordu, Android hiç yazmıyordu → Android'de canlılık
        // testinde takılan bir kullanıcı hiçbir iz bırakmıyordu. Yalnız yapısal alanlar: sebep,
        // tamamlanan hareket sayısı, yanlış deneme sayısı ve en iyi eşleşme skoru (skaler).
        val reason = if (isTimeout) "timeout" else if (customTitle != null) "too_many_errors" else "match_or_selfie"
        AppLog.warning(
            "Liveness başarısız (reason=$reason adım=$currentChallengeIndex/${challenges.size} " +
                "yanlış=$wrongAttempts skor=${(bestMatchScore * 100).toInt()}%) " +
                "[${savedFrameMetrics ?: "kare ölçüsü yok"}]",
            "Liveness"
        )
        runOnUiThread {
            try {
                // STOP CAMERA & TIMER COMPLETELY
                val cameraProvider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                countDownTimer?.cancel()
                binding.viewFinder.visibility = android.view.View.INVISIBLE // Hide preview surface
                
                // Inflate existing XML layout
                val dialogView = layoutInflater.inflate(R.layout.dialog_biometric_fail, null)
                val imgChip = dialogView.findViewById<android.widget.ImageView>(R.id.imgChipPhoto)
                val imgSelfie = dialogView.findViewById<android.widget.ImageView>(R.id.imgSelfie)
                val btnRetry = dialogView.findViewById<android.view.View>(R.id.btnRetry)
                val btnCancel = dialogView.findViewById<android.view.View>(R.id.btnCancel) // New Custom Button
                
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
                    // Re-start Phase
                    // Need to re-bind camera. 
                    // Simpler to just recreate activity or call startCamera() again?
                    // startCamera() handles re-binding.
                    binding.viewFinder.visibility = android.view.View.VISIBLE
                    startCamera() 
                }
                
                btnCancel.setOnClickListener {
                    finishWithDiagnostics()
                }
                
                dialog.show()
                
                /* 
                   Fix: Ensure Retry button inside custom view also works or remove it?
                   If XML has btnRetry, maybe hide it and use standard buttons?
                   Or keep both.
                */
                    
            } catch (e: Exception) {
                AppLog.error("Dialog hatası", "Liveness", e)
                Toast.makeText(this@LivenessActivity, "${getString(R.string.error_data_prefix)}${e.message}", Toast.LENGTH_LONG).show()
                finishWithDiagnostics()
            }
        }
    }
}
