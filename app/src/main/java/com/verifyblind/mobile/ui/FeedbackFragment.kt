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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSubmit.setOnClickListener { submit() }

        // Akış içinden gelindiyse konu ön-doldurulur ve odak doğrudan mesaja gider.
        arguments?.getString("subject")?.takeIf { it.isNotBlank() }?.let { prefilled ->
            binding.etSubject.setText(prefilled)
            binding.etMessage.requestFocus()
        }
    }

    private fun submit() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val subject = binding.etSubject.text.toString().trim()
        val message = binding.etMessage.text.toString().trim()

        if (name.isEmpty() || email.isEmpty() || subject.isEmpty() || message.isEmpty()) {
            toast(getString(R.string.feedback_error_missing)); return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(getString(R.string.feedback_error_invalid_email)); return
        }

        val fullMessage = message + "\n\n" + deviceMetadata()

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
                        language = language
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

    /** Triyaj için mesaja eklenen cihaz/sürüm bloğu (kullanıcı-arayüzü değil → sabit etiket). */
    private fun deviceMetadata(): String = buildString {
        append("───\n")
        append("Uygulama / App: Android v").append(BuildConfig.VERSION_NAME).append('\n')
        append("Cihaz / Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("OS: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSubmit.isEnabled = !busy
        binding.etName.isEnabled = !busy
        binding.etEmail.isEnabled = !busy
        binding.etSubject.isEnabled = !busy
        binding.etMessage.isEnabled = !busy
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
