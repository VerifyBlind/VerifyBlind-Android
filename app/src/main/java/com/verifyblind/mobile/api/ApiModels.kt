package com.verifyblind.mobile.api

import com.google.gson.annotations.SerializedName

// --- Handshake ---
data class HandshakeRequest(
    @SerializedName("integrity_token") val integrityToken: String = "",
    @SerializedName("fcm_token") val fcmToken: String? = null,
    @SerializedName("platform") val platform: String? = "android"
)

data class HandshakeResponse(
@SerializedName("nonce") val nonce: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("nonce_signature") val nonceSignature: String,
    @SerializedName("pcr0_signature") val pcr0Signature: String? = null,
    @SerializedName("attestation_document") val attestationDocument: String? = null,
    @SerializedName("enclave_pub_key") val enclavePubKey: String? = null,
    @SerializedName("challenges") val challenges: List<Int> = emptyList()
)

data class LoginHandshakeResponse(
    @SerializedName("attestation_document") val attestationDocument: String? = null,
    @SerializedName("pcr0_signature") val pcr0Signature: String? = null,
    @SerializedName("enclave_pub_key") val enclavePubKey: String? = null
)

enum class LivenessAction(val value: Int) {
    None(0),
    FaceLeft(1),
    FaceRight(2),
    Blink(3),
    Smile(4);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: None
    }
}

// --- Registration ---
data class SecurePayload(
    val SOD: String,
    val DG1: String,
    val DG2: String = "", // RAW DG2 EF bytes (Base64) — SOD hash binding + biyometrik yüz kaynağı (enclave Dg2FaceExtractor ile çıkarır)
    val DG15: String = "", // AA Public Key (Base64)
    val ActiveSig: String,
    val AAChallenge: String = "", // Challenge used for AA (Base64)
    val UserPubKey: String,
    // Nonce Verification (from Handshake)
    val Nonce: String = "",
    val Timestamp: Long = 0,
    val NonceSignature: String = "",
    // Biometrics (Base64)
    // NOT: Kimlik yüz fotoğrafı ayrı GÖNDERİLMEZ — enclave biyometrik yüzü SOD-doğrulanmış ham DG2'den
    // çıkarır (Dg2FaceExtractor). Eski DG2_Photo alanı belgeye bağlı olmayan görüntüye güvendiği için kaldırıldı.
    val LivenessVideo: String = "",
    val ZoomVideo: String = "",
    val UserSelfie: String = "",
    val IntegrityToken: String = "", // Google Play Integrity
    val AntiSpoofCrop: String = "", // 2.7x wide crop 80x80 JPEG Base64 — MiniFASNetV2
    /**
     * En fazla İKİ aday kare (canlı benzerlik akışı). Doluysa enclave [UserSelfie] /
     * [AntiSpoofCrop] yerine bunları değerlendirir.
     *
     * Sıra: 1 = cihazın en iyi seçtiği kare, 2 = enclave'in canlılık sırasında onayladığı kare
     * (yalnız 1'den FARKLIYSA eklenir). Enclave her adayı normal kapıdan geçirir ve ilk GEÇEN
     * kazanır — "önceden onaylanmış" diye bir kavram yoktur.
     *
     * Her aday KENDİ selfie'si + KENDİ kırpmasıyla bir bütün olarak değerlendirilir: benzerliği
     * bir kareden, canlılığı başkasından almak gerçek bir açıktır.
     */
    val Candidates: List<RegistrationCandidate>? = null
)

/**
 * Final register yükündeki tek aday — kendi selfie'si + kendi 2,7× anti-spoof kırpması.
 * İkisi de AYNI kareden gelmelidir.
 */
data class RegistrationCandidate(
    val Rank: Int,
    val UserSelfie: String,
    val AntiSpoofCrop: String
)

/**
 * Cihazın kare başına ölçtüğü sinyaller — zaten hesaplanıyorlardı ama hiçbir yere
 * gönderilmiyorlardı.
 *
 * ⚠️ Sunucu bu sayılara GÜVENMEZ: aralık kontrolünden geçirir, geçersizse sessizce düşürür.
 * Telemetri asla akışı bozmaz.
 */
data class DeviceFrameMetrics(
    @SerializedName("device_match_score") val deviceMatchScore: Int? = null,
    @SerializedName("luma") val luma: Int? = null,
    @SerializedName("sharpness") val sharpness: Int? = null,
    @SerializedName("quality") val quality: Int? = null,
    @SerializedName("yaw") val yaw: Int? = null,
    @SerializedName("pitch") val pitch: Int? = null,
    @SerializedName("roll") val roll: Int? = null,
    @SerializedName("face_width_ratio") val faceWidthRatio: Int? = null,
    @SerializedName("gesture_count") val gestureCount: Int? = null,
    @SerializedName("wrong_gesture_count") val wrongGestureCount: Int? = null,
    @SerializedName("elapsed_ms") val elapsedMs: Int? = null,
    /**
     * Bu gönderimden önce, oran freni (700ms / uçuştaki istek) yüzünden GÖNDERİLMEDEN elenen
     * iyileşme sayısı. Elenen karenin KENDİSİNİ göndermek veriyi kareyle büyütürdü; bu sayaç,
     * topladığımız dağılımın ne kadar yanlı olduğunu ölçmenin ucuz yolu.
     */
    @SerializedName("skipped_count") val skippedCount: Int? = null,
    /**
     * Yalnız final aday: bu karenin streaming'de gönderildiği `seq`. Hiç gönderilmediyse null.
     * İki aday farklı karelerken "hangi kare hangi karara yol açtı" ancak bununla yanıtlanır.
     */
    @SerializedName("source_seq") val sourceSeq: Int? = null,
    @SerializedName("platform") val platform: String = "android",
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("device_model") val deviceModel: String? = null
)

// --- Canlı benzerlik akışı (streaming) ---
//
// Amaç: cihazdaki 0.65 kapısında düşen deneme bugün enclave'e HİÇ ulaşmıyor, dolayısıyla kaç
// meşru kullanıcıyı hatalı reddettiğimiz ölçülemiyor. Canlılık sürerken enclave'e kare
// göndermek (a) cihaz skoru düşük kalsa bile enclave onayıyla submit açılmasını, (b) her
// denemenin ölçülebilir bir veri noktasına dönüşmesini sağlar.

/** Akış başı: DG2'nin gömme vektörünü enclave RAM'ine aldırır (şifreli — relay göremez). */
data class StreamingPrepareRequest(
    @SerializedName("flow_id") val flowId: String,
    @SerializedName("encrypted_key") val encryptedKey: String,
    @SerializedName("aes_blob") val aesBlob: String
)

/** Şifreli prepare yükü — çipten okunan ham DG2. */
data class StreamingPreparePayload(
    val DG2: String
)

/** Tek kare: selfie + AYNI karenin 2,7× kırpması. */
data class StreamingCheckRequest(
    @SerializedName("flow_id") val flowId: String,
    @SerializedName("encrypted_key") val encryptedKey: String,
    @SerializedName("aes_blob") val aesBlob: String,
    @SerializedName("seq") val seq: Int,
    @SerializedName("device_metrics") val deviceMetrics: DeviceFrameMetrics? = null
)

/** Şifreli kare yükü — selfie ve kırpma açıkta gitmez. */
data class StreamingCheckPayload(
    val UserSelfie: String,
    val AntiSpoofCrop: String
)

/**
 * Kare sonucu. İstemci yalnız [similarityPassed] üzerine karar verir; skorlar teşhis içindir.
 */
data class StreamingCheckResponse(
    @SerializedName("similarity_passed") val similarityPassed: Boolean = false,
    @SerializedName("match_score") val matchScore: Double? = null,
    @SerializedName("p_live") val pLive: Double? = null,
    @SerializedName("outcome") val outcome: String? = null
)

/**
 * Akış bitiş bildirimi.
 *
 * 🔴 [flowOutcome] bu işin varlık sebebi olan vakayı görünür kılar: "enclave geçirirdi ama
 * kullanıcı pes etti". Streaming satırları yazılıyordu ama akışın NASIL bittiği hiçbir yerde
 * yoktu.
 */
data class StreamingReleaseRequest(
    @SerializedName("flow_id") val flowId: String,
    /**
     * Sabit küme (sunucu bilinmeyeni düşürür): submitted | abandoned | timeout_gesture |
     * timeout_session | too_many_errors | match_failed | no_selfie.
     */
    @SerializedName("flow_outcome") val flowOutcome: String? = null
)

data class RegistrationRequest(
    @SerializedName("encrypted_key") val encryptedKey: String,
    @SerializedName("aes_blob") val aesBlob: String,
    @SerializedName("country_iso_code") val countryIsoCode: String = "",
    /**
     * Akış izleme numarası — ölçüm satırlarını canlılık sırasındaki karelerle birleştirir.
     * Şifreli yükün DIŞINDA: relay'in görmesi gerekir, enclave'in bilmesine gerek yoktur.
     * Kimlikle bağ taşımaz.
     */
    @SerializedName("flow_id") val flowId: String? = null,
    /**
     * Adayların cihaz ölçüleri (rank sırasına göre). Şifreli yükün DIŞINDA: relay tabloya
     * yazar. Fotoğrafların KENDİSİ şifreli yükün içindedir — relay onları göremez.
     */
    @SerializedName("candidate_metrics") val candidateMetrics: List<DeviceFrameMetrics>? = null
)

// Demo mode için minimal kayıt isteği — enclave hardcoded veriyle imzalı ticket üretir.
data class DemoRegisterRequest(
    @SerializedName("user_pub_key") val userPubKey: String,
    @SerializedName("app_version") val appVersion: String = "",
    // Relay sürüm kontrolünü Play Store'a yönlendirir.
    @SerializedName("platform") val platform: String = "android"
)

// Hybrid Response from Enclave
data class EncryptedTicketResponse(
    @SerializedName("encrypted_ticket") val encryptedTicket: String, // JSON: { enc_key, blob }
    @SerializedName("registration_nonce") val registrationNonce: String? = null
)
data class HybridContent(
    @SerializedName("enc_key") val encKey: String,
    @SerializedName("blob") val blob: String
)

data class UnifiedRegistrationPayload(
    @SerializedName("ticket") val ticket: SignedTicket,
    @SerializedName("person_id") val personId: String,
    @SerializedName("card_id") val cardId: String
)

data class SignedTicket(
    val Payload: TicketPayload,
    val Signature: String
)
data class TicketPayload(
    val TCKN: String,
    val Ad: String,
    val Soyad: String,
    val DogumTarihi: String = "",
    val SeriNo: String,
    val GecerlilikTarihi: String = "",
    val Cinsiyet: String = "",
    val Uyruk: String = "",
    val UserPubKey: String,
    val CountryIsoCode: String = "",
    val PersonId: String = "",
    val CardId: String = "",
    val DocumentType: String? = null
)

// --- Login ---
data class PartnerRequest(
    @SerializedName("partner_id") val partnerId: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("callback_url") val callbackUrl: String,
    @SerializedName("additional_data") val specialData: com.google.gson.JsonElement?,
    @SerializedName("request_sign") val requestSign: String = "" // Partner's signature
)

data class PartnerQrPayload(
    @SerializedName("request") val request: PartnerRequest,
    @SerializedName("request_sign") val requestSign: String
)

data class LoginRequest(
    @SerializedName("encr_signed_ticket") val encrSignedTicket: String, // Hybrid JSON {enc_key, blob}
    @SerializedName("nonce") val nonce: String, // API-generated GUID from QR
    @SerializedName("integrity_token") val integrityToken: String = "",
    // Holder-of-key kanıtı (Y-4): "VBLOK1|{nonce}|{pk_hash}|{user_sig_ts}" mesajının user key (RSA-PSS/SHA-256) imzası
    @SerializedName("user_signature") val userSignature: String = "",
    @SerializedName("user_sig_ts") val userSigTs: Long = 0
)

data class LoginResponse(
    @SerializedName("encrypted_response") val encryptedResponse: String
)

data class PartnerInfoResponse(
    @SerializedName("partner_id") val partnerId: String,
    @SerializedName("name") val name: String,
    @SerializedName("logo_url") val logoUrl: String,
    @SerializedName("logo_base64") val logoBase64: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("scopes") val scopes: List<String>?,
    @SerializedName("validations") val validations: com.google.gson.JsonElement?,
    // App-to-app deeplink "geri dönüş" için partner'ın kayıtlı return şeması (ör. "verifyblinddemo").
    // null/boş → app-return kapalı; deeplink'teki return URL'i AÇILMAZ (fail-closed).
    @SerializedName("app_return_scheme") val appReturnScheme: String? = null
)

// --- Revoke ---
data class RevokeRequest(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("integrity_token") val integrityToken: String = ""
)

data class RevokeResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

// --- PoP Cancel ---
data class PopCancelRequest(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("reason") val reason: String? = null
)

// --- KVKK ---
data class KvkkWithdrawRequest(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("reason") val reason: String? = "Kullanıcı talebi"
)

data class KvkkBlockCardRequest(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("card_id") val cardId: String? = null,
    @SerializedName("reason") val reason: String? = "USER_REQUEST",
    // Play Integrity token'ı. requestHash'i AttestationBinding.requestHash(nonce, cardId) ile
    // üretilir → sunucu aynısını hesaplayıp karşılaştırır (card_id gövde takasına karşı bağlama).
    @SerializedName("integrity_token") val integrityToken: String? = null
)

// --- Config ---
data class AppConfigResponse(
    @SerializedName("minimum_android_version") val minimumAndroidVersion: String,
    @SerializedName("store_url") val storeUrl: String,
    @SerializedName("environment") val environment: String? = null,
    // Admin panelden tanımlanır; cihaz sürümü buna eşitse demo butonu görünür (şifre yok).
    @SerializedName("demo_version_android") val demoVersionAndroid: String = "",
    // Yürürlükteki hukuki metin demeti sürümü. Cihazdaki kabulden yeniyse yeniden onay istenir.
    // Boş = sunucu bir şey dayatmıyor; istemci gömülü taban sürümünde kalır (bkz. LegalTerms).
    @SerializedName("legal_terms_version") val legalTermsVersion: String = ""
)

// --- Chatbot ---
data class ChatMessageDto(
    @SerializedName("role") val role: String,           // "user" | "assistant"
    @SerializedName("content") val content: String
)

data class ChatEmailCapture(
    @SerializedName("email") val email: String,
    @SerializedName("original_question") val originalQuestion: String
)

data class ChatRequest(
    @SerializedName("messages") val messages: List<ChatMessageDto>,
    @SerializedName("turnstile_token") val turnstileToken: String? = null,
    @SerializedName("source") val source: String = "mobile",
    @SerializedName("language") val language: String? = null,
    @SerializedName("email_capture") val emailCapture: ChatEmailCapture? = null
)

data class ChatResponse(
    @SerializedName("message") val message: String,
    @SerializedName("requires_email") val requiresEmail: Boolean = false,
    @SerializedName("ticket_created") val ticketCreated: Boolean = false,
    @SerializedName("ticket_id") val ticketId: Int? = null
)

data class ChatErrorResponse(
    @SerializedName("error") val error: String,
    @SerializedName("code") val code: String? = null
)

// ── Feedback / Bize Ulaşın ──────────────────────────────────────────────────
// POST /api/feedback ile aynı sözleşme (landing formuyla bire bir). source="mobile"
// gönderildiğinde sunucu Turnstile'ı atlar → uygulamada captcha yok.
data class FeedbackRequest(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("subject") val subject: String,
    @SerializedName("message") val message: String,
    @SerializedName("source") val source: String = "mobile",
    @SerializedName("language") val language: String? = null,
    /** Kullanıcı bu gönderimde fotoğrafını paylaşmaya AÇIKÇA rıza verdi mi (varsayılan kapalı). */
    @SerializedName("photo_consent") val photoConsent: Boolean = false,
    /**
     * Denemedeki selfie, base64. Rıza yoksa null — sunucu da rıza olmadan bu alanı okumaz.
     * Görüntü CİHAZDAN gelir; enclave'den hiçbir biyometrik veri dışarı çıkmaz.
     */
    @SerializedName("photo_base64") val photoBase64: String? = null,
    /**
     * Kullanıcı KİMLİK ÇİPİNDEKİ fotoğrafı paylaşmaya AÇIKÇA rıza verdi mi. [photoConsent] ile
     * BİRLEŞTİRİLMEZ: selfie o an çekilen kare, bu ise kimlik belgesinin içinden çıkan resmî
     * görüntü — farklı kategori, ayrı rıza (sunucuda da kapılar ayrı).
     */
    @SerializedName("chip_photo_consent") val chipPhotoConsent: Boolean = false,
    /**
     * Çip fotoğrafının MODELE GİREN hâli (hizalanmış 112×112 kırpım), base64 — ham DG2 değil.
     * Benzerlik ikili bir fonksiyondur: tek tarafla skor yeniden üretilemez, bu yüzden teşhis
     * için gerekli. Rıza yoksa null.
     */
    @SerializedName("chip_photo_base64") val chipPhotoBase64: String? = null,
    /**
     * Kart ekleme akışının gruplama anahtarı. Mesaj gövdesinde METİN olarak da var; ayrı alan
     * olarak gitmesi, sunucunun kaydı `register_flow_events`'e regex'siz bağlamasını sağlar.
     */
    @SerializedName("flow_id") val flowId: String? = null,
    /** "android" — sunucu tanımadığı değeri düşürür. */
    @SerializedName("platform") val platform: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    /** Teşhis bloğu (canlılık skoru, cihaz eşiği, adım sayacı, kare ölçüleri) — skaler, rıza istemez. */
    @SerializedName("diagnostics") val diagnostics: String? = null,
    /**
     * Kullanıcı "bu sorun düzeldiğinde bana haber ver" kutusunu AÇIKÇA işaretledi mi.
     * Varsayılan kapalı; fotoğraf rızalarıyla aynı desen — rıza aktif bir eylemdir.
     */
    @SerializedName("notify_consent") val notifyConsent: Boolean = false,
    /**
     * FCM token. Rıza yoksa null — sunucu da rıza olmadan bu alanı okumaz. Kimlik bilgisi taşımaz
     * ama KALICI bir cihaz tanımlayıcısıdır, bu yüzden ayrı rıza kapısından geçer.
     */
    @SerializedName("push_token") val pushToken: String? = null
)

data class FeedbackErrorResponse(
    @SerializedName("error") val error: String? = null,
    @SerializedName("code") val code: String? = null
)
