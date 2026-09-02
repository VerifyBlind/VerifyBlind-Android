package com.verifyblind.mobile.util

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/**
 * `AppLog` → Sentry politikasının regresyon zemini.
 *
 * İki ayrı arıza buradan çıktı:
 *
 * 1) **Seviye event'e hiç uygulanmıyordu.** Kayıt `Sentry.withScope { scope.level = WARNING }`
 *    içinde yapılıyordu; Android SDK'sı `globalHubMode = true` ile başlatıldığı için
 *    `Sentry.getCurrentScopes()` fork edilmiş scope'u değil KÖK scope'u döndürür ve callback'te set
 *    edilen level/extra event'e hiç işlenmez. Sonuç: buradaki warning politikası kâğıt üstünde
 *    kalıyor, istisnalı her kayıt ERROR olarak faturalanıyordu. Test de bu yüzden
 *    `globalHubMode = true` ile başlatır — aksi halde hata JVM'de üremez.
 *
 * 2) **İnternetsiz cihazın taşıma hatası rapor ediliyordu.** Kullanıcı uçak modundayken
 *    `UnknownHostException` Sentry'ye düşüyordu (VERIFYBLIND-ANDROID-12). Kullanıcının bağlantısı
 *    bizim arızamız değil; kota gerçek arızalara ayrılır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])   // Robolectric 4.11 en fazla SDK 34 destekler; targetSdk 36 doğrudan çalışmaz.
class AppLogSentryPolicyTest {

    private val captured = mutableListOf<SentryEvent>()

    @Before
    fun setUp() {
        captured.clear()
        Sentry.init({ options: SentryOptions ->
            options.dsn = "https://public@localhost/1"
            // Event ağa çıkmadan yakalanır; null döndürmek gönderimi iptal eder.
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                captured += event
                null
            }
        }, /* globalHubMode = */ true)
        online()
    }

    @After
    fun tearDown() {
        AppLog.setOnlineProbeForTest(null)
        Sentry.close()
    }

    // ─────────────────────── Seviye event'e gerçekten yazılıyor mu? ───────────────────────

    @Test
    fun warningWithThrowable_isSentAsWarning_notError() {
        AppLog.warning("test uyarısı", "Test", IOException("boom"))

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.WARNING, captured[0].level)
    }

    @Test
    fun errorWithThrowable_isSentAsError() {
        AppLog.error("test hatası", "Test", IllegalStateException("boom"))

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.ERROR, captured[0].level)
    }

    @Test
    fun capturedEvent_carriesMessageAndCategory() {
        AppLog.warning("mesaj metni", "NFC", IOException("boom"))

        assertEquals("mesaj metni", captured[0].getExtra("message"))
        assertEquals("NFC", captured[0].getTag("category"))
    }

    @Test
    fun messageOnlyWarning_keepsItsLevel() {
        AppLog.warning("gövdesiz uyarı", "Test")

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.WARNING, captured[0].level)
    }

    // ─────────────────────── Sınıflandırma (failure) ───────────────────────

    @Test
    fun failure_withTransportError_online_staysWarning() {
        AppLog.failure("bağlantı hatası", "Test", UnknownHostException("api.verifyblind.com"))

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.WARNING, captured[0].level)
    }

    @Test
    fun failure_withUnknownError_isError() {
        AppLog.failure("beklenmeyen", "Test", IllegalStateException("boom"))

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.ERROR, captured[0].level)
    }

    @Test
    fun failure_withCancellation_producesNoEvent() {
        AppLog.failure("iptal", "Test", java.util.concurrent.CancellationException())

        assertEquals(0, captured.size)
    }

    @Test
    fun failure_with5xx_isError_and_4xx_isWarning() {
        AppLog.failure("sunucu", "Test", httpStatus = 503)
        AppLog.failure("istemci", "Test", httpStatus = 400)

        assertEquals(2, captured.size)
        assertEquals(SentryLevel.ERROR, captured[0].level)
        assertEquals(SentryLevel.WARNING, captured[1].level)
    }

    // ─────────────────────── İnternet yokken taşıma hatası → EVENT YOK ───────────────────────

    @Test
    fun transportFailure_whileOffline_isNotSent() {
        offline()
        AppLog.warning("Handshake bağlantı hatası", "Handshake", UnknownHostException("api.verifyblind.com"))
        AppLog.failure("Demo kayıt gönderilemedi", "Register", UnknownHostException("api.verifyblind.com"))
        AppLog.error("ham hata", "Register", IOException("no route to host"))

        assertEquals(0, captured.size)
    }

    @Test
    fun wrappedTransportFailure_whileOffline_isNotSent() {
        offline()
        // Gerçek akış: Retrofit/coroutine istisnayı sarmalayarak yukarı taşır.
        AppLog.failure(
            "Giriş sistem hatası",
            "Login",
            RuntimeException("wrapper", UnknownHostException("api.verifyblind.com"))
        )

        assertEquals(0, captured.size)
    }

    @Test
    fun nonTransportFailure_whileOffline_isStillSent() {
        offline()
        AppLog.error("kripto arızası", "Crypto", IllegalStateException("bad key"))

        assertEquals(1, captured.size)
        assertEquals(SentryLevel.ERROR, captured[0].level)
    }

    @Test
    fun withoutProbe_transportFailureIsStillSent() {
        // Bağlantı durumu bilinemiyorsa event KAYBEDİLMEZ (güvenli taraf).
        AppLog.setOnlineProbeForTest(null)
        AppLog.warning("bağlantı hatası", "Test", UnknownHostException("api.verifyblind.com"))

        assertEquals(1, captured.size)
    }

    // ─────────────────────── Yardımcılar ───────────────────────

    private fun online() = AppLog.setOnlineProbeForTest { true }
    private fun offline() = AppLog.setOnlineProbeForTest { false }
}
