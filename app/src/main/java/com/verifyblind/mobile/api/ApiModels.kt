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
    val DG15: String = "", // AA Public Key (Base64)
    val ActiveSig: String,
    val AAChallenge: String = "", // Challenge used for AA (Base64)
    val UserPubKey: String,
    // Nonce Verification (from Handshake)
    val Nonce: String = "",
    val Timestamp: Long = 0,
    val NonceSignature: String = "",
    // Biometrics (Base64)
    val DG2_Photo: String = "",
    val LivenessVideo: String = "",
    val ZoomVideo: String = "",
    val UserSelfie: String = "",
    val IntegrityToken: String = "" // Google Play Integrity
)

data class RegistrationRequest(
    @SerializedName("encrypted_key") val encryptedKey: String,
    @SerializedName("aes_blob") val aesBlob: String,
    @SerializedName("country_iso_code") val countryIsoCode: String = ""
)

// Demo mode için minimal kayıt isteği — enclave hardcoded veriyle imzalı ticket üretir.
data class DemoRegisterRequest(
    @SerializedName("user_pub_key") val userPubKey: String,
    @SerializedName("app_version") val appVersion: String = ""
)

// Hybrid Response from Enclave
data class EncryptedTicketResponse(
    @SerializedName("encrypted_ticket") val encryptedTicket: String // JSON: { enc_key, blob }
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
    @SerializedName("integrity_token") val integrityToken: String = ""
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
    @SerializedName("validations") val validations: com.google.gson.JsonElement?
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
    @SerializedName("reason") val reason: String? = "USER_REQUEST"
)

// --- Config ---
data class AppConfigResponse(
    @SerializedName("minimum_android_version") val minimumAndroidVersion: String,
    @SerializedName("store_url") val storeUrl: String,
    @SerializedName("environment") val environment: String? = null,
    @SerializedName("demo_password") val demoPassword: String = ""
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
