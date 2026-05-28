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
import com.verifyblind.mobile.api.*
import com.verifyblind.mobile.crypto.CryptoUtils
import com.verifyblind.mobile.nfc.PassportReader
import com.verifyblind.mobile.util.IntegrityManagerHelper
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

    // ──────────────────────── State ────────────────────────

    // Handshake
    var enclavePubKey: String? = null
        private set
    var handshakeNonce: String? = null
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

    // User / Ticket
    var userPubKey: String? = null
        private set
    var signedTicketJson: String? = null
        private set

    // Deep Link
    var isDeepLinkFlow = false

    // Demo Mode
    var isDemoMode = false
    var demoEnabled = false

    // Biometrics / Registration
    var userSelfiePath: String? = null
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
        try {
            userPubKey = CryptoUtils.ensureKeyExists()
        } catch (e: Exception) {
            Log.e("VerifyBlind", "Keystore Hatası: ${e.message}")
        }
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
                val body = res.body()!!
                log("RAW Handshake Response: ${gson.toJson(body)}")

                val prefs = getApplication<Application>().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)

                val serverKey: String
                if (BuildConfig.USE_LOCAL_API) {
                    // Local dev mode: no real Nitro Enclave — skip attestation, use key directly
                    serverKey = body.enclavePubKey ?: run {
                        log("❌ CRITICAL: No enclave_pub_key in handshake response (local mode)!")
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Sunucudan enclave anahtarı alınamadı."))
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
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Donanım doğrulama belgesi eksik! Sunucu güvenli değil."))
                        return@withContext
                    }

                    log("Verifying Hardware Attestation Document (AWS Nitro)...")

                    val configRes = try { RetrofitClient.api.getAppConfig() } catch (e: Exception) { null }
                    val isDev = configRes?.body()?.environment == "Development"

                    val attestResult = com.verifyblind.mobile.crypto.AttestationVerifier.verify(
                        attestationBase64 = attestDoc,
                        pcr0Signature = body.pcr0Signature,
                        isServerDevelopment = isDev
                    )

                    if (!attestResult.isValid) {
                        log("❌ HARDWARE ATTESTATION FAILED: ${attestResult.failReason}")
                        _uiEvent.postValue(UiEvent.CriticalError("Donanım Doğrulama Hatası", attestResult.failReason ?: "Bağlantı güvenli değil."))
                        return@withContext
                    }

                    serverKey = attestResult.enclavePubKey ?: run {
                        log("❌ CRITICAL: Could not extract Enclave Pub Key from Attestation!")
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Donanım belgesinden anahtar okunamadı!"))
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
                handshakeTimestamp = body.timestamp
                handshakeSignature = body.nonceSignature
                livenessChallenges = body.challenges

                _isHandshakeSuccessful = true
                handshakeCompletedAt = System.currentTimeMillis()
                log("Handshake Success! Nonce: ${mask(handshakeNonce)}, Timestamp: $handshakeTimestamp")
            } else {
                val errBody = res.errorBody()?.string()
                val parsedError = parseApiError(errBody, "Bağlantı Hatası: ${res.code()}")
                lastHandshakeError = parsedError
                log("Handshake Failed: ${res.code()} - $parsedError")
            }
        } catch (e: Exception) {
            lastHandshakeError = if (!e.message.isNullOrBlank()) e.message else "Ağ veya zaman aşımı hatası (${e.javaClass.simpleName})"
            log("Handshake Error: ${e.message}")
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
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Sunucudan enclave anahtarı alınamadı."))
                        return@withContext
                    }
                    log("⚠️ LOCAL API MODE: Login-handshake attestation skipped.")
                } else {
                    val attestDoc = body.attestationDocument
                    if (attestDoc.isNullOrBlank()) {
                        log("❌ CRITICAL: No attestation document in login-handshake response!")
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Donanım doğrulama belgesi eksik!"))
                        return@withContext
                    }

                    val attestResult = com.verifyblind.mobile.crypto.AttestationVerifier.verify(
                        attestationBase64 = attestDoc,
                        pcr0Signature = body.pcr0Signature,
                        isServerDevelopment = try { RetrofitClient.api.getAppConfig().body()?.environment == "Development" } catch (e: Exception) { false }
                    )

                    if (!attestResult.isValid) {
                        log("❌ LOGIN HANDSHAKE ATTESTATION FAILED: ${attestResult.failReason}")
                        _uiEvent.postValue(UiEvent.CriticalError("Donanım Doğrulama Hatası", attestResult.failReason ?: "Bağlantı güvenli değil."))
                        return@withContext
                    }

                    enclavePubKey = attestResult.enclavePubKey ?: run {
                        log("❌ CRITICAL: Could not extract Enclave Pub Key from login-handshake attestation!")
                        _uiEvent.postValue(UiEvent.CriticalError("Güvenlik Hatası", "Donanım belgesinden anahtar okunamadı!"))
                        return@withContext
                    }
                }

                _isLoginHandshakeSuccessful = true
                loginHandshakeCompletedAt = System.currentTimeMillis()
                lastLoginHandshakeError = null
                log("Login Handshake Success!")
            } else {
                val errBody = res.errorBody()?.string()
                lastLoginHandshakeError = parseApiError(errBody, "Bağlantı Hatası: ${res.code()}")
                log("Login Handshake Failed: ${res.code()} - $lastLoginHandshakeError")
            }
        } catch (e: Exception) {
            lastLoginHandshakeError = if (!e.message.isNullOrBlank()) e.message else "Ağ veya zaman aşımı hatası (${e.javaClass.simpleName})"
            log("Login Handshake Error: ${e.message}")
        } finally {
            isLoginHandshaking = false
        }
    }

    suspend fun ensureLoginHandshake(context: Context) = withContext(Dispatchers.IO) {
        if (isLoginHandshakeSuccessful) return@withContext
        if (!isLoginHandshaking) performLoginHandshake(context)
        while (isLoginHandshaking) delay(100)
    }

    fun getHandshakeErrorMessage(): Pair<String, String> {
        val message = if (!lastHandshakeError.isNullOrBlank()) {
            "$lastHandshakeError\n\nLütfen işleminize devam edebilmek için bağlantınızı veya cihazınızı kontrol edip tekrar deneyin."
        } else {
            "Güvenli sunucu bağlantısı kurulamadı. Bu durum şunlardan kaynaklanabilir:\n\n" +
                "• İnternet bağlantınız kısıtlı olabilir.\n" +
                "• Sunucumuz şu anda bakımda olabilir.\n\n" +
                "Lütfen internetinizi kontrol edin ve yeniden deneyin."
        }

        val title = if (lastHandshakeError?.contains("Güvenlik", ignoreCase = true) == true ||
            lastHandshakeError?.contains("Integrity", ignoreCase = true) == true
        ) {
            "Güvenlik Engeli"
        } else {
            "Bağlantı Hatası"
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
            if (enclavePubKey == null) throw Exception("Enclave Public Key is missing!")

            val sodBytes = passportData.sod.encoded
            val dg1Bytes = passportData.dg1Raw
            val faceBytes = passportData.faceImage
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

            var integrityToken = ""
            if (handshakeNonce != null) {
                log("Fetching Play Integrity Token...")
                onStatusUpdate("Güvenlik Kontrolü Yapılıyor...")
                integrityToken = IntegrityManagerHelper.requestIntegrityToken(context, handshakeNonce!!) ?: ""
                log("Integrity Token Fetched: ${if (integrityToken.isNotEmpty()) "OK" else "FAIL"}")
            }

            val payload = SecurePayload(
                SOD = Base64.encodeToString(sodBytes, Base64.NO_WRAP),
                DG1 = Base64.encodeToString(dg1Bytes, Base64.NO_WRAP),
                DG15 = if (passportData.dg15Bytes != null) Base64.encodeToString(passportData.dg15Bytes, Base64.NO_WRAP) else "",
                ActiveSig = if (activeSig != null) Base64.encodeToString(activeSig, Base64.NO_WRAP) else "",
                AAChallenge = Base64.encodeToString(passportData.challenge, Base64.NO_WRAP),
                UserPubKey = userPubKey!!,
                Nonce = handshakeNonce ?: "",
                Timestamp = handshakeTimestamp,
                NonceSignature = handshakeSignature ?: "",
                DG2_Photo = if (faceBytes != null) Base64.encodeToString(faceBytes, Base64.NO_WRAP) else "",
                LivenessVideo = "",
                ZoomVideo = "",
                UserSelfie = userSelfieBase64,
                IntegrityToken = integrityToken
            )

            // ── Load Test Dump ──────────────────────────────────────────
            // NFC ham verilerini ve selfie'yi telefona kaydeder.
            // Dosyalar: Android/data/com.verifyblind.mobile/files/load_test_dump/
            try {
                val dumpDir = java.io.File(context.getExternalFilesDir(null), "load_test_dump")
                dumpDir.mkdirs()
                dumpDir.resolve("sod.bin").writeBytes(sodBytes)
                dumpDir.resolve("dg1.bin").writeBytes(dg1Bytes)
                if (faceBytes != null) dumpDir.resolve("dg2_face.bin").writeBytes(faceBytes)
                if (passportData.dg15Bytes != null) dumpDir.resolve("dg15.bin").writeBytes(passportData.dg15Bytes!!)
                if (activeSig != null) dumpDir.resolve("aa_signature.bin").writeBytes(activeSig)
                dumpDir.resolve("aa_challenge.bin").writeBytes(passportData.challenge)
                if (userSelfiePath != null) {
                    java.io.File(userSelfiePath!!).copyTo(dumpDir.resolve("selfie.jpg"), overwrite = true)
                }
                log("Load Test Dump saved to: ${dumpDir.absolutePath}")
            } catch (e: Exception) {
                log("Load Test Dump Error (non-fatal): ${e.message}")
            }
            // ── End Load Test Dump ──────────────────────────────────────

            register(context, payload)
        } catch (e: Exception) {
            _uiEvent.postValue(UiEvent.Toast("Veri hazırlama hatası: ${e.message}"))
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
        val res = RetrofitClient.api.register(req)
        if (res.isSuccessful && res.body() != null) {
            log("Register Request Sent. Processing Ticket...")

            try {
                val hybridJsonStr = res.body()!!.encryptedTicket
                val hybridObj = gson.fromJson(hybridJsonStr, HybridContent::class.java)

                isCryptoOperationActive = true
                _uiEvent.postValue(UiEvent.RequestBiometricDecrypt(hybridObj.encKey, "register", hybridObj))
            } catch (e: Exception) {
                val errMsg = e.message ?: "unknown"
                log("Ticket Save/Decrypt Failed: $errMsg")
                _uiEvent.postValue(UiEvent.ShowMessage("Kayıt Hatası", errMsg))
            }
        } else {
            val errBody = res.errorBody()?.string()
            val parsedError = parseApiError(errBody, "Kayıt sırasında bir sunucu hatası oluştu.")
            log("Register Error: ${res.code()} $parsedError")
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

            val plainTicketJson = gson.toJson(unifiedPayload.ticket)
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

            var pid = unifiedPayload.personId.trim()
            var cid = unifiedPayload.cardId.trim()

            val tckn = pendingPassportData?.dg1?.mrzInfo?.personalNumber ?: "00000000000"

            if (pid.isEmpty() || cid.isEmpty()) {
                pid = CryptoUtils.sha256(tckn)
                val sodBytes = pendingPassportData?.sod?.encoded ?: ByteArray(0)
                val sodBase64 = Base64.encodeToString(sodBytes, Base64.NO_WRAP)
                cid = CryptoUtils.sha256(sodBase64)
                log("Fallback to local ID generation.")
            }

            try {
                com.verifyblind.mobile.util.SecureStore.saveIds(context, pid, cid)
            } catch (e: Exception) {
                log("SecureStore.saveIds failed: ${e.message}")
                throw e
            }

            _isAuthenticated.postValue(true)

            val regNonce = java.util.UUID.randomUUID().toString()
            try {
                historyRepository.insert(
                    title = "Kimlik Kartı Eklendi",
                    description = "TCKN: " + mask(tckn),
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

            // Background sync
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                com.verifyblind.mobile.backup.SyncManager.performSync(context)
            }

            _uiEvent.postValue(UiEvent.RegistrationSuccess)
        } catch (e: Exception) {
            val errMsg = e.message ?: "unknown"
            log("Ticket Save/Decrypt Failed: $errMsg")
            _uiEvent.postValue(UiEvent.ShowMessage("Kayıt Hatası", errMsg))
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
            _uiEvent.postValue(UiEvent.Toast("Kimlik bileti bulunamadı!"))
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
            log("Giriş Sistem Hatası: ${e.message}")
            e.printStackTrace()
            val errorTitle = if (e is java.io.IOException) "Ağa Bağlanılamadı" else "Sistem Hatası"
            val errorDetail = e.message ?: e.javaClass.simpleName
            _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, "Hata Detayı: $errorDetail", fromDeepLink))
        }
    }

    suspend fun completeLogin(
        context: Context,
        aesKey: String,
        hybridContent: HybridContent,
        loginContext: LoginContext,
        historyRepository: com.verifyblind.mobile.data.HistoryRepository
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

            val lEncKey = CryptoUtils.rsaEncrypt(lAesKey, enclavePubKey!!)
            val hybridTicket = HybridContent(lEncKey, lAesBlob)
            val encrTicketStr = gson.toJson(hybridTicket)

            var integrityToken = ""
            try {
                _uiEvent.postValue(UiEvent.UpdateProcessingStatus("Cihaz Güvenliği Doğrulanıyor..."))
                integrityToken = IntegrityManagerHelper.requestIntegrityToken(context, loginContext.nonce) ?: ""
            } catch (e: Exception) {
                log("Play Integrity fetch error during login: ${e.message}")
            }

            val req = LoginRequest(
                encrSignedTicket = encrTicketStr,
                nonce = loginContext.nonce,
                integrityToken = integrityToken
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
                    title = "Kimlik Paylaşıldı",
                    description = if (loginContext.partnerName != null) "Partner: ${loginContext.partnerName}" else "QR ile Giriş",
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
                val parsedError = parseApiError(errBody, "Hata: ${res.code()}")
                log("Login Failed: ${res.code()} - $parsedError")
                _uiEvent.postValue(UiEvent.ShowMessageAndFinish("Giriş Başarısız", parsedError, loginContext.fromDeepLink))
            }
        } catch (e: Exception) {
            log("Giriş Sistem Hatası: ${e.message}")
            e.printStackTrace()
            val errorTitle = if (e is java.io.IOException) "Ağa Bağlanılamadı" else "Sistem Hatası"
            val errorDetail = e.message ?: e.javaClass.simpleName
            _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, "Hata Detayı: $errorDetail", loginContext.fromDeepLink))
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
                    "Kayıtlı Kart Bulunamadı",
                    "Kimlik doğrulaması yapabilmek için önce VerifyBlind uygulamasına kimlik kartınızı eklemeniz gerekmektedir.",
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

                    _uiEvent.postValue(UiEvent.ShowConsentDialog(info, logoBitmap, nonce, pkHash, fromDeepLink))
                } else {
                    val errBody = res.errorBody()?.string()
                    val parsedError = parseApiError(errBody, "Partner bilgisi alınamadı.")
                    log("Partner Hatası: ${res.code()} - $parsedError")
                    _uiEvent.postValue(UiEvent.ShowMessageAndFinish("Partner Hatası", parsedError, fromDeepLink))
                }
            } catch (e: Exception) {
                log("Partner Getirme Sistem Hatası: ${e.message}")
                e.printStackTrace()
                val errorTitle = if (e is java.io.IOException) "Ağa Bağlanılamadı" else "Sistem Hatası"
                val errorDetail = e.message ?: e.javaClass.simpleName
                _uiEvent.postValue(UiEvent.ShowMessageAndFinish(errorTitle, "Hata Detayı: $errorDetail", fromDeepLink))
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
            val localDemoPassword = com.verifyblind.mobile.BuildConfig.DEMO_PASSWORD
            demoEnabled = localDemoPassword.isNotEmpty() && config.demoPassword == localDemoPassword
            if (demoEnabled != prevDemo) _uiEvent.postValue(UiEvent.ConfigLoaded)
            val currentVersion = com.verifyblind.mobile.BuildConfig.VERSION_NAME
            if (isVersionOlder(currentVersion, config.minimumAndroidVersion)) {
                _uiEvent.postValue(UiEvent.ForceUpdate(config.storeUrl))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("VerifyBlind", "Güncelleme kontrolü başarısız: ${e.message}")
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
        data class ShowMessageAndFinish(val title: String, val message: String, val isDeepLink: Boolean) : UiEvent()
        data class CriticalError(val title: String, val message: String) : UiEvent()
        data class ForceUpdate(val storeUrl: String) : UiEvent()

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
                _uiEvent.postValue(UiEvent.ShowMessage("Demo Kayıt Hatası", "Kullanıcı anahtarı hazır değil."))
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
                    _uiEvent.postValue(UiEvent.ShowMessage("Demo Kayıt Hatası", e.message ?: "Bilinmeyen hata"))
                    isNfcOperationActive = false
                }
            } else {
                val errBody = res.errorBody()?.string()
                val parsedError = parseApiError(errBody, "Demo kayıt sırasında bir sunucu hatası oluştu.")
                log("Demo Register Error: ${res.code()} $parsedError")
                _uiEvent.postValue(UiEvent.RegistrationFailed(parsedError))
                isNfcOperationActive = false
            }
        } catch (e: Exception) {
            log("Demo Registration Error: ${e.message}")
            _uiEvent.postValue(UiEvent.ShowMessage("Demo Kayıt Hatası", e.message ?: "Bilinmeyen hata"))
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
