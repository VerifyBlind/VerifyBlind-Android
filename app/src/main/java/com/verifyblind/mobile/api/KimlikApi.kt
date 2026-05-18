package com.verifyblind.mobile.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface KimlikApi {

    @POST("handshake")
    suspend fun handshake(
        @Header("X-Play-Integrity") integrityToken: String? = null,
        @Body request: HandshakeRequest = HandshakeRequest()
    ): Response<HandshakeResponse>

    @POST("login-handshake")
    suspend fun loginHandshake(
        @Header("X-Play-Integrity") integrityToken: String? = null,
        @Body request: HandshakeRequest = HandshakeRequest()
    ): Response<LoginHandshakeResponse>

    @POST("register")
    suspend fun register(@Body request: RegistrationRequest): Response<EncryptedTicketResponse>

    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("/api/PartnerRequest/info/{nonce}")
    suspend fun getPartnerInfo(
        @retrofit2.http.Path("nonce") nonce: String,
        @Header("X-Play-Integrity") integrityToken: String? = null
    ): Response<PartnerInfoResponse>

    @POST("revoke")
    suspend fun revoke(@Body request: RevokeRequest): Response<RevokeResponse>

    @POST("/api/pop/cancel")
    suspend fun cancelPop(@Body request: PopCancelRequest): Response<Unit>

    @GET("/api/public/app-config")
    suspend fun getAppConfig(): Response<AppConfigResponse>

    // ── KVKK ──────────────────────────────────────────────────────────────────

    @POST("/api/kvkk/consent/withdraw")
    suspend fun withdrawConsent(@Body request: KvkkWithdrawRequest): Response<Unit>

    @POST("/api/kvkk/block-card")
    suspend fun blockCard(@Body request: KvkkBlockCardRequest): Response<Unit>

    @GET("/api/kvkk/privacy-notice")
    suspend fun getPrivacyNotice(
        @Query("format") format: String? = null
    ): Response<com.google.gson.JsonObject>

    // ── Chatbot ──────────────────────────────────────────────────────────────

    @POST("/api/chatbot/chat")
    suspend fun chatbotChat(@Body request: ChatRequest): Response<ChatResponse>
}
