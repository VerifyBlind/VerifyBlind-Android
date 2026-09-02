package com.verifyblind.mobile.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
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

    /**
     * "İşlem geçmişimi de sil" seçildiğinde: silmeden ÖNCE yedek teklifi. Kullanıcı yedeklemeyi
     * seçerse Yedekle ekranına gider ve silme İPTAL olur (yedekten sonra tekrar silebilir);
     * "Sil" derse doğrudan siler.
     */
    private fun offerBackupBeforeDelete() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_backup_prompt_title))
            .setMessage(getString(R.string.delete_backup_prompt_message))
            .setPositiveButton(getString(R.string.delete_backup_prompt_backup)) { _, _ ->
                findNavController().navigate(R.id.nav_backup_settings)
            }
            .setNegativeButton(getString(R.string.delete_backup_prompt_delete)) { _, _ ->
                (activity as? MainActivity)?.deleteTicket(true)
            }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyWindowInsets()

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
            findNavController().navigate(R.id.action_wallet_to_history)
        }

        binding.btnHowItWorks.setOnClickListener {
            findNavController().navigate(R.id.action_wallet_to_help)
        }

        binding.btnDeleteText.setOnClickListener {
            val sheet = DeleteConfirmBottomSheet()
            sheet.onConfirm = { deleteHistory ->
                if (deleteHistory) offerBackupBeforeDelete() else (activity as? MainActivity)?.deleteTicket(false)
            }
            sheet.show(parentFragmentManager, DeleteConfirmBottomSheet.TAG)
        }

        // ── Bildirim izni soft-ask banner ──
        binding.btnNotifAllow.setOnClickListener {
            // Sistem prompt'unu bir kez gösterdiğimizi işaretle (iOS .notDetermined paritesi)
            // ve banner'ı gizle; ardından sistem iznini tetikle.
            notifPrefs().edit().putBoolean(PREF_NOTIF_PROMPT_SHOWN, true).apply()
            binding.cardNotifSoftAsk.visibility = View.GONE
            (activity as? MainActivity)?.launchNotificationPermission()
        }
        binding.btnNotifLater.setOnClickListener {
            // 2 gün ertele.
            notifPrefs().edit().putLong(
                PREF_NOTIF_SNOOZE_UNTIL,
                System.currentTimeMillis() + 2L * 24 * 3600 * 1000
            ).apply()
            binding.cardNotifSoftAsk.visibility = View.GONE
        }

        startNfcRingAnimation()

        requireActivity().supportFragmentManager.setFragmentResultListener("wallet_update", viewLifecycleOwner) { _, _ ->
            updateDashboardState()
            updateNotifBanner()
        }
    }

    override fun onResume() {
        super.onResume()
        // Koyu header → durum çubuğu ikonları beyaz (bu ekrana özel).
        setLightStatusBarIcons(false)
        updateDashboardState()
        updateNotifBanner()
    }

    override fun onPause() {
        super.onPause()
        // Diğer ekranlar açık zeminli → koyu ikonlara geri dön.
        setLightStatusBarIcons(true)
    }

    /** Header'ı durum çubuğunun arkasına uzatır (üst inset kadar padding);
     *  içeriği alt nav çubuğunun üstünde güvenli alanda tutar. */
    private fun applyWindowInsets() {
        val padV = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = padV + bars.top, bottom = padV)
            binding.contentArea.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setLightStatusBarIcons(light: Boolean) {
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
    }

    // ──────────────────────── Bildirim izni soft-ask ────────────────────────

    private fun notifPrefs() =
        requireContext().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)

    /** Banner gösterilmeli mi? Yalnız Android 13+, izin verilmemiş, prompt hiç gösterilmemiş
     *  ve snooze dolmuşken true. */
    private fun shouldShowNotifSoftAsk(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false  // <13: runtime izin yok
        val ctx = context ?: return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return false
        val prefs = ctx.getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTIF_PROMPT_SHOWN, false)) return false
        if (System.currentTimeMillis() < prefs.getLong(PREF_NOTIF_SNOOZE_UNTIL, 0L)) return false
        return true
    }

    private fun updateNotifBanner() {
        _binding?.cardNotifSoftAsk?.visibility =
            if (shouldShowNotifSoftAsk()) View.VISIBLE else View.GONE
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
            val rawExpiry = requireContext()
                .getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
                .getString("expiry_date", null)?.trim()
            setupCardCarousel(listOf(WalletCard(
                id = "1",
                name = "**** ****",
                type = getString(R.string.wallet_verified_identity),
                status = getString(R.string.wallet_verified),
                lastUsed = "—",
                expiryDate = formatExpiryDate(rawExpiry ?: ""),
                expired = isExpired(rawExpiry)
            )))
            startCardFloatAnimation()
        } else {
            binding.layoutRegisteredState.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            floatAnimator?.cancel()
            floatAnimator = null
        }
    }

    /** MRZ belge geçerlilik tarihi bugünden önceyse true. Parse edilemez/boşsa false (aktif kabul). */
    private fun isExpired(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val inputFormats = listOf("yyMMdd", "yyyyMMdd", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd")
        for (fmt in inputFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(raw) ?: continue
                return date.before(java.util.Date())
            } catch (_: Exception) {}
        }
        return false
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

    companion object {
        private const val PREF_NOTIF_PROMPT_SHOWN = "notif_softask_prompt_shown"
        private const val PREF_NOTIF_SNOOZE_UNTIL = "notif_softask_snooze_until"
    }
}
