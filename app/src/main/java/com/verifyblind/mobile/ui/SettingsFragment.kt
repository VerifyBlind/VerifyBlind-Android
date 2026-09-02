package com.verifyblind.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.verifyblind.mobile.MainActivity
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.KvkkBlockCardRequest
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.data.AppDatabase
import com.verifyblind.mobile.databinding.FragmentSettingsBinding
import com.verifyblind.mobile.util.AttestationBinding
import com.verifyblind.mobile.util.BiometricHelper
import com.verifyblind.mobile.util.IntegrityManagerHelper
import com.verifyblind.mobile.util.LegalTerms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        updateCloudBackupStatus()
    }

    private fun setupUI() {
        // 1. Version Info
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val vCode = PackageInfoCompat.getLongVersionCode(pInfo)
            binding.tvVersion.text = "${pInfo.versionName} ($vCode)"
        } catch (e: Exception) {
            binding.tvVersion.text = "1.0.0"
        }

        // 2. Privacy Policy (eski gizli stub + yeni cardPrivacyNotice — ikisi de aynı URL'yi açar)
        val privacyClickListener = View.OnClickListener {
            val lang = resources.configuration.locales[0].language
            val locale = if (lang == "tr") "tr" else "en"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://verifyblind.com/$locale/gizlilik"))
            startActivity(intent)
        }
        binding.btnPrivacyPolicy.setOnClickListener(privacyClickListener)
        binding.cardPrivacyNotice.setOnClickListener(privacyClickListener)

        // 2b. Kabul edilen hukuki metinler — sürüm + tarih. ZK mimaride bu kayıt yalnızca cihazda
        // durduğu için kullanıcının neyi ne zaman kabul ettiğini burada görebilmesi gerekir.
        binding.tvLegalTermsValue.text =
            LegalTerms.acceptanceSummary(requireContext()) ?: getString(R.string.legal_terms_settings_none)
        binding.cardLegalTerms.setOnClickListener {
            val lang = resources.configuration.locales[0].language
            val locale = if (lang == "tr") "tr" else "en"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://verifyblind.com/$locale/terms")))
        }

        // 3. Biometrics Toggle
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        binding.switchBiometrics.isChecked = prefs.getBoolean("biometric_enabled", false)
        binding.switchBiometrics.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("biometric_enabled", isChecked).apply()
        }

        // 4. Cloud Backup — ekran açar (bağlanma/eşitleme/silme BackupSettingsFragment'ta).
        binding.cardCloudBackup.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_backup)
        }

        // 5. Reset Wallet
        binding.btnResetWallet.setOnClickListener {
            showResetConfirmation()
        }

        // 6. Security Info
        binding.cardSecurityInfo.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.action_settingsFragment_to_securityInfoFragment)
        }

        // 7. Back
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // 8. History
        binding.cardHistory.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.action_settings_to_history)
        }

        // 9. Help
        binding.cardHelp.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.action_settings_to_help)
        }

        // 10b. SSS
        binding.cardFaq.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.action_settings_to_faq)
        }

        // 10c. Bize Ulaşın / Geri Bildirim
        binding.cardFeedback.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.action_settings_to_feedback)
        }

        // 10. Kartımı Engelle — kart varsa göster
        binding.cardBlockCard.setOnClickListener {
            confirmBlockCard()
        }
        checkBlockCardVisibility()

        // 11. Language
        setupLanguageSection()
    }

    private fun setupLanguageSection() {
        val vbPrefs = requireContext().getSharedPreferences("vb_prefs", Context.MODE_PRIVATE)
        updateLanguageSubtitle(vbPrefs)

        binding.cardLanguage.setOnClickListener {
            showLanguageDialog(vbPrefs)
        }
    }

    private fun updateLanguageSubtitle(vbPrefs: SharedPreferences) {
        val current = vbPrefs.getString("user_lang", "system") ?: "system"
        binding.tvLanguageCurrent.text = when (current) {
            "tr" -> getString(R.string.lang_turkish)
            "en" -> getString(R.string.lang_english)
            else -> getString(R.string.lang_system)
        }
    }

    private fun showLanguageDialog(vbPrefs: SharedPreferences) {
        val options = arrayOf(getString(R.string.lang_system), getString(R.string.lang_turkish), getString(R.string.lang_english))
        val values = arrayOf("system", "tr", "en")
        val current = vbPrefs.getString("user_lang", "system") ?: "system"
        val checkedItem = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.language_dialog_title))
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                dialog.dismiss()
                val selected = values[which]
                vbPrefs.edit().putString("user_lang", selected).apply()

                val localeTag = when (selected) {
                    "tr" -> "tr"
                    "en" -> "en"
                    else -> {
                        val phoneLang = android.content.res.Resources.getSystem().configuration.locales[0].language
                        if (phoneLang == "tr") "tr" else "en"
                    }
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
                activity?.recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun checkBlockCardVisibility() {
        val cardId = com.verifyblind.mobile.util.SecureStore.getCardId(requireContext())
        binding.cardBlockCard.visibility = if (!cardId.isNullOrEmpty() && false) View.VISIBLE else View.GONE
    }

    private fun confirmBlockCard() {
        val db = AppDatabase.getDatabase(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val cardItem = db.historyDao().getAllHistorySnapshot()
                .firstOrNull { it.cardId.isNotEmpty() && !it.isDeleted }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (cardItem == null) {
                    Toast.makeText(context, getString(R.string.error_no_blockable_card), Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.block_card_confirm_title))
                    .setMessage(getString(R.string.block_card_confirm_message))
                    .setPositiveButton(getString(R.string.block_card_confirm_button)) { _, _ ->
                        blockCard(cardItem.cardId, cardItem.nonce)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    private fun blockCard(cardId: String, nonce: String) {
        // Context'i coroutine dışında yakala — IO thread'de requireContext() fragment detach olursa patlar.
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cihaz bütünlüğü kanıtını card_id'ye BAĞLA: requestHash = Base64(SHA256(nonce ‖ card_id)).
                // Sunucu aynısını hesaplayıp token'a gömülü requestHash ile karşılaştırır → araya girip
                // gövdedeki card_id'yi başkasınınkiyle takas etmek imkânsızlaşır (TLS pinning yok).
                val requestHash = AttestationBinding.requestHash(nonce, cardId)
                val integrityToken = IntegrityManagerHelper.requestIntegrityToken(appContext, requestHash)
                if (integrityToken.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(context, getString(R.string.block_card_integrity_error), Toast.LENGTH_LONG).show()
                        }
                    }
                    return@launch
                }

                val req = KvkkBlockCardRequest(
                    nonce = nonce,
                    cardId = cardId,
                    reason = "USER_REQUEST",
                    integrityToken = integrityToken
                )
                val response = RetrofitClient.api.blockCard(req)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        if (response.isSuccessful) {
                            Toast.makeText(context, getString(R.string.block_card_blocked), Toast.LENGTH_LONG).show()
                        } else {
                            val code = response.code()
                            // 5xx = sunucu/altyapı tarafı (kullanıcı bağlantısı değil) → nazik mesaj, ham kod gösterme.
                            val msg = if (code == 409) getString(R.string.block_card_already_blocked)
                                else com.verifyblind.mobile.util.ServerErrorMessages.serverErrorOrNull(requireContext(), code)
                                    ?: "${getString(R.string.block_card_error_prefix)}$code"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(context, "${getString(R.string.block_card_network_error_prefix)}${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ---------- Cloud Backup ----------

    private fun updateCloudBackupStatus() {
        // Sürekli bağlantı durumu YOK (manuel Yedekle/Geri Yükle). Kart statik başlık gösterir;
        // eylemler açılan BackupFragment'ta.
        binding.tvCloud.text = getString(R.string.settings_backup_title)
        binding.tvCloudSubtitle.text = getString(R.string.settings_backup_desc)
        binding.tvCloudSubtitle.setTextColor(
            ContextCompat.getColor(requireContext(), com.verifyblind.mobile.R.color.sv_on_surface_variant)
        )
    }

    /**
     * Yedekleme ekranından dönüldüğünde satırın alt başlığı tazelenir (yeni bağlantı,
     * yeni eşitleme zamanı ya da kesilen bağlantı burada görünür hâle gelir).
     */
    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        updateCloudBackupStatus()
    }

    // ---------- Wallet Reset ----------

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.reset_wallet_title))
            .setMessage(getString(R.string.reset_wallet_message))
            .setPositiveButton(getString(R.string.reset_wallet_confirm)) { _, _ ->
                 // Biometric verify before destructive reset
                 BiometricHelper.authenticate(
                     activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                     onSuccess = {
                         performFullReset()
                     },
                     onError = { msg ->
                         (activity as? MainActivity)?.showMessage(getString(R.string.flow_cancelled), "${getString(R.string.operation_cancelled_biometric_prefix)}$msg")
                     }
                 )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun performFullReset() {
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            // A. Wipe Database
            AppDatabase.getDatabase(context).clearAllTables()

            // B. Wipe SharedPreferences
            context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("partner_cache", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("dropbox_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            com.verifyblind.mobile.util.SecureStore.clear(context)
            context.getSharedPreferences("VerifyBlind_Partners", Context.MODE_PRIVATE).edit().clear().commit()
            
            // Delete Keys
            com.verifyblind.mobile.crypto.CryptoUtils.deleteKey()

            // C. Wipe EncryptedSharedPreferences & Keystore
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                
                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    "secret_shared_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                encryptedPrefs.edit().clear().commit()
            } catch (e: Exception) {
                // Ignore if keys are broken
            }
            
            // D. Bulut sağlayıcı OAuth oturumlarını kapat (buluttaki .vfbackup dosyalarına DOKUNMAZ —
            // onlar kullanıcının kendi bulut hesabındaki bağımsız yedeklerdir).
            try { com.verifyblind.mobile.backup.DropboxProvider(context).logout() } catch (_: Exception) {}
            try { com.verifyblind.mobile.backup.GoogleDriveProvider(context).logout() } catch (_: Exception) {}

            // E. Clear Cache/Files
            try {
                context.cacheDir.deleteRecursively()
                context.filesDir.deleteRecursively()
            } catch (e: Exception) { }

            withContext(Dispatchers.Main) {
                // F. Restart App
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent?.let { startActivity(it) }
                activity?.finish()
                Runtime.getRuntime().exit(0)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
