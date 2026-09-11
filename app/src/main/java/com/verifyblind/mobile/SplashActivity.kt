package com.verifyblind.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.databinding.ActivitySplashBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.IntegrityManagerHelper
import com.verifyblind.mobile.util.LegalTerms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTitle()
        startAnimations()

        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()

            val integrityJob = async(Dispatchers.IO) {
                IntegrityManagerHelper.prepare(this@SplashActivity)
            }

            // Kapı gerekiyorsa yürürlükteki sürümü sunucudan öğren. Integrity hazırlığıyla
            // PARALEL gider ve splash'in kendi bekleme süresinin altında bir timeout'u vardır —
            // yani açılışı pratikte uzatmaz.
            val legalGateNeeded = LegalTerms.needsAcceptance(this@SplashActivity)
            val legalVersionJob =
                if (legalGateNeeded) async(Dispatchers.IO) { fetchServerLegalVersion() } else null

            val installOk = checkInstallSource()

            integrityJob.await()

            // Minimum visible time so animation has a chance to show
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < MIN_SPLASH_MS) delay(MIN_SPLASH_MS - elapsed)

            withContext(Dispatchers.Main) {
                if (!installOk) {
                    showInstallError()
                    return@withContext
                }
                // Hukuki metin kapısı. Kapı ağ OLMADAN da çalışır (gömülü taban sürüm), ama
                // sunucu sürümüne ulaşılabildiyse kapı ONUNLA açılır.
                //
                // Sunucu sürümü beklenmeden taban sürümle açıldığında kullanıcı üst üste İKİ
                // onay ekranı görüyordu: önce tabanı onaylıyor, cihaza o sürüm yazılıyor, hemen
                // ardından MainActivity app-config'i çekince sunucudaki daha yeni sürüm için kapı
                // "metinler güncellendi" diyerek yeniden açılıyordu (cihazda görüldü 2026-09-11,
                // taban 1.0 iken sunucu 1.1). Kabul kaydı da okunan metnin sürümünü taşımıyordu.
                val next = if (legalGateNeeded) {
                    val serverVersion = legalVersionJob?.await()
                    LegalTermsActivity.intent(
                        this@SplashActivity,
                        LegalTerms.requiredVersion(serverVersion)
                    )
                } else {
                    Intent(this@SplashActivity, MainActivity::class.java)
                }
                startActivity(next)
                finish()
            }
        }
    }

    private fun setupTitle() {
        val text = "VerifyBlind"
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0, 6,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.sv_secondary)),
            6, 11,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvSplashTitle.text = spannable
    }

    private fun startAnimations() {
        // Logo: fade-in + slow zoom
        val logoFade = AlphaAnimation(0f, 1f).apply { duration = 800 }
        val logoScale = ScaleAnimation(
            0.92f, 1.08f, 0.92f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3200
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val logoSet = AnimationSet(false).apply {
            addAnimation(logoFade)
            addAnimation(logoScale)
        }
        binding.ivSplashLogo.startAnimation(logoSet)

        // Outer glow: breathes slightly slower and larger
        val outerScale = ScaleAnimation(
            1.0f, 1.18f, 1.0f, 1.18f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val outerFade = AlphaAnimation(0f, 1f).apply { duration = 1000 }
        val outerSet = AnimationSet(false).apply {
            addAnimation(outerFade)
            addAnimation(outerScale)
        }
        binding.viewGlowOuter.startAnimation(outerSet)

        // Mid glow: in sync with logo but slightly different phase
        val midScale = ScaleAnimation(
            1.05f, 1.14f, 1.05f, 1.14f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3200
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startOffset = 200
        }
        val midFade = AlphaAnimation(0f, 1f).apply { duration = 800 }
        val midSet = AnimationSet(false).apply {
            addAnimation(midFade)
            addAnimation(midScale)
        }
        binding.viewGlowMid.startAnimation(midSet)

        // Inner glow: subtle pulse, brighter core
        val innerScale = ScaleAnimation(
            0.95f, 1.08f, 0.95f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3200
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startOffset = 100
        }
        val innerFade = AlphaAnimation(0f, 1f).apply { duration = 600 }
        val innerSet = AnimationSet(false).apply {
            addAnimation(innerFade)
            addAnimation(innerScale)
        }
        binding.viewGlowInner.startAnimation(innerSet)

        // Title fade in with slight delay
        val titleFade = AlphaAnimation(0f, 1f).apply {
            duration = 700
            startOffset = 400
            fillBefore = true
        }
        binding.tvSplashTitle.startAnimation(titleFade)

        val taglineFade = AlphaAnimation(0f, 1f).apply {
            duration = 700
            startOffset = 600
            fillBefore = true
        }
        binding.tvSplashTagline.startAnimation(taglineFade)
    }

    /**
     * Yürürlükteki hukuki metin sürümünü sunucudan okur (`/api/public/app-config`).
     *
     * Ağ yoksa, istek yavaşsa veya yanıt bozuksa null döner ve gömülü taban sürüm geçerli kalır —
     * kapı hiçbir koşulda düşmez (fail-open YOK) ve sunucu sürümü yalnızca YÜKSELTEBİLİR
     * (bkz. [LegalTerms.requiredVersion]).
     */
    private suspend fun fetchServerLegalVersion(): String? =
        withTimeoutOrNull(LEGAL_VERSION_TIMEOUT_MS) {
            try {
                val response = RetrofitClient.api.getAppConfig()
                if (response.isSuccessful) response.body()?.legalTermsVersion else null
            } catch (e: Exception) {
                AppLog.warning("Hukuki metin sürümü alınamadı: ${e.message}", TAG)
                null
            }
        }

    private fun checkInstallSource(): Boolean {
        if (BuildConfig.DEBUG) return true
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
            installer == "com.android.vending"
        } catch (e: Exception) {
            false
        }
    }

    private fun showInstallError() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.security_warning_title))
            .setMessage(getString(R.string.security_warning_message))
            .setPositiveButton(getString(R.string.btn_go_to_play)) { _, _ ->
                val uri = android.net.Uri.parse("market://details?id=$packageName")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    )
                }
                finish()
            }
            .setNegativeButton(getString(R.string.btn_close)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val MIN_SPLASH_MS = 2200L

        private const val TAG = "Splash"

        /** Splash'in kendi bekleme süresinin altında: ağ yavaşsa açılış uzamaz, tabana düşülür. */
        private const val LEGAL_VERSION_TIMEOUT_MS = 2000L
    }
}
