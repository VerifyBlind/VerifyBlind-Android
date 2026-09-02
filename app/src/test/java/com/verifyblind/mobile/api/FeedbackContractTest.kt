package com.verifyblind.mobile.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geri bildirim sözleşmesi — sunucudaki `FeedbackRequest` ile alan adı uyumu.
 *
 * Neden ayrı test: bu alanlar (flow_id, platform, app_version, diagnostics) mesaj gövdesinde
 * METİN olarak zaten vardı; ayrı alan haline getirilmelerinin TEK sebebi sunucunun kaydı
 * sorgulanabilir tutması. Alan adı sessizce kayarsa sunucu null yazar, kimse fark etmez ve
 * geri bildirim yine "yalnız e-postada" duruma düşer — tam da kapatmaya çalıştığımız boşluk.
 */
class FeedbackContractTest {

    private val gson = Gson()

    @Test
    fun feedbackRequest_serializesNewFieldsAsSnakeCase() {
        val req = FeedbackRequest(
            name = "Ad",
            email = "a@b.com",
            subject = "Konu",
            message = "Mesaj",
            language = "tr",
            flowId = "19EA2E24-7131-4B4F-8ED9-A3C7FC6ABDCA",
            platform = "android",
            appVersion = "1.0.3+229",
            diagnostics = "Canlılık / Liveness: skor=%60",
        )

        val json = gson.toJsonTree(req).asJsonObject

        assertEquals("19EA2E24-7131-4B4F-8ED9-A3C7FC6ABDCA", json.get("flow_id").asString)
        assertEquals("android", json.get("platform").asString)
        assertEquals("1.0.3+229", json.get("app_version").asString)
        assertTrue(json.get("diagnostics").asString.contains("skor=%60"))

        assertFalse("camelCase sızmamalı", json.has("flowId"))
        assertFalse("camelCase sızmamalı", json.has("appVersion"))
    }

    @Test
    fun feedbackRequest_omitsNewFieldsWhenAbsent() {
        // Landing formuyla aynı sözleşme: yeni alanlar OPSİYONEL, yokluklarında istek bozulmamalı.
        val req = FeedbackRequest(name = "", email = "", subject = "Konu", message = "Mesaj")

        val json: JsonObject = gson.toJsonTree(req).asJsonObject

        assertFalse(json.has("flow_id"))
        assertFalse(json.has("platform"))
        assertFalse(json.has("app_version"))
        assertFalse(json.has("diagnostics"))
        // Zorunlu alanlar yerinde.
        assertEquals("Konu", json.get("subject").asString)
        assertEquals("mobile", json.get("source").asString)
    }

    @Test
    fun feedbackRequest_photoFieldsStayIndependent() {
        // Selfie rızası çip fotoğrafını GETİRMEZ — iki ayrı kapı (sunucuda da öyle).
        val req = FeedbackRequest(
            name = "", email = "", subject = "Konu", message = "Mesaj",
            photoConsent = true, photoBase64 = "AAAA",
        )

        val json = gson.toJsonTree(req).asJsonObject

        assertTrue(json.get("photo_consent").asBoolean)
        assertFalse(json.get("chip_photo_consent").asBoolean)
        assertFalse(json.has("chip_photo_base64"))
    }

    @Test
    fun flowEventBody_keepsScoreAsNumber() {
        // Sunucu `int?` bekliyor. Map<String, String> ile Gson "60" yazardı ve bağlanma patlardı;
        // sözleşme bu yüzden Map<String, Any>. Bu test o kaymayı yakalar.
        val body: Map<String, Any> = mapOf(
            "nonce" to "n",
            "flow_id" to "f",
            "step" to "liveness_failed",
            "platform" to "android",
            "app_version" to "1.0.3+229",
            "detail" to "match_failed",
            "score" to 60,
        )

        val json = gson.toJsonTree(body).asJsonObject

        assertTrue("skor SAYI olmalı", json.get("score").asJsonPrimitive.isNumber)
        assertEquals(60, json.get("score").asInt)
    }
}
