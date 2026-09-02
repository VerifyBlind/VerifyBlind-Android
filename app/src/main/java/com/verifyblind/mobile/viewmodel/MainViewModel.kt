package com.verifyblind.mobile.viewmodel

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.firebase.messaging.FirebaseMessaging
import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.*
import com.verifyblind.mobile.crypto.CryptoUtils
import com.verifyblind.mobile.nfc.PassportReader
import com.verifyblind.mobile.util.AppLog
import com.verifyblind.mobile.util.BiometricHelper
import com.verifyblind.mobile.util.IntegrityManagerHelper
import com.verifyblind.mobile.util.LegalTerms
import com.verifyblind.mobile.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * MainViewModel — MainActivity'den ayrıştırılmış iş mantığı katmanı.
 *
 * Sorumluluklar:
 * - Handshake + attestation doğrulama
 * - Registration (NFC → Enclave)
 * - Login (QR → Enclave)
 * - Ticket CRUD (SharedPreferences)
 * - Partner bilgisi çekme
 * - Uygulama güncelleme kontrolü
 * - API hata parse
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val HANDSHAKE_TTL_MS = 5 * 60 * 1000L // 5 dakika
    }

    private val gson = Gson()
    private fun str(id: Int): String = getApplication<Application>().getString(id)
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /** Attestation başarısızlığında kullanıcıya gösterilecek dostça, türe-göre mesaj. Ham failReason
     *  ASLA gösterilmez (yalnız Sentry/log). null/INTEGRITY → güvenli taraf (integrity mesajı). */
    private fun friendlyAttestMessage(kind: com.verifyblind.mobile.crypto.AttestFailureKind?): String =
        when (kind) {
            com.verifyblind.mobile.crypto.AttestFailureKind.AUTHORIZATION -> str(R.string.attestation_error_authorization)
            else -> str(R.string.attestation_error_integrity)
        }

    // ──────────────────────── State ────────────────────────

    // Handshake
    var enclavePubKey: String? = null
        private set
    var handshakeNonce: String? = null
        private set
    var pendingRegistrationNonce: String? = null
        private set
    var handshakeTimestamp: Long = 0
        private set
    var handshakeSignature: String? = null
        private set
    var livenessChallenges: List<Int>? = null
        private set

    private var _isHandshakeSuccessful = false
    private var handshakeCompletedAt = 0L

    /** 5 dakika TTL — sunucu restart sonrası eski anahtar kullanılmaz. */
    val isHandshakeSuccessful: Boolean
        get() = _isHandshakeSuccessful && enclavePubKey != null &&
                System.currentTimeMillis() - handshakeCompletedAt < HANDSHAKE_TTL_MS

    /** Handshake kesinlikle başarısız oldu (çalışmıyor, ağ hatası var). */
    val isHandshakeFailed: Boolean
        get() = !_isHandshakeSuccessful && !isHandshaking && lastHandshakeError != null

    var isHandshaking = false
        private set

    // Login Handshake (sadece attestation — nonce/challenges gereksiz)
    private var _isLoginHandshakeSuccessful = false
    private var loginHandshakeCompletedAt = 0L
    var isLoginHandshaking = false
        private set
    private var lastLoginHandshakeError: String? = null

    /** Register handshake tazeyse login handshake da tazedir (enclavePubKey paylaşılır). */
    val isLoginHandshakeSuccessful: Boolean
        get() = enclavePubKey != null && (
            isHandshakeSuccessful ||
            (_isLoginHandshakeSuccessful && System.currentTimeMillis() - loginHandshakeCompletedAt < HANDSHAKE_TTL_MS)
        )
    private var lastHandshakeError: String? = null
    // Handshake hatasının türü → getHandshakeErrorMessage'ın başlığını ve "bağlantını kontrol et"
    // ipucunu ekleyip eklemeyeceğini belirler. SERVER (5xx) ve CONNECTION (HTTP cevabı yok) mesajları
    // kendi içinde "tekrar deneyin" der → ipucu EKLENMEZ. OTHER (4xx) sunucu detayını taşır → eklenir.
    private enum class HandshakeErrorKind { NONE, SERVER, CONNECTION, OTHER }
    private var lastHandshakeErrorKind = HandshakeErrorKind.NONE

    // User / Ticket
    var userPubKey: String? = null
        private set
    var signedTicketJson: String? = null
        private set

    // Deep Link
    var isDeepLinkFlow = false
    // App-to-app "geri dönüş": deeplink'teki return URL'i + partner-info'daki kayıtlı şema (doğrulama).
    var returnUrl: String? = null
    var partnerAppReturnScheme: String? = null
    // Aktif login nonce'u (deeplink veya taranan QR). Akış yarıda kalırsa iptal edilir (partner poll'u
    // "cancelled" alsın diye); başarıda temizlenir.
    var activeLoginNonce: String? = null

    // Demo Mode
    var isDemoMode = false
    var demoEnabled = false

    // Biometrics / Registration
    var userSelfiePath: String? = null
    var antiSpoofCropPath: String? = null
    var pendingPassportData: PassportReader.PassportData? = null
    var detectedDocumentType: String = "ID" // "ID" or "PASSPORT"

    // ──────────────────────── LiveData ────────────────────────

    private val _uiEvent = MutableLiveData<UiEvent?>()
    val uiEvent: LiveData<UiEvent?> = _uiEvent

    private val _isAuthenticated = MutableLiveData(false)
    val isAuthenticated: LiveData<Boolean> = _isAuthenticated

    var isNfcOperationActive = false
    var isCryptoOperationActive = false

    // ──────────────────────── Init ────────────────────────

    init {
        initUserKey()
        loadTicket()
    }

    private fun initUserKey() {
        // Anahtar biyometri-gerektiren olarak üretilir; cihazda kayıtlı biyometri yoksa Keystore
        // InvalidAlgorithmParameterException fırlatır. Önden kontrol et: bu BEKLENEN bir cihaz
        // durumu (kullanıcı ayarlardan düzeltebilir), hata değil → Sentry'ye error olarak
        // yazma. Kullanıcı bir akış başlatınca net mesajı requireBiometricReady() gösterir.
        val availability = BiometricHelper.availability(getApplication())
        if (availability != BiometricHelper.Availability.AVAILABLE) {
            // info: Sentry'ye EVENT göndermez, yalnız breadcrumb bırakır. Ekran kilidi olmaması
            // beklenen bir ÇEVRE durumu (kullanıcı ayarlardan düzeltir), kod arızası değil —
            // ve kilitsiz otomatik test cihazları (Play ön-lansman taraması) her koşuda tetikler,
            // warning bırakılsaydı kota tüketen tekrarlayan gürültü olurdu. Kullanıcı zaten
            // requireBiometricReady() ile net mesajı görüyor.
            AppLog.info("Kullanıcı anahtarı üretilmedi — cihaz kilidi durumu: $availability")
            return
        }
        try {
            userPubKey = CryptoUtils.ensureKeyExists()
        } catch (e: Exception) {
            AppLog.error("Keystore Hatası: ${e.message}", throwable = e)
        }
    }

    /**
     * Biyometri hazır değilse kullanıcıya NET mesaj gösterir ve false döner.
     * Anahtar üretimi gerektiren her kullanıcı akışının başında çağrılır — aksi halde akış
     * anlaşılmaz bir hatayla yarıda kalıyordu (kullanıcı neden başarısız olduğunu göremiyordu).
     */
    private fun requireBiometricReady(): Boolean {
        val availability = BiometricHelper.availability(getApplication())
        if (availability == BiometricHelper.Availability.AVAILABLE) return true

        val messageRes = BiometricHelper.unavailableMessageRes(availability)
        if (messageRes != null) {
            val title = str(R.string.biometric_required_title)
            val message = str(messageRes)
            // Kilit yoksa kullanıcı bunu DÜZELTEBİLİR → onu kurulum ekranına götüren buton ver.
            // UNAVAILABLE (donanım geçici meşgul) düzeltilebilir değil, düz mesaj kalsın.
            _uiEvent.postValue(
                if (availability == BiometricHelper.Availability.NO_DEVICE_LOCK)
                    UiEvent.ShowDeviceLockRequired(title, message)
                else
                    UiEvent.ShowMessage(title, message)
            )
        }
        return false
    }

    // ──────────────────────── Ticket CRUD ────────────────────────

    fun loadTicket() {
        val currentKeystoreKey = userPubKey  // set by initUserKey() before this call
        val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        val storedPubKey = prefs.getString("userPubKey", null)

        // Detect stale data from Android Auto Backup after reinstall:
        // ticket is encrypted with the stored public key's corresponding private key.
        // If the stored key no longer matches the current Keystore key, the private key
        // is gone and the ticket is undecryptable — clear everything silently.
        if (storedPubKey != null && currentKeystoreKey != null && storedPubKey != currentKeystoreKey) {
            Log.w("VerifyBlind", "Kayıt anahtarı uyuşmazlığı — eski yedek verisi temizleniyor")
            prefs.edit().clear().apply()
            com.verifyblind.mobile.util.SecureStore.clear(getApplication())
            signedTicketJson = null
            userPubKey = currentKeystoreKey
            return
        }

        val storedTicket = prefs.getString("ticket", null)
        // Eski demo akışından kalma "DEMO_MODE" string'i veya geçersiz format → bayat veriyi temizle.
        // Yeni demo akışında gerçek HybridContent JSON kaydedilir; bu blok sadece eski yüklemelerden
        // gelen tutarsız state'i sessizce siler.
        signedTicketJson = if (storedTicket != null && !isValidHybridContentJson(storedTicket)) {
            Log.w("VerifyBlind", "Bayat ticket formatı tespit edildi — temizleniyor")
            prefs.edit().remove("ticket").apply()
            null
        } else {
            storedTicket
        }
        userPubKey = storedPubKey ?: currentKeystoreKey
    }

    private fun isValidHybridContentJson(json: String): Boolean {
        return try {
            val hc = gson.fromJson(json, HybridContent::class.java)
            hc != null && hc.encKey.isNotEmpty() && hc.blob.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun saveTicket(ticket: String, pubKey: String) {
        val (aesBlob, aesKey, _) = CryptoUtils.aesEncrypt(ticket)
        val encryptedKey = CryptoUtils.rsaEncryptForKeystore(aesKey, pubKey)
        val storageJson = gson.toJson(HybridContent(encryptedKey, aesBlob))
        val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("ticket", storageJson).putString("userPubKey", pubKey).apply()
    }

    fun clearTicket() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        com.verifyblind.mobile.util.SecureStore.clear(app)
        signedTicketJson = null
        isDemoMode = false
    }

    fun setAuthenticated(value: Boolean) {
        _isAuthenticated.postValue(value)
    }

    val isAuthenticatedValue: Boolean
        get() = _isAuthenticated.value ?: false

    // ──────────────────────── Handshake ────────────────────────

    suspend fun performHandshake(context: Context) = withContext(Dispatchers.IO) {
        if (isHandshaking) return@withContext
        // Security gate: outdated APKs must not perform handshake.
        if (isAppOutdated()) return@withContext
        // Biyometri hazır değilse anahtar üretilemez; erken ve NET mesajla dur (aksi halde akış
        // aşağıda anlaşılmaz bir Keystore hatasıyla yarıda kalıyordu).
        if (!requireBiometricReady()) return@withContext
        isHandshaking = true
        _isHandshakeSuccessful = false
        handshakeCompletedAt = 0L

        try {
            if (userPubKey == null) {
                userPubKey = CryptoUtils.ensureKeyExists()
            }
            log("User Key Ready: ${mask(userPubKey)}")
            log("Step 1: Handshake...")

            val localHandshakeNonce = java.util.UUID.randomUUID().toString()
            val token = IntegrityManagerHelper.requestIntegrityToken(context, localHandshakeNonce)

            val fcmToken = try {
                val cached = SecureStore.getFcmToken(context)
                if (cached != null) cached
                else FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) { null }

            val res = RetrofitClient.api.handshake(
                integrityToken = token,
                request = HandshakeRequest(fcmToken = fcmToken)
            )

            if (res.isSuccessful && res.body() != null) {
                lastHandshakeError = null
                lastHandshakeErrorKind = HandshakeErrorKind.NONE
                val body = res.body()!!
                log("RAW Handshake Response: ${gson.toJson(body)}")

                val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)

                val serverKey: String
                if (BuildConfig.USE_LOCAL_API) {
                    // Local dev mode: no real Nitro Enclave — skip attestation, use key directly
                    serverKey = body.enclavePubKey ?: run {
                        log("❌ CRITICAL: No enclave_pub_key in handshake response (local mode)!")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_enclave_key_missing)))
                        return@withContext
                    }
                    log("⚠️ LOCAL API MODE: Attestation skipped, enclave key taken directly from response.")
                    prefs.edit().apply {
                        putString("last_pcr0", "LOCAL_DEV")
                        putBoolean("last_hardware_verified", false)
                        putBoolean("last_is_mock", true)
                        putLong("last_attestation_time", System.currentTimeMillis())
                        apply()
                    }
                } else {
                    val attestDoc = body.attestationDocument
                    if (attestDoc.isNullOrBlank()) {
                        log("❌ CRITICAL: No attestation document in handshake response!")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_attestation_missing)))
                        return@withContext
                    }

                    log("Verifying Hardware Attestation Document (AWS Nitro)...")

                    val attestResult = com.verifyblind.mobile.crypto.AttestationVerifier.verify(
                        attestationBase64 = attestDoc,
                        pcr0Signature = body.pcr0Signature
                    )

                    if (!attestResult.isValid) {
                        com.verifyblind.mobile.util.AppLog.error("Android hardware attestation FAILED: ${attestResult.failReason}")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.error_attestation_title), friendlyAttestMessage(attestResult.failureKind)))
                        return@withContext
                    }

                    serverKey = attestResult.enclavePubKey ?: run {
                        log("❌ CRITICAL: Could not extract Enclave Pub Key from Attestation!")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_attestation_key_unreadable)))
                        return@withContext
                    }

                    if (attestResult.isMockDocument) {
                        log("⚠️ Mock attestation document (DEV MODE). Hardware not verified.")
                    } else {
                        log("✅ Hardware Attestation VERIFIED. PCR0: ${mask(attestResult.pcr0)}")
                    }

                    prefs.edit().apply {
                        putString("last_pcr0", attestResult.pcr0)
                        putBoolean("last_hardware_verified", attestResult.isValid && !attestResult.isMockDocument)
                        putBoolean("last_is_mock", attestResult.isMockDocument)
                        putLong("last_attestation_time", System.currentTimeMillis())
                        apply()
                    }
                }

                enclavePubKey = serverKey
                handshakeNonce = body.nonce
                com.verifyblind.mobile.util.FlowTelemetry.reached(
                    com.verifyblind.mobile.util.FlowTelemetry.STEP_HANDSHAKE, body.nonce)
                handshakeTimestamp = body.timestamp
                handshakeSignature = body.nonceSignature
                livenessChallenges = body.challenges

                _isHandshakeSuccessful = true
                handshakeCompletedAt = System.currentTimeMillis()
                log("Handshake Success! Nonce: ${mask(handshakeNonce)}, Timestamp: $handshakeTimestamp")
            } else {
                val code = res.code()
                val errBody = res.errorBody()?.string()   // tek kez okunabilir → en üstte al
                val serverMsg = com.verifyblind.mobile.util.ServerErrorMessages.serverErrorOrNull(getApplication<Application>(), code)
                if (serverMsg != null) {
                    lastHandshakeErrorKind = HandshakeErrorKind.SERVER
                    lastHandshakeError = serverMsg
                } else {
                    lastHandshakeErrorKind = HandshakeErrorKind.OTHER
                    lastHandshakeError = parseApiError(errBody, "${str(R.string.connection_error_title)}: $code")
                }
                log("Handshake Failed: $code - $lastHandshakeError")
                // Handshake reddi register+login'in giriş kapısı → Sentry'de görünür olmalı (warning, PII'siz).
                AppLog.warning("Handshake reddedildi: HTTP $code kod=${errorCodeOf(errBody) ?: "?"}", "Handshake")
            }
        } catch (e: Exception) {
            // HTTP cevabı YOK (DNS/TCP/timeout) = gerçek bağlantı sorunu. iOS .network ile aynı kanonik mesaj.
            lastHandshakeErrorKind = HandshakeErrorKind.CONNECTION
            lastHandshakeError = com.verifyblind.mobile.util.ServerErrorMessages.connectionFailed(getApplication<Application>())
            log("Handshake Error: ${e.message}")
            // Bağlantı hatası (beklenen/çevresel) → warning (error değil), stacktrace'li. Host/URL PII değil.
            AppLog.warning("Handshake bağlantı hatası", "Handshake", e)
        } finally {
            isHandshaking = false
        }
    }

    /**
     * Handshake'i gerekirse yapar, yoksa atlar.
     * - Taze (TTL içinde) → atla
     * - Şu an çalışıyor → tamamlanmasını bekle
     * - Bayat / hiç yapılmamış → yap
     */
    suspend fun ensureHandshake(context: Context) = withContext(Dispatchers.IO) {
        if (isHandshakeSuccessful) return@withContext   // taze — atla
        if (!isHandshaking) performHandshake(context)   // bayat/hiç yapılmamış — başlat
        while (isHandshaking) delay(100)                // çalışıyorsa bekle
    }

    suspend fun performLoginHandshake(context: Context) = withContext(Dispatchers.IO) {
        if (isLoginHandshaking) return@withContext
        isLoginHandshaking = true
        _isLoginHandshakeSuccessful = false
        loginHandshakeCompletedAt = 0L

        try {
            if (userPubKey == null) userPubKey = CryptoUtils.ensureKeyExists()

            val localNonce = java.util.UUID.randomUUID().toString()
            val token = try { IntegrityManagerHelper.requestIntegrityToken(context, localNonce) } catch (e: Exception) { null }

            val fcmToken = try {
                SecureStore.getFcmToken(context) ?: FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) { null }

            val res = RetrofitClient.api.loginHandshake(
                integrityToken = token,
                request = HandshakeRequest(fcmToken = fcmToken)
            )

            if (res.isSuccessful && res.body() != null) {
                val body = res.body()!!

                if (BuildConfig.USE_LOCAL_API) {
                    // Local dev mode: no real Nitro Enclave — skip attestation, use key directly
                    enclavePubKey = body.enclavePubKey ?: run {
                        log("❌ CRITICAL: No enclave_pub_key in login-handshake response (local mode)!")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_enclave_key_missing)))
                        return@withContext
                    }
                    log("⚠️ LOCAL API MODE: Login-handshake attestation skipped.")
                } else {
                    val attestDoc = body.attestationDocument
                    if (attestDoc.isNullOrBlank()) {
                        log("❌ CRITICAL: No attestation document in login-handshake response!")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_attestation_missing)))
                        return@withContext
                    }

                    val attestResult = com.verifyblind.mobile.crypto.AttestationVerifier.verify(
                        attestationBase64 = attestDoc,
                        pcr0Signature = body.pcr0Signature
                    )

                    if (!attestResult.isValid) {
                        com.verifyblind.mobile.util.AppLog.error("Android login-handshake attestation FAILED: ${attestResult.failReason}")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.error_attestation_title), friendlyAttestMessage(attestResult.failureKind)))
                        return@withContext
                    }

                    enclavePubKey = attestResult.enclavePubKey ?: run {
                        log("❌ CRITICAL: Could not extract Enclave Pub Key from login-handshake attestation!")
                        // Attestation'dan enclave anahtarı çıkarılamadı = gerçek güvenlik/sistem arızası → Sentry ERROR.
                        AppLog.error("KRİTİK: login-handshake attestation'dan Enclave Pub Key çıkarılamadı", "LoginHandshake")
                        _uiEvent.postValue(UiEvent.CriticalError(str(R.string.security_error_title), str(R.string.error_attestation_key_unreadable)))
                        return@withContext
                    }
                }

                _isLoginHandshakeSuccessful = true
                loginHandshakeCompletedAt = System.currentTimeMillis()
                lastLoginHandshakeError = null
                log("Login Handshake Success!")
            } else {
                val errBody = res.errorBody()?.string()
                lastLoginHandshakeError = friendlyApiError(res.code(), errBody, "${str(R.string.connection_error_title)}: ${res.code()}")
                log("Login Handshake Failed: ${res.code()} - $lastLoginHandshakeError")
                AppLog.warning("Login-handshake reddedildi: HTTP ${res.code()} kod=${errorCodeOf(errBody) ?: "?"}", "LoginHandshake")
            }
        } catch (e: Exception) {
            // HTTP cevabı YOK (DNS/TCP/timeout) = bağlantı sorunu → handshake + iOS .network ile aynı mesaj.
            lastLoginHandshakeError = com.verifyblind.mobile.util.ServerErrorMessages.connectionFailed(getApplication<Application>())
            log("Login Handshake Error: ${e.message}")
            AppLog.warning("Login-handshake bağlantı hatası", "LoginHandshake", e)
        } finally {
            isLoginHandshaking = false
        }
    }

    suspend fun ensureLoginHandshake(context: Context) = withContext(Dispatchers.IO) {
        if (isLoginHandshakeSuccessful) return@withContext
        if (!isLoginHandshaking) performLoginHandshake(context)
        while (isLoginHandshaking) delay(100)
    }

    sealed class AttestProbe {
        data class Verified(val pcr0: String) : AttestProbe()
        object Failed : AttestProbe()        // gerçek doğrulama hatası → kırmızı
        object Unreachable : AttestProbe()   // ağ/sunucu → snapshot fallback
    }

    /** Security ekranı için YAN-ETKİSİZ attestation sondası — CriticalError/app-close YOK.
     *  Verified'da last_* prefs'i günceller; ağ/HTTP hatasında Unreachable (snapshot korunur). */
    suspend fun probeAttestation(context: Context): AttestProbe = withContext(Dispatchers.IO) {
        try {
            val localNonce = java.util.UUID.randomUUID().toString()
            val token = try { IntegrityManagerHelper.requestIntegrityToken(context, localNonce) } catch (e: Exception) { null }
            val res = RetrofitClient.api.loginHandshake(integrityToken = token, request = HandshakeRequest())
            if (!res.isSuccessful || res.body() == null) return@withContext AttestProbe.Unreachable
            val body = res.body()!!
            val doc = body.attestationDocument ?: return@withContext AttestProbe.Failed
            val r = com.verifyblind.mobile.crypto.AttestationVerifier.verify(doc, body.pcr0Signature)
            val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
            if (r.isValid) {
                prefs.edit()
                    .putString("last_pcr0", r.pcr0)
                    .putBoolean("last_hardware_verified", !r.isMockDocument)
                    .putBoolean("last_is_mock", r.isMockDocument)
                    .putLong("last_attestation_time", System.currentTimeMillis())
                    .apply()
                AttestProbe.Verified(r.pcr0 ?: "N/A")
            } else {
                prefs.edit()
                    .putBoolean("last_hardware_verified", false)
                    .putBoolean("last_is_mock", false)
                    .putLong("last_attestation_time", System.currentTimeMillis())
                    .apply()
                AttestProbe.Failed
            }
        } catch (e: Exception) {
            AttestProbe.Unreachable
        }
    }

    fun getHandshakeErrorMessage(): Pair<String, String> {
        // SERVER (5xx) ve CONNECTION mesajları kendi içinde "tekrar deneyin" der → bağlantı ipucu EKLEME.
        // OTHER (4xx) sunucu detayını taşır → bağlantı/cihaz ipucunu ekle.
        val message = when {
            lastHandshakeError.isNullOrBlank() -> str(R.string.handshake_error_generic)
            lastHandshakeErrorKind == HandshakeErrorKind.OTHER ->
                "$lastHandshakeError\n\n${str(R.string.handshake_error_retry_hint)}"
            else -> lastHandshakeError!!
        }

        val title = when {
            lastHandshakeErrorKind == HandshakeErrorKind.SERVER -> str(R.string.error_server_unavailable_title)
            lastHandshakeError?.contains("Security", ignoreCase = true) == true ||
                lastHandshakeError?.contains("Güvenlik", ignoreCase = true) == true ||
                lastHandshakeError?.contains("Integrity", ignoreCase = true) == true ->
                str(R.string.security_block_title)
            else -> str(R.string.connection_error_title)
        }

        return Pair(title, message)
    }

    // ──────────────────────── Registration ────────────────────────

    suspend fun finalizeRegistration(
        context: Context,
        passportData: PassportReader.PassportData,
        onStatusUpdate: suspend (String) -> Unit
    ) {
        try {
            if (userPubKey == null) {
                userPubKey = CryptoUtils.ensureKeyExists()
            }

            if (userPubKey == null) throw Exception("User Public Key is missing!")
            // HARDENING (invariant): enclavePubKey yalnız verified attestation'dan gelir. Yoksa (offline
            // açılışta handshake hiç tamamlanmadıysa) kaydı GÖNDERME — dostça bağlantı mesajıyla dur.
            // NOT: register nonce'u ilk handshake'e bağlı; burada YENİDEN handshake YAPMA (payload imzası
            // o nonce'a bağlı). Sadece verified anahtarın varlığını şart koş.
            if (enclavePubKey == null) {
                _uiEvent.postValue(UiEvent.RegistrationFailed(str(R.string.handshake_error_generic)))
                return
            }

            val sodBytes = passportData.sod.encoded
            val dg1Bytes = passportData.dg1Raw
            val activeSig = passportData.activeAuthSignature

            var userSelfieBase64 = ""
            if (userSelfiePath != null) {
                try {
                    val bytes = java.io.File(userSelfiePath!!).readBytes()
                    userSelfieBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (e: Exception) {
                    log("Selfie Read Error: ${e.message}")
                }
            }

            var antiSpoofCropBase64 = ""
            if (antiSpoofCropPath != null) {
                try {
                    val bytes = java.io.File(antiSpoofCropPath!!).readBytes()
                    antiSpoofCropBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (e: Exception) {
                    log("AntiSpoof Crop Read Error (non-fatal): ${e.message}")
                }
            }

            var integrityToken = ""
            if (handshakeNonce != null) {
                log("Fetching Play Integrity Token...")
                onStatusUpdate(str(R.string.security_check_in_progress))
                integrityToken = IntegrityManagerHelper.requestIntegrityToken(context, handshakeNonce!!) ?: ""
                log("Integrity Token Fetched: ${if (integrityToken.isNotEmpty()) "OK" else "FAIL"}")
            }

            val payload = SecurePayload(
                SOD = Base64.encodeToString(sodBytes, Base64.NO_WRAP),
                DG1 = Base64.encodeToString(dg1Bytes, Base64.NO_WRAP),
                DG2 = if (passportData.dg2Raw != null) Base64.encodeToString(passportData.dg2Raw, Base64.NO_WRAP) else "",
                DG15 = if (passportData.dg15Bytes != null) Base64.encodeToString(passportData.dg15Bytes, Base64.NO_WRAP) else "",
                ActiveSig = if (activeSig != null) Base64.encodeToString(activeSig, Base64.NO_WRAP) else "",
                AAChallenge = Base64.encodeToString(passportData.challenge, Base64.NO_WRAP),
                UserPubKey = userPubKey!!,
                Nonce = handshakeNonce ?: "",
                Timestamp = handshakeTimestamp,
                NonceSignature = handshakeSignature ?: "",
                // Kimlik yüzü ayrı gönderilmez — enclave ham DG2'den çıkarır (Dg2FaceExtractor).
                LivenessVideo = "",
                ZoomVideo = "",
                UserSelfie = userSelfieBase64,
                IntegrityToken = integrityToken,
                AntiSpoofCrop = antiSpoofCropBase64
            )

            register(context, payload)
        } catch (e: Exception) {
            _uiEvent.postValue(UiEvent.Toast("${str(R.string.error_data_prefix)}${e.message}"))
        }
    }

    suspend fun register(
        context: Context,
        payload: SecurePayload
    ) {
        log("Step 3: Encrypting & Registering...")
        val payloadJson = gson.toJson(payload)

        val (aesBlob, aesKey, _) = CryptoUtils.aesEncrypt(payloadJson)
        val encryptedKey = CryptoUtils.rsaEncrypt(aesKey, enclavePubKey!!)

        val req = RegistrationRequest(
            encryptedKey = encryptedKey,
            aesBlob = aesBlob,
            countryIsoCode = pendingPassportData?.dg1?.mrzInfo?.issuingState ?: ""
        )
        com.verifyblind.mobile.util.FlowTelemetry.reached(
            com.verifyblind.mobile.util.FlowTelemetry.STEP_SUBMIT, handshakeNonce)
        val res = RetrofitClient.api.register(req)
        if (res.isSuccessful && res.body() != null) {
            log("Register Request Sent. Processing Ticket...")

            try {
                val body = res.body()!!
                pendingRegistrationNonce = body.registrationNonce
                com.verifyblind.mobile.util.FlowTelemetry.reached(
                    com.verifyblind.mobile.util.FlowTelemetry.STEP_SUCCESS, handshakeNonce)
                val hybridJsonStr = body.encryptedTicket
                val hybridObj = gson.fromJson(hybridJsonStr, HybridContent::class.java)

                isCryptoOperationActive = true
                _uiEvent.postValue(UiEvent.RequestBiometricDecrypt(hybridObj.encKey, "register", hybridObj))
            } catch (e: Exception) {
                val errMsg = e.message ?: "unknown"
                log("Ticket Save/Decrypt Failed: $errMsg")
                _uiEvent.postValue(UiEvent.ShowMessage(str(R.string.registration_error_title), errMsg))
            }
        } else {
            val errBody = res.errorBody()?.string()
            val parsedError = rateLimitMessageOrNull(res)
                ?: friendlyApiError(res.code(), errBody, str(R.string.error_registration_server))
            log("Register Error: ${res.code()} $parsedError")
            // Kayıt başarısızlığı Sentry'de görünür olmalı (eski hâli yalnız logcat'e gidiyordu).
            // PII'siz: yalnız HTTP status + enclave hata kodu (ör. ERR_ACTIVE_AUTH) — kullanıcı metni/değer GÖNDERİLMEZ.
            AppLog.warning("Kayıt reddedildi: HTTP ${res.code()} kod=${errorCodeOf(errBody) ?: "?"}", "Register")
            _uiEvent.postValue(UiEvent.RegistrationFailed(parsedError))
        }
    }

    suspend fun completeRegistration(
        context: Context,
        aesKeyDec: String,
        hybridObj: HybridContent,
        historyRepository: com.verifyblind.mobile.data.HistoryRepository
    ) {
        try {
            val bundledJson = CryptoUtils.aesDecrypt(hybridObj.blob.trim(), aesKeyDec.trim())
            val unifiedPayload = gson.fromJson(bundledJson, UnifiedRegistrationPayload::class.java)

            // Ticket'ı RAW sub-JSON olarak çıkar (iOS extractRawTicket paritesi). gson.toJson(typed) ile
            // yeniden serialize edersek TicketPayload modelinde OLMAYAN alanlar (ör. SignedAtUnix) DÜŞER →
            // enclave login'de MAC'i kendi sırasıyla yeniden hesaplarken imza uyuşmaz, login HEP patlar.
            val plainTicketJson = com.google.gson.JsonParser.parseString(bundledJson)
                .asJsonObject.getAsJsonObject("ticket").toString()
            log("Ticket Decrypted & Stored! Registration Complete.")

            try {
                saveTicket(plainTicketJson, userPubKey!!)
                val expiryRaw = unifiedPayload.ticket.Payload.GecerlilikTarihi
                if (expiryRaw.isNotBlank()) {
                    val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("expiry_date", expiryRaw).apply()
                }
                // saveTicket disk'e HybridContent yazar; in-memory state'i de aynı formata getir.
                // Aksi halde kayıt hemen ardından login deniyorsa signedTicketJson cleartext kalır
                // ve `gson.fromJson(signedTicketJson, HybridContent::class.java)` patlar.
                loadTicket()
            } catch (e: Exception) {
                log("saveTicket failed: ${e.message}")
                throw e
            }

            val pid = unifiedPayload.personId.trim()
            val cid = unifiedPayload.cardId.trim()

            val tckn = pendingPassportData?.dg1?.mrzInfo?.personalNumber ?: "00000000000"

            // Y-8(b): SHA256(TCKN) fallback KALDIRILDI — brute-force'lanabilir bir personId üretiyordu
            // (backup anahtarı = SHA256(SHA256(TCKN)) → kırılabilir). Gerçek personId/cardId yalnızca
            // enclave'den gelir (yüksek entropi). Sunucu döndürmediyse zayıf yerel kimlik ÜRETME;
            // kaydı durdur — aşağıdaki catch kullanıcıya hata gösterir, finally bayrakları sıfırlar.
            if (pid.isEmpty() || cid.isEmpty()) {
                throw IllegalStateException("Enclave personId/cardId missing — registration cannot complete securely.")
            }

            try {
                com.verifyblind.mobile.util.SecureStore.saveIds(context, pid, cid)
            } catch (e: Exception) {
                log("SecureStore.saveIds failed: ${e.message}")
                throw e
            }

            _isAuthenticated.postValue(true)

            val regNonce = pendingRegistrationNonce ?: java.util.UUID.randomUUID().toString()
            pendingRegistrationNonce = null
            try {
                historyRepository.insert(
                    title = str(R.string.history_card_added_title),
                    description = str(R.string.history_tckn_prefix) + mask(tckn),
                    status = 1,
                    actionType = com.verifyblind.mobile.data.HistoryAction.REGISTRATION,
                    nonce = regNonce,
                    personId = pid,
                    cardId = cid
                )
            } catch (e: Exception) {
                log("historyRepository.insert failed: ${e.message}")
                throw e
            }

            _uiEvent.postValue(UiEvent.RegistrationSuccess)
        } catch (e: Exception) {
            val errMsg = e.message ?: "unknown"
            log("Ticket Save/Decrypt Failed: $errMsg")
            // Kayıt tamamlama (biyometrik çözme / ticket+id kaydetme) başarısızlığı GERÇEK arıza →
            // Sentry ERROR (stacktrace'li). Sabit mesaj + throwable; errMsg mesaja KONMAZ (PII güvenliği).
            AppLog.error("Kayıt tamamlanamadı (ticket çözme/kaydetme)", "Register", e)
            _uiEvent.postValue(UiEvent.ShowMessage(str(R.string.registration_error_title), errMsg))
        } finally {
            isCryptoOperationActive = false
            isNfcOperationActive = false
        }
    }

    // ──────────────────────── Login ────────────────────────

    suspend fun performLoginWithQr(
        context: Context,
        nonce: String,
        pkHash: String?,
        partnerName: String? = null,
        fromDeepLink: Boolean = false,
        historyRepository: com.verifyblind.mobile.data.HistoryRepository,
        partnerId: String? = null,
        scopes: List<String>? = null
    ) {
        if (signedTicketJson == null) {
            _uiEvent.postValue(UiEvent.Toast(str(R.string.error_ticket_not_found)))
            return
        }

        try {
            log("Logging in with nonce: $nonce")

            val hybridContent = gson.fromJson(signedTicketJson!!, HybridContent::class.java)

            // Request biometric decrypt for login
            _uiEvent.postValue(UiEvent.RequestBiometricDecrypt(
                hybridContent.encKey,
                "login",
                hybridContent,
                LoginContext(nonce, pkHash, partnerName, fromDeepLink, partnerId, scopes)
            ))
        } catch (e: Exception) {
            AppLog.error("Giriş Sistem Hatası: ${e.message}", throwable = e)
            val errorTitle = if (e is java.io.IOException) str(R.string.error_network_title) else str(R.string.error_system_title)
            val errorDetail = e.message ?: e.javaClass.simpleName
            _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, str(R.string.error_data_prefix) + errorDetail, fromDeepLink))
        }
    }

    suspend fun completeLogin(
        context: Context,
        aesKey: String,
        hybridContent: HybridContent,
        loginContext: LoginContext,
        historyRepository: com.verifyblind.mobile.data.HistoryRepository,
        userSignature: String,
        userSigTs: Long
    ) {
        try {
            val plainTicketJson = CryptoUtils.aesDecrypt(hybridContent.blob, aesKey)

            val signedTicket = gson.fromJson(plainTicketJson, com.google.gson.JsonElement::class.java)
            val wrapper = com.google.gson.JsonObject().apply {
                add("signed_ticket", signedTicket)
                addProperty("nonce", loginContext.nonce)
                if (loginContext.pkHash != null) addProperty("pk_hash", loginContext.pkHash)
            }
            val wrapperJson = gson.toJson(wrapper)

            val (lAesBlob, lAesKey, _) = CryptoUtils.aesEncrypt(wrapperJson)

            if (!isLoginHandshakeSuccessful) {
                ensureLoginHandshake(context)
            }
            // HARDENING (invariant): taze verified login-handshake yoksa GÖNDERME. Verification hatası
            // performLoginHandshake'te zaten CriticalError→app-close verir; buraya yalnız ağ/refresh
            // başarısızlığı düşer → bağlantı mesajıyla dur, ticket'i asla bayat/eksik anahtarla yollama.
            if (!isLoginHandshakeSuccessful || enclavePubKey == null) {
                _uiEvent.postValue(UiEvent.ShowMessageAndFinish(
                    str(R.string.connection_error_title),
                    lastLoginHandshakeError ?: str(R.string.handshake_error_generic),
                    loginContext.fromDeepLink))
                return
            }

            val lEncKey = CryptoUtils.rsaEncrypt(lAesKey, enclavePubKey!!)
            val hybridTicket = HybridContent(lEncKey, lAesBlob)
            val encrTicketStr = gson.toJson(hybridTicket)

            var integrityToken = ""
            try {
                _uiEvent.postValue(UiEvent.UpdateProcessingStatus(str(R.string.processing_device_security)))
                integrityToken = IntegrityManagerHelper.requestIntegrityToken(context, loginContext.nonce) ?: ""
            } catch (e: Exception) {
                log("Play Integrity fetch error during login: ${e.message}")
            }

            val req = LoginRequest(
                encrSignedTicket = encrTicketStr,
                nonce = loginContext.nonce,
                integrityToken = integrityToken,
                userSignature = userSignature,
                userSigTs = userSigTs
            )

            val res = RetrofitClient.api.login(req)
            if (res.isSuccessful) {
                val pid = com.verifyblind.mobile.util.SecureStore.getPersonId(context) ?: ""
                val cid = com.verifyblind.mobile.util.SecureStore.getCardId(context) ?: ""

                val partnerHistoryId: String? = loginContext.partnerId?.also { pId ->
                    // Only save partner if not already in cache (fetchPartnerInfo already saved it with logo)
                    if (com.verifyblind.mobile.data.PartnerManager.getPartner(pId) == null) {
                        com.verifyblind.mobile.data.PartnerManager.savePartner(
                            com.verifyblind.mobile.data.PartnerItem(pId, loginContext.partnerName ?: "", null, null, System.currentTimeMillis())
                        )
                    }
                }

                historyRepository.insert(
                    title = str(R.string.history_identity_shared_title),
                    description = if (loginContext.partnerName != null) str(R.string.history_partner_prefix) + loginContext.partnerName else str(R.string.history_qr_login),
                    status = 1,
                    actionType = com.verifyblind.mobile.data.HistoryAction.SHARED_IDENTITY,
                    nonce = loginContext.nonce,
                    personId = pid,
                    cardId = cid,
                    partnerId = partnerHistoryId
                )

                _uiEvent.postValue(UiEvent.LoginSuccess(loginContext.fromDeepLink))
            } else {
                val errBody = res.errorBody()?.string()
                val errCode = errorCodeOf(errBody)
                val parsedError = friendlyApiError(res.code(), errBody, "Hata: ${res.code()}")
                log("Login Failed: ${res.code()} - $parsedError")
                // Giriş reddi Sentry'de görünür olmalı (eski hâli yalnız logcat'e gidiyordu).
                // PII'siz: yalnız HTTP status + hata kodu (ör. ERR_TICKET_REVOKED, ERR_HOLDER_OF_KEY).
                AppLog.warning("Giriş reddedildi: HTTP ${res.code()} kod=${errCode ?: "?"}", "Login")
                if (errCode == "ERR_TICKET_REVOKED") {
                    // Ticket sunucu tarafında iptal edildi → yerel kaydı sil, kullanıcıyı yeniden kayda yönlendir.
                    clearTicket()
                    _uiEvent.postValue(UiEvent.TicketRevoked(parsedError, loginContext.fromDeepLink))
                } else {
                    _uiEvent.postValue(UiEvent.ShowMessageAndFinish(str(R.string.login_failed_title), parsedError, loginContext.fromDeepLink))
                }
            }
        } catch (e: Exception) {
            AppLog.error("Giriş Sistem Hatası: ${e.message}", throwable = e)
            val errorTitle = if (e is java.io.IOException) str(R.string.error_network_title) else str(R.string.error_system_title)
            val errorDetail = e.message ?: e.javaClass.simpleName
            _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, str(R.string.error_data_prefix) + errorDetail, loginContext.fromDeepLink))
        }
    }

    fun handleLoginKeystoreError(context: Context, fromDeepLink: Boolean) {
        _uiEvent.postValue(UiEvent.LoginKeystoreError(fromDeepLink))
    }

    // ──────────────────────── Partner Info ────────────────────────

    fun fetchPartnerInfo(context: Context, nonce: String, pkHash: String?, fromDeepLink: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // Kart yoksa partner API'sine hiç gitme — kullanıcıya doğrudan "kart ekle" mesajı göster.
            // Aksi halde QR süresi dolmuş gibi yanlış yönlendiren hatalar görülebiliyor.
            if (signedTicketJson == null) {
                // Partnere "no_card_registered" sebebi gönderilir; widget bunu UI'ında gösterebilir.
                try {
                    val cancelResp = RetrofitClient.api.cancelPop(PopCancelRequest(nonce, reason = "no_card_registered"))
                    if (!cancelResp.isSuccessful) {
                        log("cancelPop başarısız: HTTP ${cancelResp.code()} — partner sorgulamaya devam edebilir")
                    }
                } catch (e: Exception) {
                    log("cancelPop ağ hatası: ${e.message} — partner sorgulamaya devam edebilir")
                }
                _uiEvent.postValue(UiEvent.ShowMessageAndFinish(
                    str(R.string.error_no_card_title),
                    str(R.string.error_no_card_message),
                    fromDeepLink
                ))
                return@launch
            }
            try {
                val token = try { IntegrityManagerHelper.requestIntegrityToken(context, nonce) } catch (e: Exception) { null }
                val res = RetrofitClient.api.getPartnerInfo(nonce, token)

                if (res.isSuccessful && res.body() != null) {
                    val info = res.body()!!
                    var logoBitmap: android.graphics.Bitmap? = null
                    val finalLogoBase64: String? = info.logoBase64

                    if (finalLogoBase64 != null) {
                        try {
                            val bytes = Base64.decode(finalLogoBase64, Base64.DEFAULT)
                            logoBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { }
                    }

                    val pName = info.name.trim()
                    val pId = info.partnerId
                    com.verifyblind.mobile.data.PartnerManager.savePartner(
                        com.verifyblind.mobile.data.PartnerItem(pId, pName, info.logoUrl, finalLogoBase64, System.currentTimeMillis())
                    )

                    // App-return doğrulaması için partner'ın kayıtlı return şemasını sakla (yalnız deeplink akışında kullanılır).
                    partnerAppReturnScheme = info.appReturnScheme

                    _uiEvent.postValue(UiEvent.ShowConsentDialog(info, logoBitmap, nonce, pkHash, fromDeepLink))
                } else {
                    val errBody = res.errorBody()?.string()
                    val parsedError = friendlyApiError(res.code(), errBody, str(R.string.error_partner_title))
                    log("Partner Hatası: ${res.code()} - $parsedError")
                    _uiEvent.postValue(UiEvent.ShowMessageAndFinish(str(R.string.error_partner_title), parsedError, fromDeepLink))
                }
            } catch (e: Exception) {
                AppLog.error("Partner Getirme Sistem Hatası: ${e.message}", throwable = e)
                val errorTitle = if (e is java.io.IOException) str(R.string.error_network_title) else str(R.string.error_system_title)
                val errorDetail = e.message ?: e.javaClass.simpleName
                _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, str(R.string.error_data_prefix) + errorDetail, fromDeepLink))
            }
        }
    }

    // ──────────────────────── App Update ────────────────────────

    /**
     * Returns true if the installed APK is older than MINIMUM_ANDROID_VERSION.
     * Caller MUST bail out when true — the force-update dialog will be posted.
     * On network error returns false (fail-open): server is unreachable, so we
     * cannot prove the client is outdated; subsequent API calls will fail anyway.
     */
    suspend fun isAppOutdated(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.api.getAppConfig()
            if (!response.isSuccessful || response.body() == null) return@withContext false
            val config = response.body()!!
            val prevDemo = demoEnabled
            val currentVersion = com.verifyblind.mobile.BuildConfig.VERSION_NAME
            // Demo butonu: cihaz sürümü admin tanımlı demo sürümüyle birebir eşleşirse görünür.
            demoEnabled = config.demoVersionAndroid.isNotEmpty() && config.demoVersionAndroid == currentVersion
            if (demoEnabled != prevDemo) _uiEvent.postValue(UiEvent.ConfigLoaded)

            // Hukuki metinler güncellendiyse yeniden onay iste. Sunucu sürümü YALNIZCA yükseltir;
            // ayar boş veya bozuksa gömülü taban geçerli kalır, yani bu yol kapıyı gevşetemez.
            val requiredLegal = LegalTerms.requiredVersion(config.legalTermsVersion)
            if (LegalTerms.needsAcceptance(LegalTerms.acceptedVersion(getApplication()), requiredLegal)) {
                _uiEvent.postValue(UiEvent.LegalTermsUpdated(requiredLegal))
            }
            if (isVersionOlder(currentVersion, config.minimumAndroidVersion)) {
                _uiEvent.postValue(UiEvent.ForceUpdate(config.storeUrl))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLog.warning("Güncelleme kontrolü başarısız: ${e.message}", throwable = e)
            false
        }
    }

    /** Fire-and-forget wrapper for lifecycle callbacks (onResume). */
    fun checkAppUpdate() {
        viewModelScope.launch { isAppOutdated() }
    }

    fun isVersionOlder(current: String, minimum: String): Boolean {
        val currParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val minParts = minimum.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(currParts.size, minParts.size)
        for (i in 0 until length) {
            val c = currParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (c < m) return true
            if (c > m) return false
        }
        return false
    }

    // ──────────────────────── Event Consumed ────────────────────────

    fun onEventConsumed() {
        _uiEvent.value = null
    }

    // ──────────────────────── Helpers ────────────────────────

    /**
     * 429 ise sunucunun Retry-After header'ından lokalize "X sonra tekrar deneyin" mesajı üretir;
     * 429 değilse null döner (çağıran parseApiError fallback'ine düşer).
     */
    private fun rateLimitMessageOrNull(res: retrofit2.Response<*>): String? {
        if (res.code() != 429) return null
        val retryAfter = res.headers()["Retry-After"]?.toIntOrNull()
        return when {
            retryAfter == null || retryAfter <= 0 -> str(R.string.error_rate_limited_generic)
            retryAfter >= 60 -> str(R.string.error_rate_limited_minutes, (retryAfter + 59) / 60) // ceil → dakika
            else -> str(R.string.error_rate_limited_seconds, retryAfter)
        }
    }

    /**
     * HTTP hata yanıtını kullanıcı dostu mesaja çevirir. 5xx = sunucu/altyapı tarafı; kullanıcının
     * KENDİ bağlantı sorunu 5xx ÜRETMEZ (o yol exception/timeout olarak ayrı yakalanır) → sabit, nazik
     * "geçici sorun" mesajı. 4xx ve diğerleri → sunucunun döndürdüğü detayı taşıyan parseApiError.
     */
    fun friendlyApiError(statusCode: Int, errorBody: String?, fallbackMsg: String): String =
        com.verifyblind.mobile.util.ServerErrorMessages.serverErrorOrNull(getApplication<Application>(), statusCode)
            ?: parseApiError(errorBody, fallbackMsg)

    fun parseApiError(errorBody: String?, fallbackMsg: String): String {
        if (errorBody.isNullOrBlank()) return fallbackMsg
        try {
            val jsonObject = gson.fromJson(errorBody, com.google.gson.JsonObject::class.java)
            val sb = StringBuilder()

            if (jsonObject.has("error")) {
                val errNode = jsonObject.get("error")
                sb.append(if (errNode.isJsonPrimitive) errNode.asString else errNode.toString())
            }
            if (jsonObject.has("details")) {
                val detailsNode = jsonObject.get("details")
                if (sb.isNotEmpty()) sb.append("\n\nDetaylar: ")
                sb.append(if (detailsNode.isJsonPrimitive) detailsNode.asString else detailsNode.toString())
            }

            if (sb.isNotEmpty()) return sb.toString()
        } catch (e: Exception) {
            if (errorBody.length < 500 && !errorBody.contains("<html", ignoreCase = true)) {
                return errorBody.trim()
            }
        }
        return fallbackMsg
    }

    /**
     * Hata gövdesinden hata kodunu çıkarır (ör. ERR_TICKET_REVOKED, ERR_ACTIVE_AUTH). Yoksa null.
     * Önce "error_code" (login/revoke yolu), sonra "code" (register/relay ContentResult yolu) denenir.
     */
    fun errorCodeOf(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val obj = gson.fromJson(errorBody, com.google.gson.JsonObject::class.java)
            when {
                obj.has("error_code") && obj.get("error_code").isJsonPrimitive -> obj.get("error_code").asString
                obj.has("code") && obj.get("code").isJsonPrimitive -> obj.get("code").asString
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun log(msg: String) {
        Log.d("VerifyBlind", msg)
    }

    fun mask(value: String?): String {
        if (value == null || value.isEmpty()) return ""
        if (value.length <= 4) return "**" + value.length + "**"
        return value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2)
    }

    // ──────────────────────── Event Types ────────────────────────

    sealed class UiEvent {
        data class Toast(val message: String) : UiEvent()
        data class ShowMessage(val title: String, val message: String) : UiEvent()
        /**
         * Ekran kilidi yok — mesajın yanında kurulum ekranını AÇAN bir buton gerekir.
         * Ayrı event, çünkü intent'i yalnızca Activity başlatabilir.
         */
        data class ShowDeviceLockRequired(val title: String, val message: String) : UiEvent()
        data class ShowMessageAndFinish(val title: String, val message: String, val isDeepLink: Boolean) : UiEvent()
        data class CriticalError(val title: String, val message: String) : UiEvent()
        data class ForceUpdate(val storeUrl: String) : UiEvent()

        /** Sunucudaki hukuki metin sürümü cihazdakinden yeni → yeniden onay ekranı açılmalı. */
        data class LegalTermsUpdated(val requiredVersion: String) : UiEvent()

        data class ShowConsentDialog(
            val info: PartnerInfoResponse,
            val logo: android.graphics.Bitmap?,
            val nonce: String,
            val pkHash: String?,
            val fromDeepLink: Boolean
        ) : UiEvent()

        data class RequestBiometricDecrypt(
            val cipherText: String,
            val flow: String, // "register" or "login"
            val hybridObj: HybridContent,
            val loginContext: LoginContext? = null
        ) : UiEvent()

        data class UpdateProcessingStatus(val status: String) : UiEvent()

        object ConfigLoaded : UiEvent()
        object RegistrationSuccess : UiEvent()
        data class RegistrationFailed(val error: String) : UiEvent()

        data class LoginSuccess(val fromDeepLink: Boolean) : UiEvent()
        data class LoginKeystoreError(val fromDeepLink: Boolean) : UiEvent()
        /** Ticket admin iptal kuralıyla reddedildi (ERR_TICKET_REVOKED) — kimliği yeniden eklemeli. */
        data class TicketRevoked(val message: String, val fromDeepLink: Boolean) : UiEvent()
    }

    data class LoginContext(
        val nonce: String,
        val pkHash: String?,
        val partnerName: String?,
        val fromDeepLink: Boolean,
        val partnerId: String? = null,
        val scopes: List<String>? = null
    )

    // ──────────────────────── Demo Mode ────────────────────────

    /**
     * Demo kayıt — enclave'in /demo-register endpoint'inden gerçek imzalı bir ticket alır.
     * Yanıt formatı normal /register ile aynıdır; aynı biyometrik decrypt + completeRegistration
     * akışına bağlanır. Kayıt sonrası normal login akışıyla giriş yapılır — özel demo bypass yok.
     */
    suspend fun completeDemoRegistration(context: Context) {
        try {
            log("Demo Step 3: Demo Registration via Enclave...")

            if (userPubKey.isNullOrEmpty()) {
                _uiEvent.postValue(UiEvent.ShowMessage(str(R.string.error_demo_registration_title), str(R.string.error_demo_key_not_ready)))
                isNfcOperationActive = false
                return
            }

            val req = DemoRegisterRequest(userPubKey = userPubKey!!, appVersion = com.verifyblind.mobile.BuildConfig.VERSION_NAME)
            val res = RetrofitClient.api.demoRegister(req)
            if (res.isSuccessful && res.body() != null) {
                log("Demo Register Request Sent. Processing Ticket...")
                try {
                    val hybridJsonStr = res.body()!!.encryptedTicket
                    val hybridObj = gson.fromJson(hybridJsonStr, HybridContent::class.java)

                    isCryptoOperationActive = true
                    _uiEvent.postValue(UiEvent.RequestBiometricDecrypt(hybridObj.encKey, "register", hybridObj))
                } catch (e: Exception) {
                    log("Demo Ticket Parse Failed: ${e.message}")
                    _uiEvent.postValue(UiEvent.ShowMessage(str(R.string.error_demo_registration_title), e.message ?: str(R.string.error_unknown)))
                    isNfcOperationActive = false
                }
            } else {
                val errBody = res.errorBody()?.string()
                val parsedError = rateLimitMessageOrNull(res)
                    ?: friendlyApiError(res.code(), errBody, str(R.string.error_demo_server))
                log("Demo Register Error: ${res.code()} $parsedError")
                _uiEvent.postValue(UiEvent.RegistrationFailed(parsedError))
                isNfcOperationActive = false
            }
        } catch (e: Exception) {
            log("Demo Registration Error: ${e.message}")
            _uiEvent.postValue(UiEvent.ShowMessage(str(R.string.error_demo_registration_title), e.message ?: str(R.string.error_unknown)))
            isNfcOperationActive = false
        }
    }

    suspend fun cancelQrNonce(nonce: String) {
        try {
            RetrofitClient.api.cancelPop(PopCancelRequest(nonce))
            log("QR işlemi iptal bildirildi: $nonce")
        } catch (e: Exception) {
            log("QR iptal bildirimi başarısız (kritik değil): ${e.message}")
        }
    }
}
