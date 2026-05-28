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
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.verifyblind.mobile.api.LivenessAction
import com.verifyblind.mobile.databinding.ActivityLivenessBinding
import com.verifyblind.mobile.util.LivenessAnalyzer
import com.verifyblind.mobile.view.FaceOvalOverlayView
import android.graphics.RectF
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LivenessActivity : BaseActivity() {

    companion object {
        const val MATCH_THRESHOLD = 0.65f
    }

    private lateinit var binding: ActivityLivenessBinding
    private lateinit var cameraExecutor: ExecutorService
    private var originalBrightness = -1f
    
    // CameraX
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    
    // State
    private var challenges: List<LivenessAction> = emptyList()
    private var currentChallengeIndex = 0
    
    // Result Paths
    private var userSelfiePath: String? = null
    
    // AI Matching
    private var faceEmbedder: com.verifyblind.mobile.util.FaceEmbedder? = null
    private var chipEmbedding: FloatArray? = null
    private var isIdentityVerified = false
    private var bestMatchScore = 0f

    // Anti-Spoofing (Face Tracking)
    private var lockedTrackingId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("Liveness", "onCreate BAŞLADI") // FORCE LOG
        binding = ActivityLivenessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Parse Intent
        val challengeInts = intent.getIntegerArrayListExtra("challenges") ?: arrayListOf()
        challenges = challengeInts.map { LivenessAction.fromInt(it) }
        
        Log.d("Liveness", "Zorluklar: $challenges")

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        try {
            startCamera()
        } catch (t: Throwable) {
             Log.e("Liveness", "Kamera başlatma başarısız", t)
             showMessage("Başlatma Hatası", t.message ?: "Bilinmeyen hata")
        }
        
        // Initial UI
        binding.tvInstruction.text = "Hazırlanıyor..."
        // binding.progressBar.visibility = View.VISIBLE // Removed

        // Set bottom hint with actual threshold
        val thresholdPct = (MATCH_THRESHOLD * 100).toInt()
        binding.tvBottomHint.text =
            "Tüm hareketleri tamamlamadan önce yüzünüzün en az %$thresholdPct başarı ile tanındığından emin olun"

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
        Log.e("Liveness", "DEBUG: Alınan Chip Yolu: '$chipPath'")

        if (chipPath != null) {
            val chipFile = File(chipPath)
            if (chipFile.exists()) {
                Log.e("Liveness", "DEBUG: Dosya mevcut! Boyut: ${chipFile.length()}")
                cameraExecutor.submit {
                    try {
                        faceEmbedder = com.verifyblind.mobile.util.FaceEmbedder(this)
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        val bitmap = android.graphics.BitmapFactory.decodeFile(chipPath, opts)

                        if (bitmap != null) {
                            Log.e("Liveness", "DEBUG: Bitmap çözüldü! ${bitmap.width}x${bitmap.height}")

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
                                Log.e("Liveness", "Chip landmark: leftEye=$leftEyePos rightEye=$rightEyePos")
                            } catch (e: Exception) {
                                Log.w("Liveness", "Chip yüz tespiti başarısız, yedek embedding kullanılıyor", e)
                            }

                            chipEmbedding = faceEmbedder?.getEmbeddingAligned(bitmap, leftEyePos, rightEyePos)
                            val method = if (leftEyePos != null) "ALIGNED" else "FALLBACK"
                            Log.e("Liveness", "DEBUG: Chip Embedding ($method) Size: ${chipEmbedding?.size}")

                            runOnUiThread {
                                val ivChip = findViewById<android.widget.ImageView>(R.id.ivLiveChipPhoto)
                                ivChip.setImageBitmap(bitmap)
                            }
                        } else {
                            Log.e("Liveness", "DEBUG: Bitmap çözme BAŞARISIZ (Null)")
                        }
                    } catch (e: Exception) {
                        Log.e("Liveness", "DEBUG: AI başlatma istisnası", e)
                    }
                }
            } else {
                Log.e("Liveness", "DEBUG: Dosya belirtilen yolda bulunamadı!")
            }
        } else {
            Log.e("Liveness", "DEBUG: Intent'de Chip Yolu NULL!")
            runOnUiThread { hideMatchingUI() }
        }
    }

    private fun hideMatchingUI() {
        binding.tvBottomHint.visibility = View.GONE
        findViewById<android.widget.TextView>(R.id.tvLiveScore)?.visibility = View.GONE
        findViewById<android.widget.ImageView>(R.id.ivLiveChipPhoto)?.visibility = View.GONE
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

                // Video Recorder — en yüksek desteklenen kalite, SD'ye kadar fallback
                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // Analysis — maksimum çözünürlük (landmark hassasiyeti için kritik)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        Log.d("Liveness", "Analizör başlatılıyor...")
                        it.setAnalyzer(cameraExecutor, LivenessAnalyzer { face, imageProxy ->
                            processFace(face, imageProxy)
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis, videoCapture
                )
                
                // Start Logic after camera init
                runOnUiThread { 
                    startActionPhase() 
                }

            } catch (exc: Throwable) { // Throwable olarak değiştirildi (Error'ları da yakalar)
                Log.e("Liveness", "Kamera başlatma başarısız", exc)
                runOnUiThread {
                    showMessage("Kamera Hatası", exc.localizedMessage ?: "Kamera başlatılamadı.")
                }
                // Do not finish immediately so user can see toast? 
                // finish() 
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Timer properties
    private var countDownTimer: CountDownTimer? = null
    
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
        
        // Start 30s Timer
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvTimer.text = "${millisUntilFinished / 1000}"
            }

            override fun onFinish() {
                binding.tvTimer.text = "0"
                
                // Timeout!
                 // Stop Recording Logic removed
                
                showFailureSummary(isTimeout = true)
            }
        }.start()
        
        // Start Recording Liveness Video (Removed for optimizing bandwidth, just dummy for UI timer?)
        // Or remove recording entirely? Logic relies on recording?
        // Let's remove recording entirely.
        // startRecording... -> No.
        
        // Just trigger start callback immediately
        // startRecording(file) { showNextChallenge() }
        showNextChallenge()
    }

    private fun showNextChallenge() {
        if (currentChallengeIndex >= challenges.size) {
            // All done
            finishSuccess()
            return
        }

        val action = challenges[currentChallengeIndex]
        binding.tvStepCounter.text = "${currentChallengeIndex + 1}/${challenges.size}"
        
        val text = when (action) {
            LivenessAction.FaceLeft -> "Başını SOLA Çevir ⬅️"
            LivenessAction.FaceRight -> "Başını SAĞA Çevir ➡️"
            LivenessAction.Blink -> "Gözlerini Kırp 😉"
            LivenessAction.Smile -> "Gülümse 😊"
            else -> "???"
        }
        
        binding.tvInstruction.text = text
        binding.tvSubInstruction.text = "Hareketi gerçekleştirin"
    }

    // --- LOGIC ---
    // Single Phase: Actions
    private var bestSelfieScore = 0f

    private fun processFace(face: com.google.mlkit.vision.face.Face, imageProxy: androidx.camera.core.ImageProxy) {
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

    private var lastActionTime = 0L

    private fun processAction(face: com.google.mlkit.vision.face.Face) {
        if (currentChallengeIndex >= challenges.size) return
        if (System.currentTimeMillis() - lastActionTime < 2000) return 

        val target = challenges[currentChallengeIndex]
        val detected = detectGesture(face)
        
        if (detected != null) {
            if (detected == target) {
                // Success
                lastActionTime = System.currentTimeMillis()
                runOnUiThread {
                    binding.tvInstruction.text = "✅"
                    currentChallengeIndex++
                    binding.root.postDelayed({
                        showNextChallenge()
                    }, 1000)
                }
            } else {
                // WRONG MOVE LOGIC (Hybrid)
                if (target == LivenessAction.FaceLeft || target == LivenessAction.FaceRight) {
                     // STRICT: Reset on wrong head turn direction
                     lastActionTime = System.currentTimeMillis()
                     runOnUiThread {
                         android.widget.Toast.makeText(this, "Yanlış Hareket! Baştan Başlıyor...", android.widget.Toast.LENGTH_SHORT).show()
                         binding.tvInstruction.text = "❌ Hata!"
                         currentChallengeIndex = 0 
                         binding.root.postDelayed({
                             showNextChallenge()
                         }, 1500)
                     }
                } else {
                    // IGNORE (Blink/Smile) - Do nothing
                }
            }
        }
    }
    
    private fun detectGesture(face: com.google.mlkit.vision.face.Face): LivenessAction? {
        val YAW_THRESHOLD = 20f
        val SMILE_THRESHOLD = 0.8f
        val BLINK_THRESHOLD = 0.1f
        
        // SWAPPED Directions based on User Feedback
        if (face.headEulerAngleY > YAW_THRESHOLD) return LivenessAction.FaceLeft
        if (face.headEulerAngleY < -YAW_THRESHOLD) return LivenessAction.FaceRight
        
        if ((face.smilingProbability ?: 0f) > SMILE_THRESHOLD) return LivenessAction.Smile
        
        if ((face.leftEyeOpenProbability ?: 1f) < BLINK_THRESHOLD && 
            (face.rightEyeOpenProbability ?: 1f) < BLINK_THRESHOLD) return LivenessAction.Blink
            
        return null
    }

    private fun stopRecording() {
        // No-op
    }

    private fun finishSuccess() {
        // Check if we have a valid selfie
        if (userSelfiePath == null) {
            runOnUiThread {
                 showMessage("Selfie Hatası", "Yüzünüz net bir şekilde çekilemedi. Lütfen ışıklı bir ortamda tekrar deneyin.") {
                     startActionPhase()
                 }
            }
            return
        }
        
        // Check AI Verification
        // Check AI Verification
        if (chipEmbedding != null) {
            if (!isIdentityVerified) {
                // FAILURE -> Dialog instead of Toast
                showFailureSummary(isTimeout = false)
                return
            }
        } else {
             Log.w("Liveness", "Chip fotoğrafı eksik olduğundan AI kontrolü atlanıyor.")
        }
    
        stopRecording()
        countDownTimer?.cancel() // STOP Timer on success
        
        // Wait a bit for file finalize (Safety)
        binding.root.postDelayed({
            restoreBrightness()
            val intent = Intent()
            intent.putExtra("user_selfie", userSelfiePath)
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

    override fun onDestroy() {
        super.onDestroy()
        restoreBrightness()
        cameraExecutor.shutdown()
        stopRecording()
        countDownTimer?.cancel()
    }
    
    private var lastCaptureTime = 0L
    private var bestSavedMatchScore = -1f // Track the match score of the saved file
    private var bestSavedQualityScore = -1f
    
    private fun captureFrame(imageProxy: androidx.camera.core.ImageProxy, face: com.google.mlkit.vision.face.Face, qualityScore: Float) {
        if (System.currentTimeMillis() - lastCaptureTime < 400) return // Throttle 400ms
        lastCaptureTime = System.currentTimeMillis()

        val faceBox = face.boundingBox

        try {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation)

                val fullBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

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

                    // Landmark pozisyonlarını crop koordinat uzayına çevir
                    val leftEyeLandmark  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val leftEyeInCrop  = leftEyeLandmark?.let  { PointF(it.x - left, it.y - top) }
                    val rightEyeInCrop = rightEyeLandmark?.let { PointF(it.x - left, it.y - top) }

                    // 112x112 hizalanmış bitmap — hem scoring hem enclave'e gönderim için
                    val alignedBitmap = faceEmbedder?.getAlignedBitmap(croppedBitmap, leftEyeInCrop, rightEyeInCrop)

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
                         else if (Math.abs(currentMatchScore - bestSavedMatchScore) < 0.005f && qualityScore > bestSavedQualityScore + 5f) {
                             shouldSave = true
                             reason = "Daha Net Fotoğraf"
                             debugStatus = "Kalite İyileşti (Q: %.0f)".format(qualityScore)
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
                         if (qualityScore > bestSavedQualityScore + 5f || userSelfiePath == null) {
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
                         // Hizalanmış 112x112 bitmap'i kaydet — enclave aynı görüntüyü işler
                         val saveTarget = alignedBitmap ?: croppedBitmap
                         val file = File(cacheDir, "selfie_best.jpg")
                         val fos = java.io.FileOutputStream(file)
                         saveTarget.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos)
                         fos.flush()
                         fos.close()
                         
                         userSelfiePath = file.absolutePath
                         bestSavedMatchScore = currentMatchScore
                         bestSavedQualityScore = qualityScore
                         
                         if (currentMatchScore > bestMatchScore) bestMatchScore = currentMatchScore
                         
                         if (currentMatchScore > MATCH_THRESHOLD) {
                             isIdentityVerified = true
                         }
                         Log.d("Liveness", "Selfie kaydedildi: Eşleşme=$currentMatchScore, Neden=$reason")
                     }
                 }
            }
        } catch (e: Exception) {
            Log.e("Liveness", "Fotoğraf çekme başarısız", e)
        }
    }

    private fun showFailureSummary(isTimeout: Boolean = false, customTitle: String? = null, customMessage: String? = null) {
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
                    tvTitle?.text = "Süre Doldu"
                    tvMessage?.text = "Hareketleri süre bitmeden tamamlayamadınız.\nLütfen tekrar deneyin."
                    
                    // Hide Images & Labels
                    layoutImages?.visibility = android.view.View.GONE
                    layoutLabels?.visibility = android.view.View.GONE
                } else {
                    tvTitle?.text = "Eşleşme Başarısız"
                    tvMessage?.text = "Kimlik fotoğrafınız ile çektiğiniz selfie eşleşmedi. Lütfen yüzünüzün net göründüğünden emin olun."
                    
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
                    dialog.dismiss()
                    // Re-start Phase
                    // Need to re-bind camera. 
                    // Simpler to just recreate activity or call startCamera() again?
                    // startCamera() handles re-binding.
                    binding.viewFinder.visibility = android.view.View.VISIBLE
                    startCamera() 
                }
                
                btnCancel.setOnClickListener {
                    finish()
                }
                
                dialog.show()
                
                /* 
                   Fix: Ensure Retry button inside custom view also works or remove it?
                   If XML has btnRetry, maybe hide it and use standard buttons?
                   Or keep both.
                */
                    
            } catch (e: Exception) {
                Log.e("Liveness", "Dialog hatası", e)
                Toast.makeText(this@LivenessActivity, "Hata: Diyalog Açılamadı (${e.message})", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
