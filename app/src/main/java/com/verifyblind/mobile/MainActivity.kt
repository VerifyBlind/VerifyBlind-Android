package com.verifyblind.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.verifyblind.mobile.api.*
import com.verifyblind.mobile.camera.CameraManager
import com.verifyblind.mobile.crypto.CryptoUtils
import com.verifyblind.mobile.databinding.ActivityMainBinding
import com.verifyblind.mobile.nfc.DocumentSupport
import com.verifyblind.mobile.nfc.PassportReader
import com.verifyblind.mobile.ui.BiometricConsentBottomSheet
import com.verifyblind.mobile.ui.ConsentBottomSheet
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.BiometricHelper
import com.verifyblind.mobile.viewmodel.MainViewModel
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.verifyblind.mobile.fcm.VBMessagingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MainActivity — Yalnızca UI binding, lifecycle ve navigation.
 *
 * İş mantığı → MainViewModel
 * Kamera yönetimi → CameraManager
 * Consent dialog → ConsentDialogBuilder
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var cameraManager: CameraManager
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var navController: NavController
    private lateinit var historyRepository: com.verifyblind.mobile.data.HistoryRepository
    private lateinit var cameraExecutor: ExecutorService

    // Biometric auth UI state (kept in Activity — pure UI concern)
    private var isAuthenticated = false

    private var nfcRetryCount = 0

    // Current card-add stepper step (1=Hazırlık, 2=MRZ, 3=NFC, 4=Yüz)
    private var currentAddCardStep = 0

    /// Kullanıcının kart ekleme akışında bulunduğu adım — hata olursa geri bildirim konusunu
    /// ön-doldurmak için (iOS RegisterViewModel.failedAtStep karşılığı).
    private var failedFlowStep: com.verifyblind.mobile.util.FlowFeedbackPrompt.FlowStep? = null

    /// Bu kart ekleme denemesinde kullanıcı bir HATA gördü mü? Geri bildirim yalnız hata sonrası
    /// teklif edilir — telefonu çaldığı için çıkana destek kutusu göstermek gereksiz gürültüdür.
    /// iOS'ta tüm hatalar tek `fail()` yolundan `.failed` ekranına düştüğü için orada karşılığı
    /// doğrudan o ekrandır; Android'de hatalar dağınık olduğundan bayrakla izlenir.
    private var hadErrorInFlow = false

    // NFC pulse animators
    private var nfcPulseAnimSet: android.animation.AnimatorSet? = null

    // NFC progress animation job (20→90 over ~10s while reading)
    private var nfcProgressJob: kotlinx.coroutines.Job? = null

    // Sessiz tekrar bekçisi: okuma koptuktan sonra etiket yeniden bulunmazsa (kart kaldırıldı,
    // alan geri gelmedi) kullanıcı "kart aranıyor" ekranında asılı kalmasın diye hatayı açar.
    private var nfcRetryWatchdogJob: kotlinx.coroutines.Job? = null

    // Demo mode: auto-inject job for MRZ screen (2s delay)
    private var demoMrzJob: kotlinx.coroutines.Job? = null

    // Demo mode: Hazırlık ekranında KVKK onayını otomatik işaretleyip 3sn sonra "Başla"ya basar.
    private var demoConsentJob: kotlinx.coroutines.Job? = null

    private val livenessLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.userSelfiePath = result.data?.getStringExtra("user_selfie")
            viewModel.antiSpoofCropPath = result.data?.getStringExtra("antispoof_crop")
            updateStepperState(4)
            com.verifyblind.mobile.util.FlowTelemetry.reached(com.verifyblind.mobile.util.FlowTelemetry.STEP_LIVENESS, viewModel.handshakeNonce)

            if (viewModel.isDemoMode) {
                showProcessingScreen(getString(R.string.creating_identity))
                lifecycleScope.launch(Dispatchers.IO) {
                    viewModel.completeDemoRegistration(this@MainActivity)
                }
            } else if (viewModel.pendingPassportData != null) {
                showProcessingScreen(getString(R.string.creating_identity))
                lifecycleScope.launch(Dispatchers.IO) {
                    viewModel.finalizeRegistration(
                        this@MainActivity,
                        viewModel.pendingPassportData!!
                    ) { status ->
                        withContext(Dispatchers.Main) { binding.tvProcessingTitle.text = status }
                    }
                }
            } else {
                toast(getString(R.string.err_passport_data_lost))
            }
        } else {
            binding.tvStatus.text = getString(R.string.flow_cancelled)
            viewModel.isNfcOperationActive = false
            updateUiState()
        }
    }

    // ──────────────────────── Lifecycle ────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { android.util.Log.d("FCM_TOKEN", "token: $it") }
                .addOnFailureListener { AppLog.warning("FCM token alınamadı: ${it.javaClass.simpleName} - ${it.message}", "FCM_TOKEN", it) }
        } catch (e: Exception) {
            AppLog.warning("Firebase başlatılamadı: ${e.javaClass.simpleName} - ${e.message}", "FCM_TOKEN", e)
        }

        // Edge-to-edge drawing (decorFitsSystemWindows=false) enabled via enableEdgeToEdge() above.
        applyGlobalSystemBarInsets()

        // Light status bar + nav bar: dark icons on light background (wallet screen)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        // Bildirim kanalı (Android 8+). İzin BURADA istenmez — wallet'taki soft-ask banner
        // tetikler (bağlamlı izin = yüksek kabul, kaza tap yok). FCM token zaten
        // performHandshake'te koşulsuz gönderilir; izin yalnızca bildirimin GÖSTERİLMESİNİ
        // kontrol eder, token kaydını değil.
        setupNotifications()

        // ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Camera
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraManager = CameraManager(this, binding, cameraExecutor)

        // Navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_nav_host) as? NavHostFragment
        if (navHostFragment != null) {
            navController = navHostFragment.navController
            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateVisibility(destination.id)
            }
        } else {
            AppLog.error("onCreate: NavHostFragment bulunamadı", "VerifyBlind")
        }

        // Initial state
        binding.viewFlipper.visibility = android.view.View.GONE
        binding.mainNavHost.visibility = android.view.View.VISIBLE

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupListeners()

        // Init History
        val db = com.verifyblind.mobile.data.AppDatabase.getDatabase(this)
        historyRepository = com.verifyblind.mobile.data.HistoryRepository(db.historyDao())

        // Init Partner Manager
        com.verifyblind.mobile.data.PartnerManager.init(this)

        // UI Listener for Unlock Button
        binding.btnUnlock.setOnClickListener { checkBiometricLogin() }

        // DEBUG: Check Wallet State on Startup
        val prefs = getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        Log.i("VerifyBlind_Debug", "MainActivity onCreate: Wallet State [HasTicket=${prefs.contains("ticket")}, HasKey=${prefs.contains("userPubKey")}]")

        // Background Handshake
        lifecycleScope.launch {
            viewModel.performHandshake(this@MainActivity)
        }

        // Observe ViewModel events
        observeViewModel()

        // Handle possible Deep Link intent
        handleIntent(intent)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Sonuç ne olursa olsun wallet'a haber ver → soft-ask banner tazelenir
        // (izin verildiyse veya prompt gösterildiyse gizlenir).
        supportFragmentManager.setFragmentResult("wallet_update", Bundle.EMPTY)
    }

    /** Bildirim kanalını oluşturur — UI göstermez, onCreate'te güvenle çağrılabilir. */
    private fun setupNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VBMessagingService.CHANNEL_ID,
                getString(R.string.fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.fcm_channel_desc) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Wallet'taki soft-ask banner "İzin Ver"e basınca çağrılır → sistem POST_NOTIFICATIONS
     * prompt'unu tetikler (Android 13+). Snooze/"prompt gösterildi" durumu WalletFragment'ta
     * tutulur. Android <13'te runtime izin yoktur; banner zaten gösterilmez.
     */
    fun launchNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAppUpdate()
        checkBiometricLogin()
        // NFC foreground dispatch SADECE kart-ekle akışının NFC okutma ekranı açıkken etkin.
        // Aksi halde cüzdan ana ekranında karta dokunmak hiçbir şey yapmamalı (kazara
        // liveness/kayıt başlatmamalı). Ekrana arka plandan dönüldüğünde durumu yansıtır.
        if (isNfcScanScreenActive) {
            enableNfcForegroundDispatch()
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onStop() {
        super.onStop()
        if (!viewModel.isNfcOperationActive && !viewModel.isCryptoOperationActive) {
            isAuthenticated = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onBackPressed() {
        val isFlipperVisible = binding.viewFlipper.visibility == android.view.View.VISIBLE
        if (isFlipperVisible && currentAddCardStep > 0) {
            binding.btnStepperBack.performClick()
        } else if (isFlipperVisible) {
            // Login/QR/işlem ekranından geri: kamerayı durdur ve akışı sonlandır — aktif login nonce'u
            // iptal edilir (partner "lütfen bekleyiniz"de kalmasın), deeplink'se partnere geri dönülür.
            cameraManager.stopCamera()
            finishDeepLinkFlowOrUpdateUi()
        } else {
            super.onBackPressed()
        }
    }

    // ──────────────────────── ViewModel Observer ────────────────────────

    private fun observeViewModel() {
        viewModel.uiEvent.observe(this) { event ->
            if (event == null) return@observe
            viewModel.onEventConsumed()

            when (event) {
                is MainViewModel.UiEvent.Toast -> toast(event.message)

                is MainViewModel.UiEvent.ShowMessage -> showMessage(event.title, event.message)

                is MainViewModel.UiEvent.ShowDeviceLockRequired -> showMessage(
                    event.title,
                    event.message,
                    actionLabel = getString(R.string.btn_open_lock_settings),
                    onAction = { BiometricHelper.openDeviceLockSettings(this@MainActivity) }
                )

                is MainViewModel.UiEvent.ShowMessageAndFinish -> {
                    showMessage(event.title, event.message) { finishDeepLinkFlowOrUpdateUi(event.isDeepLink) }
                }

                is MainViewModel.UiEvent.CriticalError -> {
                    showMessage(event.title, event.message) { finishAffinity() }
                }

                is MainViewModel.UiEvent.ForceUpdate -> showForceUpdateDialog(event.storeUrl)

                // Hukuki metinler güncellendi: kapı yeniden gösterilir. Ekran kabul alınmadan
                // kapanmaz; kullanıcı reddederse uygulamadan çıkar.
                is MainViewModel.UiEvent.LegalTermsUpdated -> {
                    startActivity(LegalTermsActivity.intent(this, event.requiredVersion, isUpdate = true))
                    finish()
                }

                is MainViewModel.UiEvent.ShowConsentDialog -> {
                    updateUiState()
                    val sheet = ConsentBottomSheet().apply {
                        info = event.info
                        logo = event.logo
                        onApprove = {
                            showProcessingScreen(getString(R.string.processing), qrMode = true)
                            lifecycleScope.launch(Dispatchers.IO) {
                                viewModel.performLoginWithQr(
                                    this@MainActivity,
                                    event.nonce,
                                    event.pkHash,
                                    event.info.name,
                                    event.fromDeepLink,
                                    historyRepository,
                                    event.info.partnerId,
                                    event.info.scopes
                                )
                            }
                        }
                        onReject = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                viewModel.cancelQrNonce(event.nonce)
                            }
                            finishDeepLinkFlowOrUpdateUi(event.fromDeepLink)
                        }
                    }
                    sheet.show(supportFragmentManager, ConsentBottomSheet.TAG)
                }

                is MainViewModel.UiEvent.RequestBiometricDecrypt -> {
                    handleBiometricDecrypt(event)
                }

                is MainViewModel.UiEvent.UpdateProcessingStatus -> {
                    binding.tvProcessingTitle.text = event.status
                }

                is MainViewModel.UiEvent.ConfigLoaded -> {
                    supportFragmentManager.setFragmentResult("wallet_update", Bundle())
                }

                is MainViewModel.UiEvent.RegistrationSuccess -> {
                    isAuthenticated = true
                    // Kart ekleme tamamlandı — KVKK onayını sıfırla ki bir sonraki sefere
                    // (kart-ekleme ve partner onay ekranlarında) yeniden onay istensin.
                    getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("kvkk_consent_accepted", false).apply()
                    binding.viewFlipper.displayedChild = 6
                    binding.viewFlipper.visibility = android.view.View.VISIBLE
                    binding.mainNavHost.visibility = android.view.View.GONE
                    // Success screen is full-screen (iOS parity) — hide stepper
                    binding.layoutStepperHeader.visibility = android.view.View.GONE
                    binding.layoutStepperRow.visibility = android.view.View.GONE
                }

                is MainViewModel.UiEvent.RegistrationFailed -> {
                    hadErrorInFlow = true
                    binding.tvStatus.text = getString(R.string.registration_failed_status)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.registration_rejected_title))
                        .setMessage(event.error)
                        .setPositiveButton(getString(R.string.common_ok)) { _, _ -> offerFeedbackThenFinish() }
                        .setOnCancelListener { offerFeedbackThenFinish() }
                        .show()
                }

                is MainViewModel.UiEvent.LoginSuccess -> {
                    binding.tvStatus.text = getString(R.string.login_success_status)
                    // Başarı: ayrı "Başarılı" ekranı yok — toast + (deeplink'te) partner uygulamasına geri dön/kapat.
                    finishDeepLinkFlowOrUpdateUi(event.fromDeepLink, "success")
                    toast(getString(R.string.identity_verified))
                }

                is MainViewModel.UiEvent.LoginKeystoreError -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.security_error_title))
                        .setMessage(getString(R.string.keystore_error_message))
                        .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                            com.verifyblind.mobile.util.SecureStore.clear(this)
                            getSharedPreferences("VerifyBlind_Prefs", MODE_PRIVATE).edit().clear().apply()
                            viewModel.clearTicket()
                            try { CryptoUtils.deleteKey() } catch (ex: Exception) {}
                            toast(getString(R.string.card_data_cleared))
                            finishDeepLinkFlowOrUpdateUi(event.fromDeepLink)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> finishDeepLinkFlowOrUpdateUi(event.fromDeepLink) }
                        .setCancelable(false)
                        .show()
                }

                is MainViewModel.UiEvent.TicketRevoked -> {
                    // Ticket sunucu tarafında iptal edildi; viewModel yerel kaydı zaten temizledi.
                    // Kullanıcıya bilgi ver ve kayıtsız (kimlik ekleme) durumuna dön.
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ticket_revoked_title))
                        .setMessage(event.message)
                        .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                            toast(getString(R.string.card_data_cleared))
                            finishDeepLinkFlowOrUpdateUi(event.fromDeepLink)
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    // ──────────────────────── Biometric Decrypt Bridge ────────────────────────

    private fun handleBiometricDecrypt(event: MainViewModel.UiEvent.RequestBiometricDecrypt) {
        binding.tvStatus.text = getString(R.string.authenticating)

        lifecycleScope.launch {
            try {
                when (event.flow) {
                    "register" -> {
                        // Time-bound user key (V5): TEK biyometrik prompt → pencere içinde ticket decrypt.
                        val aesKeyDec = authenticateAndUseUserKey {
                            CryptoUtils.rsaDecryptWithCipher(CryptoUtils.getCipherForDecrypt(), event.cipherText)
                        }
                        withContext(Dispatchers.IO) {
                            viewModel.completeRegistration(
                                this@MainActivity,
                                aesKeyDec,
                                event.hybridObj,
                                historyRepository
                            )
                        }
                    }
                    "login" -> {
                        val loginCtx = event.loginContext!!
                        // Holder-of-key (Y-4): TEK biyometrik prompt → aynı time-bound pencerede HEM ticket
                        // decrypt HEM login mesajının imzası. user key (V5) time-bound olduğundan ikinci
                        // prompt YOK (iOS tek-LAContext akışının eşi).
                        val sigTs = System.currentTimeMillis() / 1000
                        val hokMessage = "VBLOK1|${loginCtx.nonce}|${loginCtx.pkHash ?: ""}|$sigTs"
                        val (aesKeyDec, userSig) = authenticateAndUseUserKey {
                            val key = CryptoUtils.rsaDecryptWithCipher(CryptoUtils.getCipherForDecrypt(), event.cipherText)
                            val sig = CryptoUtils.signWithSignature(CryptoUtils.getSignatureForSign(), hokMessage)
                            Pair(key, sig)
                        }
                        withContext(Dispatchers.IO) {
                            viewModel.completeLogin(
                                this@MainActivity,
                                aesKeyDec,
                                event.hybridObj,
                                loginCtx,
                                historyRepository,
                                userSig,
                                sigTs
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                val fromDeepLink = event.loginContext?.fromDeepLink ?: false
                val cancelNonce = event.loginContext?.nonce
                if (cancelNonce != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        viewModel.cancelQrNonce(cancelNonce)
                    }
                }
                toast(getString(R.string.operation_cancelled))
                finishDeepLinkFlowOrUpdateUi(fromDeepLink)
            } catch (e: BiometricHelper.BiometricAuthException) {
                // Prompt açıkken kullanıcı/çevre kaynaklı hata. Sentry seviyesi hata KODUNA göre
                // (bkz. BiometricHelper.classify); iptal/zaman aşımı buraya hiç gelmez, onCancel'a gider.
                // Kullanıcıya sistemin İngilizce `errString`'i DEĞİL, yerelleştirilmiş metin gösterilir.
                android.util.Log.w("VerifyBlind", "Biyometrik hata: ${e.systemMessage}")
                if (e.errorClass == BiometricHelper.ErrorClass.RECOVERABLE) {
                    AppLog.warning("Biyometrik doğrulama tamamlanamadı", "VerifyBlind", e)
                } else {
                    AppLog.error("Biyometrik doğrulama beklenmeyen hata", "VerifyBlind", e)
                }
                if (event.flow == "login") {
                    viewModel.handleLoginKeystoreError(
                        this@MainActivity,
                        event.loginContext?.fromDeepLink ?: false
                    )
                } else {
                    showMessage(
                        getString(R.string.biometric_error_title),
                        getString(BiometricHelper.userMessageRes(e.errorCode))
                    )
                }
            } catch (e: Exception) {
                AppLog.error("Biyometrik/Keystore Hatası: ${e.message}", "VerifyBlind", e)
                if (event.flow == "login") {
                    viewModel.handleLoginKeystoreError(
                        this@MainActivity,
                        event.loginContext?.fromDeepLink ?: false
                    )
                } else {
                    showMessage(getString(R.string.registration_error_title), e.message ?: "unknown")
                }
            }
        }
    }

    // Holder-of-key (Y-4): TEK biyometrik auth (CryptoObject YOK). Başarıda, user key (V5) time-bound
    // olduğundan auth penceresi içinde verilen kripto bloğu (decrypt ve/veya sign) çalıştırılır → tek
    // prompt. CryptoObject kullanılsaydı auth-per-use semantiği gelir, ikinci işlem ikinci prompt isterdi.
    private suspend fun <T> authenticateAndUseUserKey(crypto: () -> T): T = suspendCancellableCoroutine { cont ->
        try {
            BiometricHelper.authenticateForKeyUse(this@MainActivity,
                onSuccess = {
                    try {
                        if (cont.isActive) cont.resume(crypto())
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                },
                onCancel = {
                    if (cont.isActive) cont.cancel()
                },
                onError = { code, systemMessage ->
                    if (cont.isActive) {
                        cont.resumeWithException(BiometricHelper.BiometricAuthException(code, systemMessage))
                    }
                }
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    // ──────────────────────── System Bar Insets ────────────────────────

    /**
     * Global edge-to-edge inset handling for all screens.
     * - main_nav_host: top+bottom padding so fragments stay within safe area
     * - tvStatus: top padding so status text clears the status bar
     * - layout_app_lock: top+bottom padding for the biometric lock overlay
     */
    private fun applyGlobalSystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Fragments handle their own insets via android:fitsSystemWindows="true"
            binding.tvStatus.updatePadding(top = bars.top)
            binding.layoutAppLock.updatePadding(top = bars.top, bottom = bars.bottom)
            binding.layoutStepperHeader.updatePadding(top = bars.top)
            binding.viewFlipper.updatePadding(bottom = bars.bottom)

            insets
        }
    }

    // ──────────────────────── UI Listeners ────────────────────────

    private fun setupListeners() {
        binding.btnAddId.setOnClickListener {
            if (viewModel.isHandshakeFailed) showHandshakeErrorWarning { startAddCardFlow() }
            else startAddCardFlow()
        }
        binding.btnScanQr.setOnClickListener { startScanFlow() }
        binding.btnDeleteCard.setOnClickListener { deleteTicket() }
        binding.btnCloseCamera.setOnClickListener { cameraManager.stopCamera(); finishDeepLinkFlowOrUpdateUi() }
        binding.btnZoom20.setOnClickListener { cameraManager.setZoom(2.0f) }
        binding.tvHowItWorks.setOnClickListener { showHowItWorksDialog() }
        binding.btnCloseNfc.setOnClickListener { updateUiState() }
        // "Yeniden Dene" (3 sessiz deneme sonrası hata ekranı) — iOS RegisterViewModel.retryNfc paritesi:
        // sayaç sıfırlanır, ekran kart arama durumuna döner.
        binding.btnNfcRetry.setOnClickListener { showNfcScanningScreen() }

        // Stepper: "Başla" on Hazırlık screen → go to MRZ camera
        binding.btnStartCardAdd.setOnClickListener {
            // KVKK onayını kalıcı olarak kaydet
            getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("kvkk_consent_accepted", true).apply()
            updateStepperState(2)
            cameraManager.setCameraOverlay(isQr = false)
            checkCameraPermissionAndStart(isQr = false)
        }

        // Stepper: Back button during card-add flow
        binding.btnStepperBack.setOnClickListener {
            when (currentAddCardStep) {
                1 -> { // Hazırlık → wallet
                    cameraManager.stopCamera()
                    offerFeedbackThenFinish { updateUiState() }
                }
                2 -> { // MRZ → Hazırlık
                    cameraManager.stopCamera()
                    binding.viewFlipper.displayedChild = 5
                    updateStepperState(1)
                }
                3 -> { // NFC → MRZ
                    stopNfcPulseAnimation()
                    cameraManager.setCameraOverlay(isQr = false)
                    binding.viewFlipper.displayedChild = 2
                    updateStepperState(2)
                    checkCameraPermissionAndStart(isQr = false)
                }
                else -> offerFeedbackThenFinish { updateUiState() }
            }
        }

        // Success screen: "Ana Sayfaya Dön" — wallet'a döner; bildirim izni orada
        // soft-ask banner ile istenir (otomatik prompt yok).
        binding.btnGoHome.setOnClickListener {
            stopNfcPulseAnimation()
            updateUiState()
        }

        setupKvkkCardAddSection()
    }

    // ──────────────────────── NFC ────────────────────────

    /**
     * Kart-ekle akışının NFC okutma ekranı (Step 3) şu an görünür mü?
     * Yalnızca bu ekran açıkken NFC etiketleri işlenir; aksi halde dokunma yok sayılır.
     */
    private val isNfcScanScreenActive: Boolean
        get() = currentAddCardStep == 3 &&
            binding.viewFlipper.visibility == android.view.View.VISIBLE &&
            binding.viewFlipper.displayedChild == 3

    /** NFC etiketlerini ön plana yönlendirmeyi etkinleştirir. Activity resumed olmalı. */
    private fun enableNfcForegroundDispatch() {
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isNfcIntent = NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action
        // NFC etiketi YALNIZCA okutma ekranı (Step 3) açıkken işlenir. Ekran kapalıyken
        // (cüzdan ana ekranı vb.) etiket sessizce yok sayılır — böylece kazara liveness/kayıt
        // ya da MRZ alanları boşken "MRZ verisi yok" hatası tetiklenmez.
        if (isNfcIntent && isNfcScanScreenActive) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                // Etiket geri geldi: bekçiyi durdur, önceki denemeden kalan hata durumunu temizle.
                nfcRetryWatchdogJob?.cancel()
                nfcRetryWatchdogJob = null
                resetNfcErrorUi()
                if (viewModel.isHandshakeSuccessful) {
                    binding.tvNfcTitle.text = getString(R.string.nfc_connecting)
                    binding.tvNfcTitle.setTextColor(ContextCompat.getColor(this, R.color.sv_on_surface))
                    binding.pbNfc.progress = 20
                    viewModel.isNfcOperationActive = true
                    handleNfcTag(tag)
                } else {
                    // TTL süresi dolmuş veya hiç handshake yapılmamış
                    binding.tvNfcTitle.text = getString(R.string.nfc_reconnecting)
                    binding.tvNfcTitle.setTextColor(ContextCompat.getColor(this, R.color.sv_on_surface))
                    lifecycleScope.launch {
                        viewModel.ensureHandshake(this@MainActivity)
                        if (viewModel.isHandshakeSuccessful) {
                            handleNfcTag(tag)
                        } else {
                            toast(getString(R.string.server_connection_error))
                            binding.tvNfcTitle.text = getString(R.string.connection_error_title)
                        }
                    }
                }
            }
        }
        handleIntent(intent)
    }

    private fun handleNfcTag(tag: Tag) {
        val docNo = binding.etDocNo.text.toString()
        val dob = binding.etDob.text.toString()
        val doe = binding.etDoe.text.toString()

        if (docNo.isEmpty() || dob.isEmpty() || doe.isEmpty()) {
            toast(getString(R.string.mrz_fill_hint))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.tvNfcTitle.text = getString(R.string.nfc_reading)
                binding.pbNfc.progress = 20
                startNfcProgressAnimation()
            }

            try {
                val nonceBytes = viewModel.handshakeNonce?.toByteArray(Charsets.UTF_8) ?: ByteArray(8)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(nonceBytes)
                val challenge = hash.copyOf(8)

                val passportData = PassportReader.readPassport(tag, docNo, dob, doe, challenge)
                nfcRetryCount = 0
                viewModel.pendingPassportData = passportData

                // Hızlı-başarısızlık: desteklenmeyen belgeyi (ör. JPEG2000 DG2 ya da AA-desteksiz pasaport)
                // BURADA durdur. Aksi halde kullanıcı tüm liveness'i boşa yapıp en sonda enclave'in
                // ERR_ACTIVE_AUTH'una çarpıyor; üstelik fotoğraf çözülemediğinde yüz eşleştirme sessizce
                // atlanıyordu (güvenlik boşluğu). PII yok — yalnız verdict + ihraç eden ülke loglanır.
                val issuer = passportData.dg1.mrzInfo?.issuingState ?: ""
                val docCode = passportData.dg1.mrzInfo?.documentCode ?: ""
                val support = DocumentSupport.evaluate(
                    issuer, docCode,
                    passportData.faceImage, passportData.dg15Bytes, passportData.activeAuthSignature)
                if (support != DocumentSupport.Verdict.SUPPORTED) {
                    AppLog.warning("Desteklenmeyen belge: $support ihraçÜlke=$issuer belgeKodu=$docCode", "NFC")
                    val msg = when (support) {
                        DocumentSupport.Verdict.UNSUPPORTED_COUNTRY  -> getString(R.string.doc_unsupported_country)
                        DocumentSupport.Verdict.UNSUPPORTED_DOC_TYPE -> getString(R.string.doc_unsupported_doc_type)
                        DocumentSupport.Verdict.UNSUPPORTED_IMAGE    -> getString(R.string.doc_unsupported_image)
                        DocumentSupport.Verdict.NO_ACTIVE_AUTH       -> getString(R.string.doc_unsupported_no_aa)
                        else                                         -> getString(R.string.doc_unsupported_generic)
                    }
                    hadErrorInFlow = true
                    viewModel.pendingPassportData = null // güvenlik: desteklenmeyen veriyle akışa devam etme
                    withContext(Dispatchers.Main) {
                        stopNfcProgressAnimation()
                        stopNfcPulseAnimation()
                        binding.tvNfcTitle.text = getString(R.string.doc_unsupported_title)
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.doc_unsupported_title))
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok) { d, _ ->
                                d.dismiss()
                                offerFeedbackThenFinish()
                            }
                            .setCancelable(true)
                            .setOnCancelListener { offerFeedbackThenFinish() }
                            .show()
                    }
                    return@launch
                }
                com.verifyblind.mobile.util.FlowTelemetry.reached(com.verifyblind.mobile.util.FlowTelemetry.STEP_NFC, viewModel.handshakeNonce)

                withContext(Dispatchers.Main) {
                    stopNfcProgressAnimation()
                    binding.tvNfcTitle.text = getString(R.string.nfc_completed)
                    binding.pbNfc.progress = 100
                    stopNfcPulseAnimation()
                    // Change inner circle to green on success
                    binding.nfcCircleInner.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_success_circle)
                    if (!viewModel.livenessChallenges.isNullOrEmpty()) {
                        binding.tvStatus.text = getString(R.string.liveness_starting)

                        var chipPhotoPath = ""
                        val faceImg = viewModel.pendingPassportData?.faceImage
                        if (faceImg != null) {
                            try {
                                val chipFile = java.io.File(cacheDir, "chip_temp.jpg")
                                chipFile.writeBytes(faceImg)
                                chipPhotoPath = chipFile.absolutePath
                            } catch (e: Exception) {
                                // Sessizce yutulunca chip_photo_path boş kalıyor ve kayıt yüz
                                // eşleştirmesi YAPILMADAN ilerliyordu. Kapı artık LivenessActivity'de
                                // sert duruyor; burada da iz bırak ki sebep görünür olsun.
                                AppLog.error("Chip fotoğrafı diske yazılamadı — yüz eşleştirme yapılamayacak", "NFC", e)
                            }
                        }

                        val livenessIntent = Intent(this@MainActivity, LivenessActivity::class.java)
                        livenessIntent.putIntegerArrayListExtra("challenges", ArrayList(viewModel.livenessChallenges))
                        // Huni: canlılık adımını NEDEN kaybettiğimizi ekranın kendisi bildirir
                        // (hata anında, çıkışta değil — kullanıcı "Tekrar Dene" diyebiliyor).
                        livenessIntent.putExtra("flow_nonce", viewModel.handshakeNonce)
                        if (chipPhotoPath.isNotEmpty()) {
                            livenessIntent.putExtra("chip_photo_path", chipPhotoPath)
                        }
                        BiometricConsentBottomSheet().apply {
                            onApprove = { livenessLauncher.launch(livenessIntent) }
                            onReject = { updateUiState() }
                        }.show(supportFragmentManager, BiometricConsentBottomSheet.TAG)
                    } else {
                        showProcessingScreen(getString(R.string.creating_identity))
                        lifecycleScope.launch(Dispatchers.IO) {
                            viewModel.finalizeRegistration(
                                this@MainActivity,
                                passportData
                            ) { status ->
                                withContext(Dispatchers.Main) { binding.tvProcessingTitle.text = status }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopNfcProgressAnimation()
                    retryNfcOrShowError(e)
                }
            }
        }
    }


    // ──────────────────────── Deep Link ────────────────────────

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && uri.scheme == "https" && uri.host == "app.verifyblind.com" && uri.path?.startsWith("/request") == true) {
                viewModel.isDeepLinkFlow = true
                val nonce = uri.getQueryParameter("nonce")
                val pkHash = uri.getQueryParameter("pk_hash")

                if (!nonce.isNullOrEmpty()) {
                    // Item 3a: deeplink aktif ekranı (kamera/demo) preempt eder — durdur/resetle, login her zaman başlasın.
                    demoMrzJob?.cancel(); demoConsentJob?.cancel()
                    cameraManager.stopCamera(resetToHome = false)
                    // Önceki farklı bir login sürüyorsa nonce'unu iptal et (partner'ı bekletmede bırakma).
                    val prev = viewModel.activeLoginNonce
                    if (!prev.isNullOrEmpty() && prev != nonce) {
                        lifecycleScope.launch(Dispatchers.IO) { viewModel.cancelQrNonce(prev) }
                    }
                    viewModel.returnUrl = uri.getQueryParameter("return")
                    viewModel.activeLoginNonce = nonce
                    viewModel.partnerAppReturnScheme = null // fetchPartnerInfo dolduracak (kayıtlı şema)
                    showProcessingScreen(getString(R.string.please_wait), qrMode = true)
                    viewModel.fetchPartnerInfo(this, nonce, pkHash, fromDeepLink = true)
                } else {
                    toast(getString(R.string.invalid_request))
                    finishDeepLinkFlowOrUpdateUi()
                }
            }
        }
    }

    /**
     * Kayıt hatasından sonra "bir sorun mu yaşadınız?" teklifi — uygunsa. Yalnız HATA sonrası ve
     * kullanıcı başına haftada en fazla bir kez (bkz. FlowFeedbackPrompt). Uygun değilse akış
     * normal şekilde kapanır.
     */
    private fun offerFeedbackThenFinish(onDone: () -> Unit = { finishDeepLinkFlowOrUpdateUi() }) {
        val step = failedFlowStep
        if (!hadErrorInFlow || step == null ||
            !com.verifyblind.mobile.util.FlowFeedbackPrompt.shouldOffer(this)
        ) {
            onDone()
            return
        }
        // "Hayır" dense de sayaç işler — amaç sıklığı sınırlamak, cevabı kaydetmek değil.
        com.verifyblind.mobile.util.FlowFeedbackPrompt.markShown(this)
        val subject = com.verifyblind.mobile.util.FlowFeedbackPrompt.subject(this, step)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.feedback_prompt_title))
            .setMessage(getString(R.string.feedback_prompt_message))
            .setPositiveButton(getString(R.string.feedback_prompt_yes)) { _, _ ->
                onDone()
                if (::navController.isInitialized) {
                    navController.navigate(
                        R.id.nav_feedback, android.os.Bundle().apply { putString("subject", subject) })
                }
            }
            .setNegativeButton(getString(R.string.feedback_prompt_no)) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    // ──────────────────────── UI State ────────────────────────

    fun updateUiState() {
        viewModel.loadTicket()
        currentAddCardStep = 0
        stopNfcPulseAnimation()

        if (isAuthenticated) {
            binding.layoutAppLock.visibility = android.view.View.GONE
        }

        binding.mainNavHost.visibility = android.view.View.VISIBLE
        binding.viewFlipper.visibility = android.view.View.GONE
        binding.tvStatus.visibility = android.view.View.GONE
        binding.layoutStepperHeader.visibility = android.view.View.GONE
        binding.layoutStepperRow.visibility = android.view.View.GONE

        supportFragmentManager.setFragmentResult("wallet_update", Bundle())
    }

    private fun updateVisibility(destinationId: Int) {
        if (binding.layoutAppLock.visibility == android.view.View.VISIBLE) return
        binding.tvStatus.visibility = android.view.View.GONE
        binding.viewFlipper.visibility = android.view.View.GONE
        binding.mainNavHost.visibility = android.view.View.VISIBLE
    }

    // ──────────────────────── Scan Flows ────────────────────────

    fun startScanFlow() {
        when {
            viewModel.isHandshakeFailed -> showHandshakeErrorWarning { startLoginFlow() }
            viewModel.isLoginHandshakeSuccessful -> startLoginFlow()
            else -> {
                // Handshake henüz tamamlanmadı — ekran ortasında bekletici göster, tamamlanınca devam et
                showProcessingScreen(getString(R.string.please_wait), qrMode = true)
                lifecycleScope.launch {
                    viewModel.ensureLoginHandshake(this@MainActivity)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (viewModel.isLoginHandshakeSuccessful) {
                            startLoginFlow()
                        } else {
                            updateUiState()
                            showHandshakeErrorWarning { startLoginFlow() }
                        }
                    }
                }
            }
        }
    }

    fun startAddCardFlow() {
        if (!viewModel.isDemoMode) {
            val nfc = NfcAdapter.getDefaultAdapter(this)
            if (nfc == null) {
                showMessage(getString(R.string.nfc_not_found_title), getString(R.string.nfc_not_found_message)) { updateUiState() }
                return
            }
            if (!nfc.isEnabled) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.nfc_disabled_title))
                    .setMessage(getString(R.string.nfc_disabled_message))
                    .setPositiveButton(getString(R.string.btn_go_to_settings)) { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                        updateUiState()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> updateUiState() }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        // Show Hazırlık (Prepare) screen — Step 1
        binding.viewFlipper.displayedChild = 5
        binding.viewFlipper.visibility = android.view.View.VISIBLE
        binding.mainNavHost.visibility = android.view.View.GONE
        updateStepperState(1)
        com.verifyblind.mobile.util.FlowTelemetry.startFlow()   // yeni kart ekleme denemesi -> yeni huni gruplama anahtari
        hadErrorInFlow = false

        // Hazırlık KVKK onayı — demo'da otomatik işaretle + 3sn sonra "Başla"ya bas;
        // normal kart-ekleme akışında onay DAİMA elle alınır, asla ön-seçili gelmez.
        demoConsentJob?.cancel()
        if (viewModel.isDemoMode) {
            binding.cbKvkkConsentCardAdd.isChecked = true
            demoConsentJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                if (viewModel.isDemoMode && currentAddCardStep == 1) {
                    binding.btnStartCardAdd.performClick()
                }
            }
        } else {
            binding.cbKvkkConsentCardAdd.isChecked = false
        }

        // Demo mode'da handshake gerekmez; normal flow'da arka planda hazırla
        if (!viewModel.isDemoMode) {
            lifecycleScope.launch { viewModel.ensureHandshake(this@MainActivity) }
        }
    }

    fun startDemoAddCardFlow() {
        // Şifre yok: buton yalnızca cihaz sürümü admin tanımlı demo sürümüyle eşleşince görünür
        // (demoEnabled), dolayısıyla görünür olması zaten yetkilendirmedir.
        viewModel.isDemoMode = true
        startAddCardFlow()
    }

    private fun startLoginFlow() {
        // Elle QR tarama — deeplink/app-return durumunu temizle (bayat return URL sızmasın).
        viewModel.isDeepLinkFlow = false
        viewModel.returnUrl = null
        viewModel.partnerAppReturnScheme = null
        viewModel.activeLoginNonce = null

        binding.viewFlipper.displayedChild = 2
        binding.viewFlipper.visibility = android.view.View.VISIBLE
        binding.mainNavHost.visibility = android.view.View.GONE

        cameraManager.setCameraOverlay(isQr = true)
        checkCameraPermissionAndStart(isQr = true)

        // QR tarama başlarken login-handshake'i arka planda hazırla (sadece attestation)
        lifecycleScope.launch { viewModel.ensureLoginHandshake(this@MainActivity) }
    }

    // ──────────────────────── Camera Permission ────────────────────────

    private fun checkCameraPermissionAndStart(isQr: Boolean) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startCameraWithCallbacks(isQr)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), if (isQr) 1002 else 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Sadece kamera izni request'lerini handle et. ActivityResultLauncher API'sı da bu
        // callback'i tetikliyor; filtre olmayınca POST_NOTIFICATIONS gibi alakasız izinler
        // verildiğinde de buraya düşüp kamerayı yanlış modda başlatıyorduk.
        if (requestCode != 1001 && requestCode != 1002) return
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCameraWithCallbacks(requestCode == 1002)
        } else {
            toast(getString(R.string.camera_permission_required))
        }
    }

    private fun startCameraWithCallbacks(isQr: Boolean) {
        if (!isQr && currentAddCardStep in 1..2) {
            updateStepperState(2)
        }
        cameraManager.startCamera(
            isQr = isQr,
            onQrDetected = { qrData -> handleQrDetected(qrData) },
            onMrzDetected = { docNo, dob, expiry, documentType -> handleMrzDetected(docNo, dob, expiry, documentType) }
        )
        // Demo: kamera 2 saniye açık kalır, ardından sahte MRZ enjekte edilir
        if (!isQr && viewModel.isDemoMode) {
            demoMrzJob?.cancel()
            demoMrzJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(2000)
                withContext(Dispatchers.Main) {
                    if (viewModel.isDemoMode) {
                        cameraManager.stopCamera(resetToHome = false)
                        handleMrzDetected("A12345678", "920101", "301231", "ID")
                    }
                }
            }
        }
    }

    private fun handleQrDetected(qrData: String) {
        var nonce: String? = null
        var pkHash: String? = null

        // Öncelik 1: Deeplink URL formatı (https://app.verifyblind.com/request?nonce=...&pk_hash=...)
        try {
            val uri = android.net.Uri.parse(qrData)
            if (uri.scheme == "https" && uri.host == "app.verifyblind.com") {
                nonce = uri.getQueryParameter("nonce")
                pkHash = uri.getQueryParameter("pk_hash")
            }
        } catch (_: Exception) { }

        // Öncelik 2: JSON fallback (geriye uyumluluk — {"nonce":"...","pk_hash":"..."})
        if (nonce == null) {
            try {
                val json = com.google.gson.JsonParser().parse(qrData).asJsonObject
                if (json.has("nonce")) nonce = json.get("nonce").asString
                if (json.has("pk_hash")) pkHash = json.get("pk_hash").asString
            } catch (_: Exception) { }
        }

        if (nonce != null) {
            cameraManager.stopCamera()
            // Taranan QR akışı (deeplink değil) — app-return yok; nonce'u takip et ki yarıda kalırsa iptal edilsin.
            viewModel.returnUrl = null
            viewModel.activeLoginNonce = nonce
            showProcessingScreen(getString(R.string.please_wait), qrMode = true)
            viewModel.fetchPartnerInfo(this, nonce, pkHash)
        } else {
            toast(getString(R.string.invalid_qr))
            updateUiState()
        }
    }

    private fun handleMrzDetected(docNo: String, dob: String, expiry: String, documentType: String) {
        binding.etDocNo.setText(PassportReader.cleanDocNo(docNo))
        binding.etDob.setText(PassportReader.correctDateInput(dob))
        binding.etDoe.setText(PassportReader.correctDateInput(expiry))
        viewModel.detectedDocumentType = documentType
        cameraManager.stopCamera(resetToHome = false)
        lifecycleScope.launch {
            showNfcScanningScreen()
            if (viewModel.isDemoMode) {
                // Demo: handshake gerekmez, NFC ekranı ~2s sonra otomatik geçer
                demoProceedAfterNfc()
                return@launch
            }
            if (!viewModel.isHandshakeSuccessful) {
                binding.tvNfcTitle.text = getString(R.string.nfc_connecting_server)
                viewModel.ensureHandshake(this@MainActivity)
                if (!viewModel.isHandshakeSuccessful) {
                    toast(getString(R.string.connection_error_server))
                    updateUiState()
                    return@launch
                }
                binding.tvNfcTitle.text = getString(R.string.nfc_card_searching)
            }
        }
    }

    private fun demoProceedAfterNfc() {
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            withContext(Dispatchers.Main) {
                stopNfcProgressAnimation()
                binding.tvNfcTitle.text = getString(R.string.nfc_completed)
                binding.pbNfc.progress = 100
                stopNfcPulseAnimation()
                binding.nfcCircleInner.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_success_circle)
                binding.tvStatus.text = getString(R.string.liveness_starting)

                val livenessIntent = Intent(this@MainActivity, LivenessActivity::class.java)
                val fallbackChallenges: List<Int> = listOf(1, 2, 3)
                val demoChallenges = ArrayList<Int>(viewModel.livenessChallenges ?: fallbackChallenges)
                livenessIntent.putIntegerArrayListExtra("challenges", demoChallenges)
                livenessIntent.putExtra("is_demo", true)
                // chip_photo_path yok → LivenessActivity yüz eşleşmesi yapmaz
                // is_demo=true → sahte liveness: her hareket 1 sn sonra otomatik geçer

                BiometricConsentBottomSheet().apply {
                    demoAutoApprove = true
                    onApprove = { livenessLauncher.launch(livenessIntent) }
                    onReject = { updateUiState() }
                }.show(supportFragmentManager, BiometricConsentBottomSheet.TAG)
            }
        }
    }

    // ──────────────────────── UI Screens ────────────────────────

    /**
     * Updates the stepper header to reflect the current step.
     * step=1: Hazırlık active, 2: MRZ active, 3: NFC active, 4: Yüz active, 5: all done
     */
    private fun updateStepperState(step: Int) {
        currentAddCardStep = step
        failedFlowStep = when (step) {
            2 -> com.verifyblind.mobile.util.FlowFeedbackPrompt.FlowStep.MRZ
            3 -> com.verifyblind.mobile.util.FlowFeedbackPrompt.FlowStep.NFC
            4 -> com.verifyblind.mobile.util.FlowFeedbackPrompt.FlowStep.LIVENESS
            5 -> com.verifyblind.mobile.util.FlowFeedbackPrompt.FlowStep.SUBMIT
            else -> null
        }

        // Show the stepper header during card-add flow
        // Hide for QR scan flow (step == 0)
        val showStepper = step in 1..5
        binding.layoutStepperHeader.visibility = if (showStepper) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStepperRow.visibility = if (showStepper) android.view.View.VISIBLE else android.view.View.GONE

        if (!showStepper) return

        val blue = R.drawable.bg_stepper_dot_active
        val gray = R.drawable.bg_stepper_dot_inactive
        val blueColor = ContextCompat.getColor(this, R.color.sv_secondary)
        val grayColor = ContextCompat.getColor(this, R.color.sv_on_surface_variant)
        val blueLineDrawable = ContextCompat.getDrawable(this, R.drawable.bg_stepper_line_active)
        val grayLineDrawable = ContextCompat.getDrawable(this, R.drawable.bg_stepper_line_inactive)

        fun applyStep(
            dot: android.view.View, num: android.widget.TextView,
            check: android.widget.ImageView, label: android.widget.TextView,
            line: android.view.View?, stepN: Int
        ) {
            when {
                stepN < step -> { // Done
                    dot.background = ContextCompat.getDrawable(this, blue)
                    num.visibility = android.view.View.GONE
                    check.visibility = android.view.View.VISIBLE
                    label.setTextColor(blueColor)
                    line?.background = blueLineDrawable
                }
                stepN == step -> { // Active
                    dot.background = ContextCompat.getDrawable(this, blue)
                    num.visibility = android.view.View.VISIBLE
                    check.visibility = android.view.View.GONE
                    label.setTextColor(blueColor)
                    label.setTypeface(null, android.graphics.Typeface.BOLD)
                    line?.background = grayLineDrawable
                }
                else -> { // Pending
                    dot.background = ContextCompat.getDrawable(this, gray)
                    num.visibility = android.view.View.VISIBLE
                    check.visibility = android.view.View.GONE
                    label.setTextColor(grayColor)
                    label.setTypeface(null, android.graphics.Typeface.NORMAL)
                    line?.background = grayLineDrawable
                }
            }
        }

        applyStep(binding.stepDot1, binding.stepNum1, binding.stepCheck1, binding.stepLabel1, binding.stepLine1, 1)
        applyStep(binding.stepDot2, binding.stepNum2, binding.stepCheck2, binding.stepLabel2, binding.stepLine2, 2)
        applyStep(binding.stepDot3, binding.stepNum3, binding.stepCheck3, binding.stepLabel3, binding.stepLine3, 3)
        applyStep(binding.stepDot4, binding.stepNum4, binding.stepCheck4, binding.stepLabel4, null, 4)
    }

    private fun startNfcPulseAnimation() {
        stopNfcPulseAnimation()
        val outer = binding.nfcCircleOuter
        val mid = binding.nfcCircleMid

        val outerScaleX = android.animation.ObjectAnimator.ofFloat(outer, android.view.View.SCALE_X, 0.85f, 1.15f, 0.85f).apply {
            duration = 2000; repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        val outerScaleY = android.animation.ObjectAnimator.ofFloat(outer, android.view.View.SCALE_Y, 0.85f, 1.15f, 0.85f).apply {
            duration = 2000; repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        val outerAlpha = android.animation.ObjectAnimator.ofFloat(outer, android.view.View.ALPHA, 0.4f, 0.9f, 0.4f).apply {
            duration = 2000; repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        val midScaleX = android.animation.ObjectAnimator.ofFloat(mid, android.view.View.SCALE_X, 0.9f, 1.1f, 0.9f).apply {
            duration = 2500; repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        val midScaleY = android.animation.ObjectAnimator.ofFloat(mid, android.view.View.SCALE_Y, 0.9f, 1.1f, 0.9f).apply {
            duration = 2500; repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        nfcPulseAnimSet = android.animation.AnimatorSet().apply {
            playTogether(outerScaleX, outerScaleY, outerAlpha, midScaleX, midScaleY)
            start()
        }
    }

    private fun stopNfcPulseAnimation() {
        nfcPulseAnimSet?.cancel()
        nfcPulseAnimSet = null
        // Reset scale/alpha
        try {
            binding.nfcCircleOuter.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
            binding.nfcCircleMid.apply { scaleX = 1f; scaleY = 1f }
        } catch (e: Exception) { }
    }

    private fun startNfcProgressAnimation() {
        stopNfcProgressAnimation()
        nfcProgressJob = lifecycleScope.launch(Dispatchers.Main) {
            // 70 increments × 140ms = ~9.8s to go from 20 → 90
            var current = binding.pbNfc.progress
            while (current < 90) {
                kotlinx.coroutines.delay(140)
                current++
                binding.pbNfc.progress = current
            }
        }
    }

    private fun stopNfcProgressAnimation() {
        nfcProgressJob?.cancel()
        nfcProgressJob = null
    }

    private fun showNfcScanningScreen() {
        nfcRetryCount = 0
        nfcRetryWatchdogJob?.cancel()
        nfcRetryWatchdogJob = null
        binding.viewFlipper.visibility = android.view.View.VISIBLE
        binding.mainNavHost.visibility = android.view.View.GONE
        binding.viewFlipper.displayedChild = 3

        // Reset NFC screen state
        stopNfcProgressAnimation()
        binding.tvNfcTitle.text = getString(R.string.nfc_card_searching)
        binding.pbNfc.progress = 0
        // Reset inner circle to blue (in case previous attempt turned it green/red)
        binding.nfcCircleInner.background = ContextCompat.getDrawable(this, R.drawable.bg_nfc_circle_inner)
        // Hata durumu artıkları (kırmızı metin, "Yeniden Dene") temizlenir + belge tipi talimatı yazılır
        resetNfcErrorUi()

        updateStepperState(3)
        // NFC ekrani acildiysa MRZ okumasi tamamlanmistir.
        com.verifyblind.mobile.util.FlowTelemetry.reached(com.verifyblind.mobile.util.FlowTelemetry.STEP_MRZ, viewModel.handshakeNonce)
        startNfcPulseAnimation()
        // Bu ekran açıldığında foreground dispatch'i etkinleştir (onResume zaten geçmiş olabilir).
        enableNfcForegroundDispatch()
    }

    private fun showProcessingScreen(status: String, genericMode: Boolean = false, qrMode: Boolean = false) {
        binding.viewFlipper.visibility = android.view.View.VISIBLE
        binding.mainNavHost.visibility = android.view.View.GONE
        binding.viewFlipper.displayedChild = 4
        binding.tvProcessingTitle.text = status
        when {
            genericMode -> {
                binding.tvProcessingSubtitle.visibility = android.view.View.GONE
                binding.layoutProcessingSteps.visibility = android.view.View.GONE
                binding.cardSecureConn.visibility = android.view.View.GONE
            }
            qrMode -> {
                // QR doğrulamada kart okuma ve yüz adımları yok — sadece sunucu adımı göster
                binding.tvProcessingSubtitle.visibility = android.view.View.VISIBLE
                binding.layoutProcessingSteps.visibility = android.view.View.VISIBLE
                binding.cardStepKartOkundu.visibility = android.view.View.GONE
                binding.cardStepYuzDogrulandi.visibility = android.view.View.GONE
                binding.cardSecureConn.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.tvProcessingSubtitle.visibility = android.view.View.VISIBLE
                binding.layoutProcessingSteps.visibility = android.view.View.VISIBLE
                binding.cardStepKartOkundu.visibility = android.view.View.VISIBLE
                binding.cardStepYuzDogrulandi.visibility = android.view.View.VISIBLE
                binding.cardSecureConn.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Okuma koptuğunda (kart kaydı/timeout/IO): UYARI ÇIKARMADAN sessizce tekrar dener — en fazla 3 kez.
     * Üçü de başarısızsa ekran-içi hata + "Yeniden Dene" (iOS `RegisterViewModel.retryOrFail` paritesi).
     *
     * iOS yeni bir CoreNFC oturumu açar; Android'de etiket nesnesi ölür, bu yüzden "tekrar" = ekranı
     * okumaya hazır duruma döndürüp foreground dispatch'in kartı yeniden bulmasını beklemek.
     */
    private fun retryNfcOrShowError(e: Exception) {
        if (nfcRetryCount < 3) {
            nfcRetryCount++
            AppLog.warning("NFC denemesi başarısız (${e.javaClass.simpleName}) → sessiz tekrar #$nfcRetryCount/3", "NFC")
            binding.tvNfcTitle.text = getString(R.string.nfc_reconnecting)
            binding.tvNfcTitle.setTextColor(ContextCompat.getColor(this, R.color.sv_on_surface))
            binding.pbNfc.progress = 0
            startNfcPulseAnimation()
            armNfcRetryWatchdog()
        } else {
            AppLog.warning("NFC 3 sessiz deneme başarısız → hata ekranı (${e.javaClass.simpleName})", "NFC")
            showNfcReadErrorState()
        }
    }

    /** Sessiz tekrardan sonra etiket 15sn içinde geri gelmezse hata ekranını aç. */
    private fun armNfcRetryWatchdog() {
        nfcRetryWatchdogJob?.cancel()
        nfcRetryWatchdogJob = lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(15_000)
            if (isNfcScanScreenActive) showNfcReadErrorState()
        }
    }

    /** Ekran-içi kurtarılabilir hata: kırmızı daire + `nfc_read_error` + "Yeniden Dene" (iOS ile aynı). */
    private fun showNfcReadErrorState() {
        hadErrorInFlow = true
        nfcRetryWatchdogJob?.cancel()
        nfcRetryWatchdogJob = null
        nfcRetryCount = 0
        viewModel.isNfcOperationActive = false
        stopNfcProgressAnimation()
        stopNfcPulseAnimation()
        binding.pbNfc.progress = 0
        binding.tvNfcTitle.visibility = android.view.View.GONE
        binding.tvNfcDesc.text = getString(R.string.nfc_read_error)
        binding.tvNfcDesc.setTextColor(ContextCompat.getColor(this, R.color.error))
        binding.nfcCircleInner.background = ContextCompat.getDrawable(this, R.drawable.bg_error_circle)
        binding.btnNfcRetry.visibility = android.view.View.VISIBLE
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) { }
    }

    /** Hata durumundan okuma durumuna dönüş (yeni etiket ya da "Yeniden Dene"). */
    private fun resetNfcErrorUi() {
        binding.btnNfcRetry.visibility = android.view.View.GONE
        binding.tvNfcTitle.visibility = android.view.View.VISIBLE
        binding.tvNfcTitle.setTextColor(ContextCompat.getColor(this, R.color.sv_on_surface))
        binding.tvNfcDesc.setTextColor(ContextCompat.getColor(this, R.color.sv_on_surface_variant))
        binding.tvNfcDesc.text = if (viewModel.detectedDocumentType == "PASSPORT") {
            getString(R.string.nfc_passport_instruction)
        } else {
            getString(R.string.nfc_id_instruction)
        }
    }

    // ──────────────────────── Dialogs ────────────────────────

    fun showHandshakeErrorWarning(onSuccess: (() -> Unit)? = null) {
        val (title, message) = viewModel.getHandshakeErrorMessage()
        if (title == getString(R.string.security_block_title)) {
            showSecurityBlockDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.handshake_retry)) { _, _ ->
                toast(getString(R.string.handshake_connecting))
                lifecycleScope.launch {
                    viewModel.performHandshake(this@MainActivity)
                    if (viewModel.isHandshakeSuccessful) {
                        withContext(Dispatchers.Main) { onSuccess?.invoke() }
                    } else {
                        withContext(Dispatchers.Main) { toast(getString(R.string.handshake_retry_failed)) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSecurityBlockDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.security_warning_title))
            .setMessage(getString(R.string.security_warning_message))
            .setPositiveButton(getString(R.string.btn_go_to_play)) { _, _ ->
                val uri = android.net.Uri.parse("market://details?id=$packageName")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
                finish()
            }
            .setNegativeButton(getString(R.string.btn_close)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun setupKvkkCardAddSection() {
        // Onay her seferinde yeniden alınmalı — önceki seçim hatırlanmaz, daima unchecked başlar.
        binding.cbKvkkConsentCardAdd.isChecked = false
        binding.btnStartCardAdd.isEnabled = false
        binding.btnStartCardAdd.alpha = 0.5f

        binding.cbKvkkConsentCardAdd.setOnCheckedChangeListener { _, isChecked ->
            binding.btnStartCardAdd.isEnabled = isChecked
            binding.btnStartCardAdd.alpha = if (isChecked) 1.0f else 0.5f
        }

        val label = getString(R.string.read_privacy_notice)
        val spannable = android.text.SpannableString(label).apply {
            setSpan(android.text.style.UnderlineSpan(), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvPrivacyNoticeCardAdd.text = spannable
        binding.tvPrivacyNoticeCardAdd.setOnClickListener {
            fetchAndShowPrivacyNoticeCardAdd()
        }
    }

    private fun fetchAndShowPrivacyNoticeCardAdd() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getPrivacyNotice(format = "text")
                }
                val text = if (response.isSuccessful && response.body()?.has("text") == true) {
                    response.body()!!.get("text").asString
                } else {
                    getString(R.string.privacy_notice_load_error)
                }
                showPrivacyNoticeDialog(text)
            } catch (e: Exception) {
                showPrivacyNoticeDialog(getString(R.string.privacy_notice_load_failed))
            }
        }
    }

    private fun showPrivacyNoticeDialog(content: String) {
        val dp = resources.displayMetrics.density
        val scrollView = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            text = content
            textSize = 13f
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sv_on_surface))
        }
        scrollView.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_notice_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showHowItWorksDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.how_it_works_title))
            .setMessage(getString(R.string.how_it_works_desc))
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    private fun showForceUpdateDialog(storeUrl: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.force_update_title))
            .setMessage(getString(R.string.force_update_message))
            .setPositiveButton(getString(R.string.force_update_btn)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(storeUrl)))
                } catch (e: Exception) {
                    AppLog.warning("Uygulama mağazası açılamadı: ${e.message}", "VerifyBlind", e)
                }
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    // ──────────────────────── Biometric Auth ────────────────────────

    private fun checkBiometricLogin() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val isBiometricEnabled = prefs.getBoolean("biometric_enabled", false)

        if (isBiometricEnabled && !isAuthenticated && !viewModel.isNfcOperationActive && !viewModel.isCryptoOperationActive) {
            binding.layoutAppLock.visibility = android.view.View.VISIBLE
            binding.btnUnlock.visibility = android.view.View.GONE
            binding.mainNavHost.visibility = android.view.View.GONE

            binding.viewFlipper.visibility = android.view.View.GONE

            BiometricHelper.authenticate(this,
                onSuccess = {
                    isAuthenticated = true
                    runOnUiThread {
                        binding.layoutAppLock.visibility = android.view.View.GONE
                        binding.mainNavHost.visibility = android.view.View.VISIBLE
                        updateUiState()
                    }
                },
                onError = {
                    isAuthenticated = false
                    runOnUiThread {
                        binding.btnUnlock.visibility = android.view.View.VISIBLE
                    }
                }
            )
        } else {
            if (!isBiometricEnabled) {
                binding.layoutAppLock.visibility = android.view.View.GONE
            }
        }
    }

    // ──────────────────────── Ticket ────────────────────────

    /**
     * Kartı siler. [deleteHistory] true ise o karta ait TÜM işlem geçmişi de silinir.
     *
     * Neden seçenek: geçmiş normalde kart silinince DURUR — yalnızca liste cardId ile filtrelendiği
     * için görünmez olur ve aynı kart tekrar eklenince geri gelir. Bu bilinçli bir tasarım (bulut
     * restore modelinin tamamı buna dayanır) ama silmek isteyen kullanıcı için "gizlendi ≠ silindi"
     * yanılgısı yaratıyordu. Artık kullanıcı açıkça seçiyor.
     *
     * Tombstone kullanılır (sert DELETE değil): silme bir sonraki senkronla buluta ve diğer
     * cihazlara yayılsın. deleteHistory seçilirse DELETED_CARD kaydı da EKLENMEZ — aksi halde o
     * kayıt aynı cardId ile geride kalır ve kart tekrar eklenince yeniden görünürdü.
     */
    fun deleteTicket(deleteHistory: Boolean = false) {
        BiometricHelper.authenticate(this,
            onSuccess = {
                val pid = com.verifyblind.mobile.util.SecureStore.getPersonId(this) ?: ""
                val cid = com.verifyblind.mobile.util.SecureStore.getCardId(this) ?: ""
                viewModel.clearTicket()
                toast(getString(
                    if (deleteHistory) R.string.card_and_history_deleted_toast
                    else R.string.card_deleted_toast
                ))
                updateUiState()
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (deleteHistory && cid.isNotEmpty()) {
                        historyRepository.deleteByCardId(cid)
                    } else {
                        historyRepository.insert(
                            title = getString(R.string.history_card_deleted_title),
                            description = getString(R.string.history_card_deleted_desc),
                            status = 1,
                            actionType = com.verifyblind.mobile.data.HistoryAction.DELETED_CARD,
                            nonce = java.util.UUID.randomUUID().toString(),
                            personId = pid,
                            cardId = cid
                        )
                    }
                }
            },
            onError = { toast(getString(R.string.operation_cancelled)) }
        )
    }

    /** Kartı cüzdandan kaldırır (biometric gerektirmez — çağıran zaten onay almış). */
    fun clearCard() {
        viewModel.clearTicket()
        updateUiState()
    }

    // ──────────────────────── Helpers ────────────────────────

    /**
     * Bir login akışını sonlandırır.
     * - status != "success" ise ve aktif bir login nonce'u varsa iptal eder (Item 3b) → partner poll'u
     *   anında "cancelled" alır, "lütfen bekleyiniz"de takılmaz.
     * - Deeplink akışıysa partner uygulamasına geri döner (kayıtlı şemayla doğrulanmış return URL) ve
     *   uygulamayı kapatır; değilse cüzdana döner.
     */
    private fun finishDeepLinkFlowOrUpdateUi(
        isDeepLink: Boolean = viewModel.isDeepLinkFlow,
        status: String = "cancelled"
    ) {
        if (status != "success") {
            val nonce = viewModel.activeLoginNonce
            if (!nonce.isNullOrEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) { viewModel.cancelQrNonce(nonce) }
            }
        }

        if (isDeepLink) openPartnerReturnIfValid(status)

        viewModel.returnUrl = null
        viewModel.partnerAppReturnScheme = null
        viewModel.activeLoginNonce = null

        if (isDeepLink) {
            finishAndRemoveTask()
        } else {
            updateUiState()
        }
    }

    /**
     * App-to-app "geri dönüş": deeplink'teki return URL'in şeması partner'ın kayıtlı app_return_scheme'i
     * ile eşleşirse (fail-closed → açık-yönlendirme önlemi) return URL'i nonce+status ile açar.
     * Kayıtlı şema yoksa/uyuşmazsa hiçbir şey açılmaz.
     */
    private fun openPartnerReturnIfValid(status: String): Boolean {
        val ret = viewModel.returnUrl?.takeIf { it.isNotBlank() } ?: return false
        val registered = viewModel.partnerAppReturnScheme?.takeIf { it.isNotBlank() } ?: return false
        val nonce = viewModel.activeLoginNonce
        return try {
            val uri = android.net.Uri.parse(ret)
            val scheme = uri.scheme
            if (scheme.isNullOrEmpty() || !scheme.equals(registered, ignoreCase = true)) {
                AppLog.error("Return şeması kayıtlı şemayla uyuşmuyor: '$scheme' != '$registered' — açılmadı")
                return false
            }
            val outUri = uri.buildUpon().apply {
                if (!nonce.isNullOrEmpty()) appendQueryParameter("nonce", nonce)
                appendQueryParameter("status", status)
            }.build()
            startActivity(Intent(Intent.ACTION_VIEW, outUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            AppLog.error("Return URL açılamadı: ${e.message}", throwable = e)
            false
        }
    }

    private fun toast(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────── Public Accessors for Fragments ────────────────────────

    // These properties are accessed by fragments (e.g. WalletFragment)
    val signedTicketJson: String?
        get() = viewModel.signedTicketJson

    val isHandshakeSuccessful: Boolean
        get() = viewModel.isHandshakeSuccessful

    val isHandshaking: Boolean
        get() = viewModel.isHandshaking

    val isHandshakeFailed: Boolean
        get() = viewModel.isHandshakeFailed

    val isDemoEnabled: Boolean
        get() = viewModel.demoEnabled

}
