package com.verifyblind.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.verifyblind.mobile.databinding.FragmentFaqWebBinding

class FaqWebFragment : Fragment() {

    private var _binding: FragmentFaqWebBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFaqWebBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Custom UA lets the backend identify requests from in-app WebView and skip Turnstile.
            userAgentString = (userAgentString ?: "") + " VerifyBlind-Android-WebView"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                binding.progressBar.visibility = View.GONE
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        val lang = resources.configuration.locales[0].language
        val locale = if (lang == "tr") "tr" else "en"
        binding.webView.loadUrl("https://verifyblind.com/$locale/sss?onlycontent")
    }

    override fun onDestroyView() {
        binding.webView.destroy()
        super.onDestroyView()
        _binding = null
    }
}
