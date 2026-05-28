package com.verifyblind.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
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
import com.verifyblind.mobile.backup.CloudBackupManager
import com.verifyblind.mobile.backup.CloudProvider
import com.verifyblind.mobile.backup.DropboxProvider
import com.verifyblind.mobile.backup.GoogleDriveProvider
import com.verifyblind.mobile.data.AppDatabase
import com.verifyblind.mobile.databinding.FragmentSettingsBinding
import com.verifyblind.mobile.util.BiometricHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Init providers and register ActivityResults (MUST be in onCreate)
        initCloudProvidersAndRegister()
    }

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

        // Check for automatic trigger from HistoryFragment
        if (arguments?.getBoolean("auto_open_backup") == true) {
            // Remove the flag so it doesn't trigger again on rotation
            arguments?.remove("auto_open_backup")
            
            val status = CloudBackupManager.getStatus(requireContext())
            if (!status.isConnected) {
                showCloudProviderDialog()
            }
        }
    }

    private fun initCloudProvidersAndRegister() {
        // Only re-register if needed, but onCreate is called once per fragment instance lifecycle
        val ctx = requireContext()
        val gDrive = GoogleDriveProvider(ctx)
        
        // IMPORTANT: Must register result listener now
        gDrive.register(this)
        
        CloudBackupManager.registerProvider(gDrive)
        CloudBackupManager.registerProvider(DropboxProvider(ctx))
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

        // 2. Privacy Policy
        binding.btnPrivacyPolicy.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://verifyblind.com/privacy"))
            startActivity(intent)
        }

        // 3. Biometrics Toggle
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        binding.switchBiometrics.isChecked = prefs.getBoolean("biometric_enabled", false)
        binding.switchBiometrics.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("biometric_enabled", isChecked).apply()
        }

        // 4. Cloud Backup
        binding.cardCloudBackup.setOnClickListener {
            val status = CloudBackupManager.getStatus(requireContext())
            if (status.isConnected) {
                showConnectedBackupOptions(status)
            } else {
                showCloudProviderDialog()
            }
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
            "tr" -> "Türkçe"
            "en" -> "English"
            else -> "Sistem (varsayılan)"
        }
    }

    private fun showLanguageDialog(vbPrefs: SharedPreferences) {
        val options = arrayOf("Sistem (varsayılan)", "Türkçe", "English")
        val values = arrayOf("system", "tr", "en")
        val current = vbPrefs.getString("user_lang", "system") ?: "system"
        val checkedItem = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Language / Dil")
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
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun checkBlockCardVisibility() {
        val cardId = com.verifyblind.mobile.util.SecureStore.getCardId(requireContext())
        binding.cardBlockCard.visibility = if (!cardId.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmBlockCard() {
        val db = AppDatabase.getDatabase(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val cardItem = db.historyDao().getAllHistorySnapshot()
                .firstOrNull { it.cardId.isNotEmpty() && !it.isDeleted }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (cardItem == null) {
                    Toast.makeText(context, "Engellenecek kart bulunamadı.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Kartımı Engelle")
                    .setMessage("Kimlik kartınız çalındı veya kayboldu mu?\n\nEngelleme sonrası bu kart ile doğrulama yapılamayacak. Bu işlem geri alınamaz.")
                    .setPositiveButton("Engelle") { _, _ ->
                        blockCard(cardItem.cardId, cardItem.nonce)
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun blockCard(cardId: String, nonce: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = KvkkBlockCardRequest(nonce = nonce, cardId = cardId, reason = "USER_REQUEST")
                val response = RetrofitClient.api.blockCard(req)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        if (response.isSuccessful) {
                            Toast.makeText(context, "Kimlik kartınız engellendi.", Toast.LENGTH_LONG).show()
                        } else {
                            val msg = if (response.code() == 409) "Bu kart zaten engellenmiş."
                                      else "Engelleme başarısız: ${response.code()}"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(context, "Ağ hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ---------- Cloud Backup ----------

    private fun updateCloudBackupStatus() {
        val status = CloudBackupManager.getStatus(requireContext())

        if (status.isConnected) {
            val provider = CloudBackupManager.getProvider(status.providerName!!)
            val providerName = provider?.displayName ?: status.providerName ?: ""
            binding.tvCloud.text = "Şifreli Yedekleme ($providerName)"
            val subtitle = if (status.lastBackupTimestamp > 0) {
                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("tr"))
                "Son yedek: ${sdf.format(Date(status.lastBackupTimestamp))}"
            } else {
                "Bağlı — henüz yedeklenmedi"
            }
            binding.tvCloudSubtitle.text = subtitle
            binding.tvCloudSubtitle.setTextColor(0xFF00BCD4.toInt())
        } else {
            binding.tvCloud.text = "Şifreli Yedekleme"
            binding.tvCloudSubtitle.text = "Kimlik bilgilerinizi bulutta yedekleyin"
            binding.tvCloudSubtitle.setTextColor(
                ContextCompat.getColor(requireContext(), com.verifyblind.mobile.R.color.sv_on_surface_variant)
            )
        }
    }

    private fun showConnectedBackupOptions(status: com.verifyblind.mobile.backup.CloudBackupManager.BackupStatus) {
        val provider = CloudBackupManager.getProvider(status.providerName!!)
        val providerName = provider?.displayName ?: status.providerName ?: ""
        AlertDialog.Builder(requireContext())
            .setTitle(providerName)
            .setItems(arrayOf("Şimdi Eşitle", "Bağlantıyı Kes")) { _, which ->
                when (which) {
                    0 -> performSync()
                    1 -> showDisconnectConfirmation()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showCloudProviderDialog() {
        val providers = CloudBackupManager.getAllProviders()
        val names = providers.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Bulut Sağlayıcı Seçin")
            .setItems(names) { _, which ->
                val selected = providers[which]
                loginToProvider(selected)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun loginToProvider(provider: CloudProvider) {
        lifecycleScope.launch {
            try {
                // Returns true if login flow started successfully (Dropbox)
                // OR if login completed successfully (Google Drive)
                val success = provider.login(this@SettingsFragment)

                if (success) {
                    if (provider.id == "google_drive") {
                        if (provider.isLoggedIn()) {
                            CloudBackupManager.saveProviderChoice(requireContext(), provider.id)
                            Toast.makeText(context, "✅ Google Drive bağlantısı başarılı!", Toast.LENGTH_SHORT).show()
                            updateCloudBackupStatus()
                            startSyncLogic()
                        } else {
                            Toast.makeText(context, "❌ Giriş yapılamadı (Hesap seçilmedi veya hata).", Toast.LENGTH_SHORT).show()
                        }
                    } 
                    // Dropbox handles success in onResume
                } else {
                    val err = if (provider is GoogleDriveProvider) provider.lastError else null
                    (activity as? MainActivity)?.showMessage("Giriş Başarısız", "Bulut hesabına giriş yapılamadı. $err")
                }
            } catch (e: Exception) {
                (activity as? MainActivity)?.showMessage("Bağlantı Hatası", "Bulut servisine bağlanırken bir hata oluştu: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return

        // Check if Dropbox SDK returned a credential after OAuth
        val dropboxProvider = CloudBackupManager.getProvider("dropbox") as? DropboxProvider
        if (dropboxProvider != null && dropboxProvider.checkForAuthResult()) {
            CloudBackupManager.saveProviderChoice(requireContext(), "dropbox")
            Toast.makeText(context, "✅ Dropbox bağlantısı başarılı!", Toast.LENGTH_SHORT).show()
            updateCloudBackupStatus()
            startSyncLogic()
            return
        }

        updateCloudBackupStatus()
    }

    private fun performSync() {
        val status = CloudBackupManager.getStatus(requireContext())
        val provider = status.providerName?.let { CloudBackupManager.getProvider(it) }
        if (provider == null || !provider.isLoggedIn()) {
            Toast.makeText(context, "Önce bir bulut sağlayıcı seçin.", Toast.LENGTH_SHORT).show()
            return
        }

        // Biometric verify before manual sync
        BiometricHelper.authenticate(
            activity = requireActivity() as androidx.fragment.app.FragmentActivity,
            onSuccess = {
                startSyncLogic()
            },
            onError = { msg ->
                Toast.makeText(context, "Doğrulama başarısız: $msg", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun startSyncLogic() {
        binding.pbBackup.visibility = View.VISIBLE
        binding.ivCloudArrow.visibility = View.GONE

        lifecycleScope.launch {
            val result = com.verifyblind.mobile.backup.SyncManager.performSync(requireContext())

            withContext(Dispatchers.Main) {
                binding.pbBackup.visibility = View.GONE
                binding.ivCloudArrow.visibility = View.VISIBLE
                if (result.isSuccess) {
                    if (result.hasChanges) {
                        Toast.makeText(context, "✅ Eşitleme tamamlandı! (+${result.itemsAdded} -${result.itemsDeleted} ↑${result.itemsUploaded})", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "✅ Zaten güncel.", Toast.LENGTH_SHORT).show()
                    }
                    updateCloudBackupStatus()
                    (activity as? MainActivity)?.updateUiState()
                } else {
                    (activity as? MainActivity)?.showMessage("Eşitleme Hatası", "Yedekleme sırasında bir sorun oluştu: ${result.error}")
                }
            }
        }
    }

    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Bağlantıyı Kes")
            .setMessage("Bulut yedekleme bağlantısı kesilecektir. Buluttaki yedek dosyası silinmez.")
            .setPositiveButton("BAĞLANTIYI KES") { _, _ ->
                CloudBackupManager.disconnect(requireContext())
                Toast.makeText(context, "Bağlantı kesildi.", Toast.LENGTH_SHORT).show()
                updateCloudBackupStatus()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Wallet Reset ----------

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cüzdanı Sıfırla")
            .setMessage("UYARI: Bu işlem tüm verilerinizi, kimlik kayıtlarınızı ve kriptografik anahtarlarınızı kalıcı olarak silecektir. Uygulama ilk kurulum haline dönecektir.\n\nEmin misiniz?")
            .setPositiveButton("SIFIRLA") { _, _ ->
                 // Biometric verify before destructive reset
                 BiometricHelper.authenticate(
                     activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                     onSuccess = {
                         performFullReset()
                     },
                     onError = { msg ->
                         (activity as? MainActivity)?.showMessage("İşlem İptal Edildi", "Güvenlik doğrulaması başarısız olduğu için işlem yapılamadı: $msg")
                     }
                 )
            }
            .setNegativeButton("İptal", null)
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
            
            // D. Disconnect cloud providers
            CloudBackupManager.disconnect(context)

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
