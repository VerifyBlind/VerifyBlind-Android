package com.verifyblind.mobile.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import kotlinx.coroutines.runBlocking
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * API model serialization/deserialization testleri.
 *
 * Amaç:
 *   - @SerializedName annotation'larının doğru snake_case üretip üretmediğini doğrula
 *   - Eksik opsiyonel alanların Kotlin default değerlerine düştüğünü doğrula
 *   - Nullable alanların JSON'da null olduğunda doğru parse edildiğini doğrula
 *   - .NET SharedModels.cs ile struct uyumunu (alan adı bazında) teyit et
 *
 * Gson bağımlılığı retrofit2:converter-gson üzerinden transitif olarak mevcut.
 * Android API bağımlılığı yok — pure JVM testi.
 */
class ApiModelsTest {

    private lateinit var gson: Gson

    @Before
    fun setUp() {
        gson = Gson()
    }

    // ─────────────────────────── HandshakeRequest ────────────────────────────────

    @Test
    fun handshakeRequest_serializesWithSnakeCase() {
        val req = HandshakeRequest(integrityToken = "tok123", fcmToken = "fcm456")
        val json = gson.toJson(req)
        assertTrue("integrity_token alanı olmalı", json.contains("\"integrity_token\""))
        assertTrue("fcm_token alanı olmalı", json.contains("\"fcm_token\""))
        assertFalse("camelCase integrity_token çıkmamalı", json.contains("\"integrityToken\""))
        assertFalse("camelCase fcmToken çıkmamalı", json.contains("\"fcmToken\""))
    }

    @Test
    fun handshakeRequest_platformDefault_isAndroid() {
        val req = HandshakeRequest(integrityToken = "x")
        assertEquals("android", req.platform)
    }

    @Test
    fun handshakeRequest_deserialize_roundTrip() {
        val original = HandshakeRequest(integrityToken = "t1", fcmToken = "f1", platform = "android")
        val json = gson.toJson(original)
        val parsed = gson.fromJson(json, HandshakeRequest::class.java)
        assertEquals(original.integrityToken, parsed.integrityToken)
        assertEquals(original.fcmToken, parsed.fcmToken)
        assertEquals(original.platform, parsed.platform)
    }

    // ─────────────────────────── HandshakeResponse ───────────────────────────────

    @Test
    fun handshakeResponse_deserializesFromJson() {
        val json = """
            {
              "nonce": "nonce-abc123",
              "timestamp": 1716700000,
              "nonce_signature": "sig-xyz",
              "challenges": [1, 3, 4]
            }
        """.trimIndent()
        val resp = gson.fromJson(json, HandshakeResponse::class.java)
        assertEquals("nonce-abc123", resp.nonce)
        assertEquals(1716700000L, resp.timestamp)
        assertEquals("sig-xyz", resp.nonceSignature)
        assertEquals(listOf(1, 3, 4), resp.challenges)
        assertNull("enclavePubKey yoksa null olmalı", resp.enclavePubKey)
        assertNull("pcr0Signature yoksa null olmalı", resp.pcr0Signature)
        assertNull("attestationDocument yoksa null olmalı", resp.attestationDocument)
    }

    @Test
    fun handshakeResponse_withAllOptionalFields() {
        val json = """
            {
              "nonce": "n1",
              "timestamp": 1000,
              "nonce_signature": "s1",
              "pcr0_signature": "pcr-sig",
              "attestation_document": "att-doc",
              "enclave_pub_key": "enc-key",
              "challenges": [2]
            }
        """.trimIndent()
        val resp = gson.fromJson(json, HandshakeResponse::class.java)
        assertEquals("pcr-sig", resp.pcr0Signature)
        assertEquals("att-doc", resp.attestationDocument)
        assertEquals("enc-key", resp.enclavePubKey)
    }

    @Test
    fun handshakeResponse_missingChallenges_isNull() {
        // Gson ignores Kotlin default values — missing field deserializes to null, not emptyList()
        val json = """{"nonce":"n","timestamp":1,"nonce_signature":"s"}"""
        val resp = gson.fromJson(json, HandshakeResponse::class.java)
        assertNull("Gson beklenen alan yoksa null döner, Kotlin default'u kullanmaz", resp.challenges)
    }

    // ─────────────────────────── RegistrationRequest ─────────────────────────────

    @Test
    fun registrationRequest_serializesSnakeCase() {
        val req = RegistrationRequest(encryptedKey = "key123", aesBlob = "blob456")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"encrypted_key\""))
        assertTrue(json.contains("\"aes_blob\""))
        assertFalse(json.contains("\"encryptedKey\""))
        assertFalse(json.contains("\"aesBlob\""))
    }

    @Test
    fun registrationRequest_countryIsoCode_defaultEmpty() {
        val req = RegistrationRequest(encryptedKey = "k", aesBlob = "b")
        assertEquals("", req.countryIsoCode)
    }

    @Test
    fun registrationRequest_deserialize_roundTrip() {
        val original = RegistrationRequest(
            encryptedKey = "key-base64==",
            aesBlob = "blob-base64==",
            countryIsoCode = "TR"
        )
        val parsed = gson.fromJson(gson.toJson(original), RegistrationRequest::class.java)
        assertEquals(original.encryptedKey, parsed.encryptedKey)
        assertEquals(original.aesBlob, parsed.aesBlob)
        assertEquals("TR", parsed.countryIsoCode)
    }

    // ─────────────────────────── LoginRequest ────────────────────────────────────

    @Test
    fun loginRequest_serializesSnakeCase() {
        val req = LoginRequest(
            encrSignedTicket = "ticket-data", nonce = "nonce-guid-123",
            userSignature = "sig-b64", userSigTs = 1700000000
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"encr_signed_ticket\""))
        assertTrue(json.contains("\"nonce\""))
        assertFalse(json.contains("\"encrSignedTicket\""))
        // Holder-of-key (Y-4) alanları snake_case wire format
        assertTrue(json.contains("\"user_signature\""))
        assertTrue(json.contains("\"user_sig_ts\""))
    }

    @Test
    fun loginRequest_integrityTokenDefault_emptyString() {
        val req = LoginRequest(encrSignedTicket = "t", nonce = "n")
        assertEquals("", req.integrityToken)
    }

    // ─────────────────────────── RevokeRequest ───────────────────────────────────

    @Test
    fun revokeRequest_serializesSnakeCase() {
        val req = RevokeRequest(nonce = "revoke-nonce-xyz")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"nonce\""))
        assertTrue(json.contains("\"integrity_token\""))
    }

    // ─────────────────────────── PopCancelRequest ────────────────────────────────

    @Test
    fun popCancelRequest_serializesWithNonce() {
        val req = PopCancelRequest(nonce = "pop-nonce-abc", reason = "user_cancelled")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"nonce\""))
        assertTrue(json.contains("\"reason\""))
    }

    @Test
    fun popCancelRequest_nullReason_omittedByGson() {
        // Gson omits null fields by default — field won't appear in JSON at all
        val req = PopCancelRequest(nonce = "pop-nonce", reason = null)
        val obj = gson.fromJson(gson.toJson(req), JsonObject::class.java)
        assertFalse("Gson null alanı JSON'a yazmaz", obj.has("reason"))
    }

    // ─────────────────────────── EncryptedTicketResponse ─────────────────────────

    @Test
    fun encryptedTicketResponse_deserializesEncryptedTicket() {
        val json = """{"encrypted_ticket": "base64-enc-ticket=="}"""
        val resp = gson.fromJson(json, EncryptedTicketResponse::class.java)
        assertEquals("base64-enc-ticket==", resp.encryptedTicket)
    }

    // ─────────────────────────── HybridContent ───────────────────────────────────

    @Test
    fun hybridContent_serializesEncKeyAndBlob() {
        val hc = HybridContent(encKey = "enc-key-b64", blob = "blob-b64")
        val json = gson.toJson(hc)
        assertTrue(json.contains("\"enc_key\""))
        assertTrue(json.contains("\"blob\""))
    }

    @Test
    fun hybridContent_deserialize_roundTrip() {
        val json = """{"enc_key":"k1","blob":"b1"}"""
        val hc = gson.fromJson(json, HybridContent::class.java)
        assertEquals("k1", hc.encKey)
        assertEquals("b1", hc.blob)
    }

    // ─────────────────────────── AppConfigResponse ───────────────────────────────

    @Test
    fun appConfigResponse_deserializesSnakeCase() {
        val json = """
            {
              "minimum_android_version": "1.0.10",
              "store_url": "https://play.google.com/store/apps/details?id=com.verifyblind.mobile",
              "environment": "production"
            }
        """.trimIndent()
        val resp = gson.fromJson(json, AppConfigResponse::class.java)
        assertEquals("1.0.10", resp.minimumAndroidVersion)
        assertEquals("production", resp.environment)
    }

    @Test
    fun appConfigResponse_optionalEnvironment_defaultsToNull() {
        val json = """{"minimum_android_version":"1.0.0","store_url":"https://example.com"}"""
        val resp = gson.fromJson(json, AppConfigResponse::class.java)
        assertNull(resp.environment)
    }

    // ─────────────────────────── ChatRequest ─────────────────────────────────────

    @Test
    fun chatRequest_serializesMessagesArray() {
        val req = ChatRequest(
            messages = listOf(
                ChatMessageDto(role = "user", content = "Merhaba"),
                ChatMessageDto(role = "assistant", content = "Merhaba!")
            ),
            source = "mobile"
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"messages\""))
        assertTrue(json.contains("\"role\""))
        assertTrue(json.contains("\"content\""))
        assertTrue(json.contains("\"source\""))
    }

    @Test
    fun chatRequest_nullTurnstileToken_omittedByGson() {
        // Gson omits null fields by default — field won't appear in JSON at all
        val req = ChatRequest(messages = emptyList(), turnstileToken = null)
        val obj = gson.fromJson(gson.toJson(req), JsonObject::class.java)
        assertFalse("Gson null alanı JSON'a yazmaz", obj.has("turnstile_token"))
    }

    @Test
    fun chatResponse_deserializesAllFields() {
        val json = """
            {
              "message": "Yardımcı olabilirim.",
              "requires_email": true,
              "ticket_created": true,
              "ticket_id": 42
            }
        """.trimIndent()
        val resp = gson.fromJson(json, ChatResponse::class.java)
        assertEquals("Yardımcı olabilirim.", resp.message)
        assertTrue(resp.requiresEmail)
        assertTrue(resp.ticketCreated)
        assertEquals(42, resp.ticketId)
    }

    @Test
    fun chatResponse_minimalJson_optionalFieldsDefaulted() {
        val json = """{"message": "Hi"}"""
        val resp = gson.fromJson(json, ChatResponse::class.java)
        assertEquals("Hi", resp.message)
        assertFalse(resp.requiresEmail)
        assertFalse(resp.ticketCreated)
        assertNull(resp.ticketId)
    }

    // ─────────────────────────── KvkkWithdrawRequest ─────────────────────────────

    @Test
    fun kvkkWithdrawRequest_serializesSnakeCase() {
        val req = KvkkWithdrawRequest(nonce = "kvkk-nonce-123")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"nonce\""))
        assertTrue(json.contains("\"reason\""))
        assertFalse(json.contains("\"withdrawReason\""))
    }

    // ─────────────────────────── LivenessAction ──────────────────────────────────

    @Test
    fun livenessAction_fromInt_mapsCorrectly() {
        assertEquals(LivenessAction.None, LivenessAction.fromInt(0))
        assertEquals(LivenessAction.FaceLeft, LivenessAction.fromInt(1))
        assertEquals(LivenessAction.FaceRight, LivenessAction.fromInt(2))
        assertEquals(LivenessAction.Blink, LivenessAction.fromInt(3))
        assertEquals(LivenessAction.Smile, LivenessAction.fromInt(4))
    }

    @Test
    fun livenessAction_fromInt_unknownValue_returnsNone() {
        assertEquals(LivenessAction.None, LivenessAction.fromInt(99))
        assertEquals(LivenessAction.None, LivenessAction.fromInt(-1))
    }

    @Test
    fun livenessAction_values_matchExpectedInts() {
        assertEquals(0, LivenessAction.None.value)
        assertEquals(1, LivenessAction.FaceLeft.value)
        assertEquals(2, LivenessAction.FaceRight.value)
        assertEquals(3, LivenessAction.Blink.value)
        assertEquals(4, LivenessAction.Smile.value)
    }

    // ─────────────────────────── Duruş + olay dizisi ─────────────────────────────

    /** Enclave'in el sıkışma gövdesi: `choreography.stops[].pos/event` sayı olarak. */
    @Test
    fun handshakeResponse_parsesChoreography() {
        val json = """
            {"nonce":"n","timestamp":1,"nonce_signature":"s","challenges":[1,3],
             "choreography":{"version":1,"stops":[{"pos":3,"event":0},{"pos":1,"event":1},
                                                  {"pos":2,"event":0},{"pos":3,"event":2}]}}
        """.trimIndent()
        val r = gson.fromJson(json, HandshakeResponse::class.java)
        val c = r.choreography!!
        assertEquals(1, c.version)
        assertEquals(4, c.stops.size)
        assertEquals(3, c.stops[0].pos)      // yakın çıpa
        assertEquals(1, c.stops[1].event)    // kırpma
    }

    /** Eski sunucu alanı göndermez → null → eski jest + parallaks akışı. */
    @Test
    fun handshakeResponse_withoutChoreography_isNull() {
        val r = gson.fromJson("""{"nonce":"n","timestamp":1,"nonce_signature":"s"}""", HandshakeResponse::class.java)
        assertNull(r.choreography)
    }

    /**
     * 🔴 Kanıtın anahtarları enclave modeliyle BİREBİR aynı olmalı (VerifyBlind.Core
     * ChoreographyModels.cs). Yanlış bir ad sessizce null okunur ve kanıt "yapı bozuk" düşer.
     */
    @Test
    fun choreographyProof_usesEnclaveKeys() {
        val proof = ChoreographyProof(
            stops = listOf(ChoreographyProofStop(hold = listOf("a", "b"), event = listOf("c"),
                faceFraction = 0.62f, attempts = 1)),
            bgTexture = 18f, bgTextureNear = 15f, elapsedMs = 14000, resets = 0, wrongEvents = 1,
            trackingChanges = 0, redos = 2, trace = "0.0 start N,F+blink,M,N;",
        )
        val o = gson.toJsonTree(proof).asJsonObject
        assertEquals(1, o.get("version").asInt)
        for (key in listOf("stops", "bg_texture", "bg_texture_near", "elapsed_ms", "resets", "wrong_events",
                "tracking_changes", "redos", "trace"))
            assertTrue("$key alanı olmalı", o.has(key))
        val stop = o.getAsJsonArray("stops")[0].asJsonObject
        for (key in listOf("hold", "event", "face_fraction", "attempts"))
            assertTrue("$key alanı olmalı", stop.has(key))
    }

    // ─────────────────────────── Huni olayı (flow-event) ────────────────────────

    /**
     * 🔴 Kotlin `Map<String, Any>` parametresi JVM'de `Map<String, ? extends Object>` olur ve
     * Retrofit joker tipli gövdeyi reddeder. Android'den tek bir huni olayı sunucuya ulaşmadı
     * (2026-09-24'e kadar); hata FlowTelemetry'de yutuluyordu.
     */
    @Test
    fun flowEvent_bodyHasNoWildcard() {
        val m = KimlikApi::class.java.methods.first { it.name == "flowEvent" }
        val body = m.genericParameterTypes[0] as ParameterizedType
        assertFalse(
            "flowEvent gövdesi joker tip taşıyor: $body",
            body.actualTypeArguments.any { it is WildcardType })
    }

    /**
     * Aynı hatanın DAVRANIŞ kanıtı: Retrofit çağrıyı kabul etmeli. Sunucu yok (kapalı port) →
     * ağ hatası beklenir; IllegalArgumentException, Retrofit'in yöntemi reddettiği demektir.
     */
    @Test
    fun flowEvent_isAcceptedByRetrofit() {
        val api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:9/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KimlikApi::class.java)
        val error = runCatching {
            runBlocking { api.flowEvent(mapOf("step" to "handshake", "score" to 60)) }
        }.exceptionOrNull()
        assertFalse("Retrofit yöntemi reddetti: $error", error is IllegalArgumentException)
    }

    /** Önizleme istek/yanıt anahtarları enclave modeliyle (ParallaxPreviewRequest/Result) aynı olmalı. */
    @Test
    fun parallaxPreview_usesEnclaveKeys() {
        val req = gson.toJsonTree(ParallaxPreviewRequest("f", "k", "b")).asJsonObject
        for (key in listOf("flow_id", "encrypted_key", "aes_blob")) assertTrue("$key olmalı", req.has(key))
        assertTrue(gson.toJsonTree(ParallaxPreviewPayload(listOf("a", "b"))).asJsonObject.has("frames"))

        val res = gson.fromJson("""{"status":"unmeasured","p":null,"s":1.4}""", ParallaxPreviewResponse::class.java)
        assertEquals("unmeasured", res.status)
        assertEquals(1.4, res.s!!, 1e-9)
    }

    /** Yükte alan adı "ChoreographyProof" — enclave SecurePayload özelliği büyük/küçük harf duyarlı. */
    @Test
    fun securePayload_carriesChoreographyProofUnderExactName() {
        val payload = SecurePayload(
            SOD = "", DG1 = "", DG2 = "", DG15 = "", ActiveSig = "", AAChallenge = "",
            UserPubKey = "", Nonce = "n", Timestamp = 0, NonceSignature = "",
            LivenessVideo = "", ZoomVideo = "", UserSelfie = "", IntegrityToken = "",
            AntiSpoofCrop = "",
            choreographyProof = ChoreographyProof(stops = emptyList()),
        )
        val o: JsonObject = gson.toJsonTree(payload).asJsonObject
        assertTrue(o.has("ChoreographyProof"))
    }
}
