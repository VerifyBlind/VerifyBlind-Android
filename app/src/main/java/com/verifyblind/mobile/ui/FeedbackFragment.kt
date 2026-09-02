package com.verifyblind.mobile.ui

import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.FeedbackErrorResponse
import com.verifyblind.mobile.api.FeedbackRequest
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.databinding.FragmentFeedbackBinding
import com.verifyblind.mobile.util.ServerErrorMessages
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Bize Ulaşın" / geri bildirim ekranı. Landing formuyla aynı POST /api/feedback
 * sözleşmesini kullanır; source="mobile" gönderildiği için Turnstile atlanır (captcha yok).
 * Cihaz/sürüm bilgisi triyaj için mesajın sonuna eklenir (sunucuda ayrı alan yok).
 */
class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!

    private val language: String by lazy {
        if (Locale.getDefault().language.lowercase().startsWith("en")) "en" else "tr"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** Başarısız denemedeki selfie — YALNIZ kullanıcı onay kutusunu işaretlerse gönderilir. */
    private var diagnosticPhoto: java.io.File? = null

    /**
     * Çip fotoğrafının hizalanmış 112×112 kırpımı — AYRI onay kutusuna bağlı.
     *
     * Neden ayrı: benzerlik ikili bir fonksiyondur, tek tarafla skor yeniden üretilemez; yani bu ek
     * teşhis için gerçekten gerekli. Ama selfie kullanıcının o an çektirdiği kare, bu ise kimlik
     * belgesinin içinden çıkan resmî görüntü. Selfie kutusunu işaretlemek bunu işaretlemiş SAYMAZ.
     */
    private var chipPhoto: java.io.File? = null

    /** Son denemenin skaler ölçüleri ve huni akış anahtarı — mesajın sonuna eklenir. */
    private var livenessDiag: String? = null
    private var flowId: String? = null

    /**
     * Cihazın FCM token'ı — "düzeltince haber ver" kutusu YALNIZ bu varsa gösterilir.
     * Token yoksa (kullanıcı bildirim iznini hiç vermemiş) kutuyu göstermek, tutamayacağımız
     * bir söz vermek olurdu.
     */
    private var pushToken: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSubmit.setOnClickListener { submit() }

        // Akış içinden gelindiyse konu ön-doldurulur ve odak doğrudan mesaja gider.
        arguments?.getString("subject")?.takeIf { it.isNotBlank() }?.let { prefilled ->
            binding.etSubject.setText(prefilled)
            binding.etMessage.requestFocus()
        }

        // Teşhis fotoğrafı yalnız akış içinden gelindiyse ve dosya gerçekten varsa TEKLİF edilir.
        // Kutu kapalı başlar; işaretlenmeden fotoğraf hiçbir yere gitmez.
        diagnosticPhoto = arguments?.getString("photo_path")
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() && it.length() > 0 }
        if (diagnosticPhoto != null) {
            binding.cbSharePhoto.visibility = View.VISIBLE
            binding.tvSharePhotoNote.visibility = View.VISIBLE
        }

        chipPhoto = arguments?.getString("chip_photo_path")
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() && it.length() > 0 }
        if (chipPhoto != null) {
            binding.cbShareChipPhoto.visibility = View.VISIBLE
            binding.tvShareChipPhotoNote.visibility = View.VISIBLE
        }

        livenessDiag = arguments?.getString("liveness_diag")?.takeIf { it.isNotBlank() }
        flowId = arguments?.getString("flow_id")?.takeIf { it.isNotBlank() }

        pushToken = com.verifyblind.mobile.util.SecureStore.getFcmToken(requireContext())
            ?.takeIf { it.isNotBlank() }
        if (pushToken != null) {
            binding.cbNotifyWhenFixed.visibility = View.VISIBLE
            binding.tvNotifyWhenFixedNote.visibility = View.VISIBLE
        }
    }

    private fun submit() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val subject = binding.etSubject.text.toString().trim()
        val message = binding.etMessage.text.toString().trim()

        // Ad da e-posta gibi İSTEĞE BAĞLI. Bu kutu çoğunlukla bir hatanın hemen ardından açılıyor
        // ve amacımız sorunu toplamak — kimin yaşadığını öğrenmek değil. Zorunlu her alan bir
        // vazgeçme sebebi; geriye kalan tek şey sessizlik oluyor. Zorunlu olan yalnız konu+mesaj
        // (ekranda kırmızı yıldızla işaretli). Adres GİRİLİRSE biçimi doğrulanır — yanlış yazılmış
        // adres, adres yokluğundan kötüdür: kullanıcı boşuna yanıt bekler.
        if (subject.isEmpty() || message.isEmpty()) {
            toast(getString(R.string.feedback_error_missing)); return
        }
        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(getString(R.string.feedback_error_invalid_email)); return
        }

        val fullMessage = message + "\n\n" + deviceMetadata()

        // Rıza kutusu işaretliyse fotoğraf base64'e çevrilir; aksi halde hiç okunmaz.
        val consented = binding.cbSharePhoto.isChecked && diagnosticPhoto != null
        val photoBase64 = if (consented) encodeOrNull(diagnosticPhoto) else null

        // Çip kırpımı AYRI kapıdan geçer: selfie kutusu işaretli olsa bile bu kutu işaretsizse
        // dosya OKUNMAZ. Sunucuda da kapılar ayrı (FeedbackController.TryReadChipPhoto).
        val chipConsented = binding.cbShareChipPhoto.isChecked && chipPhoto != null
        val chipBase64 = if (chipConsented) encodeOrNull(chipPhoto) else null

        // Bildirim rızası da AYRI kapı: işaretsizse token hiç gönderilmez (sunucu da okumaz).
        val notifyConsented = binding.cbNotifyWhenFixed.isChecked && pushToken != null

        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = RetrofitClient.api.sendFeedback(
                    FeedbackRequest(
                        name = name,
                        email = email,
                        subject = subject,
                        message = fullMessage,
                        source = "mobile",
                        language = language,
                        photoConsent = consented,
                        photoBase64 = photoBase64,
                        chipPhotoConsent = chipConsented,
                        chipPhotoBase64 = chipBase64,
                        // Aşağıdakiler mesaj gövdesinde de var (bkz. deviceMetadata); ayrı alan
                        // olarak gitmelerinin sebebi sunucunun kaydı SORGULANABİLİR tutması —
                        // destek postası artık tek arşiv değil.
                        flowId = flowId,
                        platform = "android",
                        appVersion = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                        diagnostics = livenessDiag,
                        notifyConsent = notifyConsented,
                        pushToken = if (notifyConsented) pushToken else null
                    )
                )
                if (!isAdded) return@launch
                if (res.isSuccessful) {
                    Toast.makeText(requireContext(), getString(R.string.feedback_success), Toast.LENGTH_LONG).show()
                    findNavController().navigateUp()
                } else {
                    toast(errorMessageFor(res.code(), res.errorBody()?.string()))
                }
            } catch (t: Throwable) {
                if (isAdded) toast(ServerErrorMessages.connectionFailed(requireContext()))
            } finally {
                if (isAdded) setBusy(false)
            }
        }
    }

    /** HTTP koduna ve gövdedeki `code` alanına göre yerelleştirilmiş hata mesajı. */
    private fun errorMessageFor(httpCode: Int, body: String?): String {
        // 5xx / Cloudflare 52x → nazik sunucu mesajı (tek kaynak ServerErrorMessages).
        ServerErrorMessages.serverErrorOrNull(requireContext(), httpCode)?.let { return it }
        if (httpCode == 429) return getString(R.string.feedback_error_rate_limited)
        val code = try {
            body?.let { Gson().fromJson(it, FeedbackErrorResponse::class.java)?.code }
        } catch (_: Throwable) {
            null
        }
        return when (code) {
            "MISSING_FIELDS" -> getString(R.string.feedback_error_missing)
            "INVALID_EMAIL" -> getString(R.string.feedback_error_invalid_email)
            "TOO_LONG" -> getString(R.string.feedback_error_too_long)
            else -> getString(R.string.feedback_error_generic)
        }
    }

    /** Rıza kutusu işaretliyse dosyayı base64'e çevirir; okunamazsa sessizce düşer. */
    private fun encodeOrNull(file: java.io.File?): String? = try {
        file?.let { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }
    } catch (e: Exception) {
        AppLog.info("Teşhis fotoğrafı okunamadı: ${e.javaClass.simpleName}", "Feedback")
        null
    }

    /**
     * Triyaj için mesaja eklenen cihaz/sürüm + teşhis bloğu (kullanıcı-arayüzü değil → sabit etiket).
     *
     * Buraya kadar yalnız cihaz ve sürüm gidiyordu: destek kutusundaki posta "benzerlik yetersiz"
     * diyor, yanında 112×112'lik bir kırpım duruyor ve HİÇBİR SAYI yoktu — skor kaçtı, kare ne kadar
     * karanlıktı, kafa ne kadar dönüktü, hangi adımda kalındı, hiçbiri bilinmiyordu. Hepsi zaten
     * hesaplanıyor ve cihazdaki loga yazılıyordu; buraya taşınmaları skaler oldukları için
     * ücretsiz — biyometrik veri değil, görüntü değil.
     *
     * flow_id ise anlatıyı hunideki satıra bağlayan tek anahtar: onsuz "NFC'de zorlandım" diyen
     * postayla, o akışın hangi adımda kaç saniye durduğunu gösteren satır arasında hiçbir bağ yok.
     */
    private fun deviceMetadata(): String = buildString {
        append("───\n")
        append("Uygulama / App: Android v").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")").append('\n')
        append("Cihaz / Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("OS: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        flowId?.let { append('\n').append("Akış / Flow: ").append(it) }
        livenessDiag?.let { append('\n').append(it) }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSubmit.isEnabled = !busy
        binding.etName.isEnabled = !busy
        binding.etEmail.isEnabled = !busy
        binding.etSubject.isEnabled = !busy
        binding.etMessage.isEnabled = !busy
        binding.cbSharePhoto.isEnabled = !busy
        binding.cbShareChipPhoto.isEnabled = !busy
        binding.cbNotifyWhenFixed.isEnabled = !busy
        binding.pbSubmit.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
