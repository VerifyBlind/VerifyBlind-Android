package com.verifyblind.mobile.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.verifyblind.mobile.MainActivity
import com.verifyblind.mobile.R
import com.verifyblind.mobile.backup.BackupFile
import com.verifyblind.mobile.backup.BackupManager
import com.verifyblind.mobile.backup.BackupNaming
import com.verifyblind.mobile.backup.BackupPasswordException
import com.verifyblind.mobile.backup.BackupRecord
import com.verifyblind.mobile.backup.CloudProvider
import com.verifyblind.mobile.backup.DropboxProvider
import com.verifyblind.mobile.backup.GoogleDriveProvider
import com.verifyblind.mobile.data.AppDatabase
import com.verifyblind.mobile.data.HistoryRepository
import com.verifyblind.mobile.databinding.FragmentBackupBinding
import com.verifyblind.mobile.util.BiometricHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manuel Yedekle / Geri Yükle / Tümünü Sil ekranı — sürekli senkronun yerini alan model.
 * Yedek = tek `.vfbackup` snapshot dosyası; tüm dosya
 * AES-256-GCM (parola → yerel PBKDF2). Konum: Google Drive / Dropbox / cihaz dosyası.
 *
 * OAuth: Google Drive `register()` ActivityResult sözleşmesini onCreate'te kaydeder; Dropbox
 * uygulamadan geri dönüşte sonucu onResume'da bırakır → bekleyen eylem orada sürdürülür.
 */
class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: HistoryRepository
    private lateinit var dropbox: DropboxProvider
    private lateinit var drive: GoogleDriveProvider

    private var busy = false
    /** Dropbox OAuth uygulamadan döndükten sonra sürdürülecek eylem. */
    private var pendingCloudAction: (() -> Unit)? = null
    /** SAF "dosya oluştur" sonucu bu içeriği yazacak. */
    private var pendingSaveJson: String? = null

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val json = pendingSaveJson
        pendingSaveJson = null
        if (uri == null || json == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (e: Exception) { false }
            }
            if (ok) showFileSavedInfo(uri) else toast(getString(R.string.backup_save_failed))
        }
    }

    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) { null }
            }
            if (json == null) toast(getString(R.string.backup_read_failed)) else importJson(json)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext().applicationContext
        drive = GoogleDriveProvider(ctx)
        drive.register(this) // ActivityResult kaydı ZORUNLU olarak onCreate'te
        dropbox = DropboxProvider(ctx)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = HistoryRepository(AppDatabase.getDatabase(requireContext()).historyDao())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnBackupAction.setOnClickListener { if (!busy) chooseExportLocation() }
        binding.btnRestoreAction.setOnClickListener { if (!busy) chooseRestoreLocation() }
        binding.btnDeleteAllAction.setOnClickListener { if (!busy) confirmDeleteAll() }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        // Dropbox OAuth uygulamadan döndüyse bekleyen eylemi sürdür.
        if (dropbox.checkForAuthResult()) {
            val action = pendingCloudAction
            pendingCloudAction = null
            action?.invoke()
        }
    }

    // ─────────────────────── Yedekle ───────────────────────

    private fun chooseExportLocation() {
        val options = arrayOf(
            getString(R.string.backup_loc_gdrive),
            getString(R.string.backup_loc_dropbox),
            getString(R.string.backup_loc_file)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.backup_export_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptEncryptionThen { json -> uploadToCloud(drive, json) }
                    1 -> promptEncryptionThen { json -> uploadToCloud(dropbox, json) }
                    2 -> promptEncryptionThen { json -> saveToFile(json) }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Şifreleme seçimi (varsayılan AÇIK) + parola. Biyometrik onay sonrası kayıtları toplar,
     * `.vfbackup` içeriğini üretir ve [onReady]'e verir.
     */
    private fun promptEncryptionThen(onReady: (String) -> Unit) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val encryptCheck = CheckBox(ctx).apply {
            text = getString(R.string.backup_encrypt_checkbox)
            isChecked = true // varsayılan şifreli
        }
        val passwordEdit = EditText(ctx).apply {
            hint = getString(R.string.backup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val warning = TextView(ctx).apply {
            setTextColor(0xFFD32F2F.toInt()) // kırmızı
            text = getString(R.string.backup_password_warning)
        }
        val plainWarning = TextView(ctx).apply {
            setTextColor(0xFFD32F2F.toInt())
            text = getString(R.string.backup_plaintext_warning)
            visibility = View.GONE
        }
        encryptCheck.setOnCheckedChangeListener { _, checked ->
            passwordEdit.visibility = if (checked) View.VISIBLE else View.GONE
            warning.visibility = if (checked) View.VISIBLE else View.GONE
            plainWarning.visibility = if (checked) View.GONE else View.VISIBLE
        }
        container.addView(encryptCheck)
        container.addView(passwordEdit)
        container.addView(warning)
        container.addView(plainWarning)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.backup_export_title))
            .setView(container)
            .setPositiveButton(getString(R.string.backup_do_export), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val encrypt = encryptCheck.isChecked
                val password = passwordEdit.text?.toString().orEmpty()
                if (encrypt && password.length < 6) {
                    passwordEdit.error = getString(R.string.backup_password_too_short)
                    return@setOnClickListener
                }
                dialog.dismiss()
                // Yedekleme geçmişi dışa aktarır → sahibi biyometrik doğrulasın (manuel eşitleme paritesi).
                BiometricHelper.authenticate(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    onSuccess = { buildBackupJson(if (encrypt) password else null, onReady) },
                    onError = { }
                )
            }
        }
        dialog.show()
    }

    private fun buildBackupJson(password: String?, onReady: (String) -> Unit) {
        setBusy(true)
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                val records: List<BackupRecord> = BackupManager.collectRecords(repository)
                BackupFile.write(records, password)
            }
            setBusy(false)
            onReady(json)
        }
    }

    private fun saveToFile(json: String) {
        pendingSaveJson = json
        createDocLauncher.launch(BackupNaming.defaultFileName())
    }

    private fun showFileSavedInfo(uri: android.net.Uri) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(ctx).apply { text = getString(R.string.backup_saved_info) }
        val shareCheck = CheckBox(ctx).apply { text = getString(R.string.backup_share_checkbox) }
        container.addView(info)
        container.addView(shareCheck)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.backup_saved_title))
            .setView(container)
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                if (shareCheck.isChecked) shareFile(uri)
            }
            .show()
    }

    private fun shareFile(uri: android.net.Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.backup_share_chooser)))
    }

    private fun uploadToCloud(provider: CloudProvider, json: String) {
        withCloud(provider) {
            setBusy(true)
            val result = provider.upload(BackupNaming.defaultFileName(), json)
            setBusy(false)
            if (result.isSuccess) toast(getString(R.string.backup_upload_success))
            else toast(getString(R.string.backup_upload_failed))
        }
    }

    // ─────────────────────── Geri Yükle ───────────────────────

    private fun chooseRestoreLocation() {
        val options = arrayOf(
            getString(R.string.backup_loc_gdrive),
            getString(R.string.backup_loc_dropbox),
            getString(R.string.restore_loc_device)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.backup_restore_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> listCloudBackups(drive)
                    1 -> listCloudBackups(dropbox)
                    2 -> openDocLauncher.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun listCloudBackups(provider: CloudProvider) {
        withCloud(provider) {
            setBusy(true)
            val result = provider.list(BackupNaming.EXTENSION)
            setBusy(false)
            val files = result.getOrNull()
            if (files.isNullOrEmpty()) {
                toast(getString(R.string.restore_no_files))
                return@withCloud
            }
            val labels = files.map { it.name }.toTypedArray()
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.restore_pick_file))
                    .setItems(labels) { _, idx ->
                        downloadAndImport(provider, files[idx].name)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    private fun downloadAndImport(provider: CloudProvider, filename: String) {
        setBusy(true)
        lifecycleScope.launch {
            val result = provider.download(filename)
            setBusy(false)
            val json = result.getOrNull()
            if (json == null) toast(getString(R.string.backup_read_failed)) else importJson(json)
        }
    }

    /** İçe aktarır. Şifreliyse parola sorar; yanlış parolada tekrar dener. */
    private fun importJson(json: String) {
        val encrypted = try {
            BackupFile.inspect(json).encrypted
        } catch (e: Exception) {
            toast(getString(R.string.backup_invalid_file)); return
        }
        if (encrypted) promptPasswordThenImport(json) else runImport(json, null)
    }

    private fun promptPasswordThenImport(json: String) {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            hint = getString(R.string.backup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.restore_password_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                runImport(json, edit.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun runImport(json: String, password: String?) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val records = withContext(Dispatchers.IO) { BackupFile.read(json, password) }
                val res = withContext(Dispatchers.IO) { BackupManager.importRecords(repository, records) }
                setBusy(false)
                toast(getString(R.string.restore_result, res.added, res.skipped))
            } catch (e: BackupPasswordException) {
                setBusy(false)
                toast(getString(R.string.restore_wrong_password))
                promptPasswordThenImport(json)
            } catch (e: Exception) {
                setBusy(false)
                toast(getString(R.string.backup_invalid_file))
            }
        }
    }

    // ─────────────────────── Tümünü Sil ───────────────────────

    private fun confirmDeleteAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_all_title))
            .setMessage(getString(R.string.delete_all_message))
            .setPositiveButton(getString(R.string.btn_delete_confirm)) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAll()
                    toast(getString(R.string.delete_all_done))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ─────────────────────── Yardımcılar ───────────────────────

    /**
     * Sağlayıcı girişini garanti edip [action]'ı çalıştırır. Google Drive girişi coroutine içinde
     * beklenir; Dropbox uygulamadan ayrıldığı için eylem `pendingCloudAction`'a konur ve onResume'da
     * sürdürülür.
     */
    private fun withCloud(provider: CloudProvider, action: suspend () -> Unit) {
        if (provider.isLoggedIn()) {
            lifecycleScope.launch { action() }
            return
        }
        if (provider.id == "dropbox") {
            pendingCloudAction = { lifecycleScope.launch { action() } }
            lifecycleScope.launch { provider.login(this@BackupFragment) }
        } else {
            lifecycleScope.launch {
                if (provider.login(this@BackupFragment)) action()
                else toast(getString(R.string.cloud_login_failed_message))
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        if (_binding == null) return
        binding.pbBackup.visibility = if (b) View.VISIBLE else View.GONE
        binding.btnBackupAction.isEnabled = !b
        binding.btnRestoreAction.isEnabled = !b
        binding.btnDeleteAllAction.isEnabled = !b
    }

    private fun toast(msg: String) {
        if (_binding == null) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
