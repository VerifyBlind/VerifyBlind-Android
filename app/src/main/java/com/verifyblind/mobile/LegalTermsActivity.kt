package com.verifyblind.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.verifyblind.mobile.databinding.ActivityLegalTermsBinding
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.LegalTerms

/**
 * Hukuki metin kabul kapısı (clickwrap).
 *
 * Kabul alınmadan uygulamaya girilemez: geri tuşu uygulamayı kapatır, onay kutusu işaretlenmeden
 * "Kabul et" etkinleşmez. Kabul edilince sürüm + zaman damgası cihaza yazılır — zero-knowledge
 * mimaride sunucuda kişiye bağlı kabul kaydı tutulamadığı için kanıt budur (bkz. [LegalTerms]).
 *
 * İki bağlamda açılır:
 *   - İlk açılış: [SplashActivity] gömülü taban sürüme karşı kontrol eder (ağ gerekmez).
 *   - Metin güncellemesi: sunucudaki sürüm yükseltilince [MainActivity] bu ekranı yeniden açar.
 */
class LegalTermsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalTermsBinding

    /** Kapının dayattığı sürüm — kabul bununla kaydedilir. */
    private val requiredVersion: String by lazy {
        intent.getStringExtra(EXTRA_REQUIRED_VERSION)?.takeIf { it.isNotBlank() }
            ?: LegalTerms.BASELINE_VERSION
    }

    /** Güncelleme bağlamında farklı bir giriş metni gösterilir. */
    private val isUpdate: Boolean by lazy { intent.getBooleanExtra(EXTRA_IS_UPDATE, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLegalTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (isUpdate) binding.tvLegalIntro.setText(R.string.legal_terms_updated_intro)
        binding.tvLegalVersion.text = getString(R.string.legal_terms_version_label, requiredVersion)

        binding.tvLinkTerms.setOnClickListener { openUrl(URL_TERMS) }
        binding.tvLinkDpa.setOnClickListener { openUrl(URL_DPA) }
        binding.tvLinkDisclosure.setOnClickListener { openUrl(URL_DISCLOSURE) }

        binding.cbLegalAccept.setOnCheckedChangeListener { _, checked ->
            binding.btnLegalAccept.isEnabled = checked
        }

        binding.btnLegalAccept.setOnClickListener {
            LegalTerms.recordAcceptance(this, requiredVersion)
            AppLog.info("Hukuki metin kabul edildi: sürüm $requiredVersion")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnLegalDecline.setOnClickListener { finishAffinity() }

        // Geri tuşuyla kapı atlanamaz: reddetmek uygulamadan çıkmak demektir.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.warning("Hukuki metin bağlantısı açılamadı: $url", throwable = e)
        }
    }

    companion object {
        const val EXTRA_REQUIRED_VERSION = "required_version"
        const val EXTRA_IS_UPDATE = "is_update"

        private const val URL_TERMS = "https://verifyblind.com/terms"
        private const val URL_DPA = "https://verifyblind.com/dpa"
        private const val URL_DISCLOSURE = "https://verifyblind.com/disclosure"

        fun intent(context: android.content.Context, requiredVersion: String, isUpdate: Boolean = false): Intent =
            Intent(context, LegalTermsActivity::class.java)
                .putExtra(EXTRA_REQUIRED_VERSION, requiredVersion)
                .putExtra(EXTRA_IS_UPDATE, isUpdate)
    }
}
