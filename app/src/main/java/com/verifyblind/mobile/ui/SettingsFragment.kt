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
            val lang = resources.configuration.locales[0].language
            val locale = if (lang == "tr") "tr" else "en"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://verifyblind.com/$locale/gizlilik"))
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = KvkkBlockCardRequest(nonce = nonce, cardId = cardId, reason = "USER_REQUEST")
                val response = RetrofitClient.api.blockCard(req)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        if (response.isSuccessful) {
                            Toast.makeText(context, getString(R.string.block_card_blocked), Toast.LENGTH_LONG).show()
                        } else {
                            val msg = if (response.code() == 409) getString(R.string.block_card_already_blocked)
                                      else "${getString(R.string.block_card_error_prefix)}${response.code()}"
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
        val status = CloudBackupManager.getStatus(requireContext())

        if (status.isConnected) {
            val provider = CloudBackupManager.getProvider(status.providerName!!)
            val providerName = provider?.displayName ?: status.providerName ?: ""
            binding.tvCloud.text = "${getString(R.string.settings_backup_title)} ($providerName)"
            val subtitle = if (status.lastBackupTimestamp > 0) {
                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                "${getString(R.string.backup_last_prefix)}${sdf.format(Date(status.lastBackupTimestamp))}"
            } else {
                getString(R.string.backup_not_yet)
            }
            binding.tvCloudSubtitle.text = subtitle
            binding.tvCloudSubtitle.setTextColor(0xFF00BCD4.toInt())
        } else {
            binding.tvCloud.text = getString(R.string.settings_backup_title)
            binding.tvCloudSubtitle.text = getString(R.string.settings_backup_desc)
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
            .setItems(arrayOf(getString(R.string.backup_sync_now), getString(R.string.backup_disconnect))) { _, which ->
                when (which) {
                    0 -> performSync()
                    1 -> showDisconnectConfirmation()
                }
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showCloudProviderDialog() {
        val providers = CloudBackupManager.getAllProviders()
        val names = providers.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.backup_provider_title))
            .setItems(names) { _, which ->
                val selected = providers[which]
                loginToProvider(selected)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
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
                            Toast.makeText(context, getString(R.string.cloud_backup_success_gdrive), Toast.LENGTH_SHORT).show()
                            updateCloudBackupStatus()
                            startSyncLogic()
                        } else {
                            Toast.makeText(context, getString(R.string.cloud_backup_fail_gdrive), Toast.LENGTH_SHORT).show()
                        }
                    } 
                    // Dropbox handles success in onResume
                } else {
                    val err = if (provider is GoogleDriveProvider) provider.lastError else null
                    (activity as? MainActivity)?.showMessage(getString(R.string.cloud_connect_failed_title), "${getString(R.string.cloud_login_failed_message)}$err")
                }
            } catch (e: Exception) {
                (activity as? MainActivity)?.showMessage(getString(R.string.cloud_connect_error_title), "${getString(R.string.cloud_connect_error_title)}: ${e.message}")
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
            Toast.makeText(context, getString(R.string.cloud_backup_success_dropbox), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, getString(R.string.sync_error_no_provider), Toast.LENGTH_SHORT).show()
            return
        }

        // Biometric verify before manual sync
        BiometricHelper.authenticate(
            activity = requireActivity() as androidx.fragment.app.FragmentActivity,
            onSuccess = {
                startSyncLogic()
            },
            onError = { msg ->
                Toast.makeText(context, "${getString(R.string.sync_auth_failed_prefix)}$msg", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, "${getString(R.string.sync_complete_changes)} (+${result.itemsAdded} -${result.itemsDeleted} ↑${result.itemsUploaded})", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, getString(R.string.sync_already_current), Toast.LENGTH_SHORT).show()
                    }
                    updateCloudBackupStatus()
                    (activity as? MainActivity)?.updateUiState()
                } else {
                    (activity as? MainActivity)?.showMessage(getString(R.string.sync_error_title), "${getString(R.string.sync_error_title)}: ${result.error}")
                }
            }
        }
    }

    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.disconnect_confirm_title))
            .setMessage(getString(R.string.disconnect_confirm_message))
            .setPositiveButton(getString(R.string.disconnect_confirm_button)) { _, _ ->
                CloudBackupManager.disconnect(requireContext())
                Toast.makeText(context, getString(R.string.disconnected_toast), Toast.LENGTH_SHORT).show()
                updateCloudBackupStatus()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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
