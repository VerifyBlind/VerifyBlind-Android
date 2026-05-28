package com.verifyblind.mobile.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayoutMediator
import com.verifyblind.mobile.MainActivity
import com.verifyblind.mobile.R
import com.verifyblind.mobile.databinding.FragmentWalletBinding
import java.text.SimpleDateFormat
import java.util.Locale

class WalletFragment : Fragment() {

    private var _binding: FragmentWalletBinding? = null
    private val binding get() = _binding!!

    private var floatAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWalletBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_wallet_to_settings)
        }

        binding.btnScanQr.setOnClickListener {
            val mainActivity = activity as? MainActivity ?: return@setOnClickListener
            mainActivity.startScanFlow()
        }

        binding.btnAddId.setOnClickListener {
            val mainActivity = activity as? MainActivity ?: return@setOnClickListener
            if (mainActivity.isHandshakeFailed) mainActivity.showHandshakeErrorWarning { mainActivity.startAddCardFlow() }
            else mainActivity.startAddCardFlow()
        }

        binding.btnDemoMode.setOnClickListener {
            val mainActivity = activity as? MainActivity ?: return@setOnClickListener
            mainActivity.startDemoAddCardFlow()
        }

        binding.cardTapOverlay.setOnClickListener {
            binding.btnScanQr.performClick()
        }

        binding.btnHowItWorks.setOnClickListener {
            findNavController().navigate(R.id.action_wallet_to_help)
        }

        binding.btnDeleteText.setOnClickListener {
            val sheet = DeleteConfirmBottomSheet()
            sheet.onConfirm = { (activity as? MainActivity)?.deleteTicket() }
            sheet.show(parentFragmentManager, DeleteConfirmBottomSheet.TAG)
        }

        startNfcRingAnimation()

        requireActivity().supportFragmentManager.setFragmentResultListener("wallet_update", viewLifecycleOwner) { _, _ ->
            updateDashboardState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboardState()
    }

    private fun startNfcRingAnimation() {
        val outerRing = binding.nfcRingOuter
        val innerRing = binding.nfcRingInner

        val outerScaleX = ObjectAnimator.ofFloat(outerRing, View.SCALE_X, 0.7f, 1.25f).apply {
            duration = 3000; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }
        val outerScaleY = ObjectAnimator.ofFloat(outerRing, View.SCALE_Y, 0.7f, 1.25f).apply {
            duration = 3000; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }
        val outerAlpha = ObjectAnimator.ofFloat(outerRing, View.ALPHA, 0.35f, 0f).apply {
            duration = 3000; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }

        val innerScaleX = ObjectAnimator.ofFloat(innerRing, View.SCALE_X, 0.9f, 1.08f, 0.9f).apply {
            duration = 2500; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }
        val innerScaleY = ObjectAnimator.ofFloat(innerRing, View.SCALE_Y, 0.9f, 1.08f, 0.9f).apply {
            duration = 2500; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }

        AnimatorSet().apply {
            playTogether(outerScaleX, outerScaleY, outerAlpha, innerScaleX, innerScaleY)
            start()
        }
    }

    private fun startCardFloatAnimation() {
        floatAnimator?.cancel()
        val dp = resources.displayMetrics.density
        val floatAmountPx = 10f * dp
        floatAnimator = ObjectAnimator.ofFloat(binding.cardViewPager, View.TRANSLATION_Y, -floatAmountPx, floatAmountPx).apply {
            duration = 2800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun updateDashboardState() {
        val mainActivity = activity as? MainActivity
        val ticket = mainActivity?.signedTicketJson
        val demoEnabled = mainActivity?.isDemoEnabled ?: false

        binding.btnDemoMode.visibility = if (demoEnabled && ticket == null) View.VISIBLE else View.GONE

        if (ticket != null) {
            binding.layoutRegisteredState.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            val expiryDate = loadExpiryDate()
            setupCardCarousel(listOf(WalletCard(
                id = "1",
                name = "**** ****",
                type = "Doğrulanmış Kimlik",
                status = "Doğrulanmış",
                lastUsed = "—",
                expiryDate = expiryDate
            )))
            startCardFloatAnimation()
        } else {
            binding.layoutRegisteredState.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            floatAnimator?.cancel()
            floatAnimator = null
        }
    }

    private fun loadExpiryDate(): String {
        val prefs = requireContext().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("expiry_date", null)?.trim() ?: return "—"
        return formatExpiryDate(raw)
    }

    private fun formatExpiryDate(raw: String): String {
        if (raw.isBlank()) return "—"
        val inputFormats = listOf("yyMMdd", "yyyyMMdd", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd")
        val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        for (fmt in inputFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(raw) ?: continue
                return outputFormat.format(date)
            } catch (_: Exception) {}
        }
        return raw
    }

    private fun setupCardCarousel(cards: List<WalletCard>) {
        binding.tabLayout.visibility = if (cards.size > 1) View.VISIBLE else View.GONE
        val adapter = WalletCardAdapter(cards)
        binding.cardViewPager.adapter = adapter
        TabLayoutMediator(binding.tabLayout, binding.cardViewPager) { _, _ -> }.attach()
    }

    override fun onDestroyView() {
        floatAnimator?.cancel()
        floatAnimator = null
        super.onDestroyView()
        _binding = null
    }
}
