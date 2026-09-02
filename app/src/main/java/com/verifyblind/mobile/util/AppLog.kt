package com.verifyblind.mobile.util

import android.content.Context
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel

/**
 * Merkezi loglama yardımcısı — iOS `Log.swift` PII disiplininin Android portu.
 *
 * PII kuralları:
 *  - [sensitive] yalnızca Logcat (DEBUG, private) → Sentry'ye ASLA gönderilmez.
 *  - Nonce, personId, MRZ değerleri, TCKN, biyometrik (landmark/embedding) vb. asla Sentry'ye gitmez.
 *  - [info] Sentry'ye EVENT göndermez (kota tüketmez) — yalnız breadcrumb bırakır; bir crash/error
 *    olduğunda son adımların izi raporun "Breadcrumbs" bölümünde görünür (iOS paritesi).
 *  - NFC/ağ/biyometrik iptal/HTTP 4xx → Sentry WARNING (düşük öncelik).
 *  - Gerçek hatalar (kripto, decode, beklenmeyen exception) → Sentry ERROR.
 *  - Cihazın interneti yokken oluşan taşıma hataları → Sentry'ye HİÇ gitmez (bkz. [capture]).
 *  - Anlık loglama: her zaman logcat'e yaz; Sentry'ye yalnız Sentry seviyesindekiler gider.
 */
object AppLog {

    /**
     * "Cihazda internet var mı?" sorusunu cevaplayan sonda. `VerifyBlindApp.onCreate` bir kez
     * [attach] ile kurar. null ise durum BİLİNMİYOR demektir ve her şey "online" varsayılır —
     * event kaybetmemek için güvenli taraf.
     *
     * Context yerine fonksiyon tutulur: test bunu deterministik olarak değiştirebilsin, üretim
     * kodu da bağlantı durumunu her çağrıda taze okusun (uçak modu akış ortasında değişebilir).
     */
    @Volatile
    private var onlineProbe: (() -> Boolean)? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        onlineProbe = { NetworkStatus.isOnline(app) }
    }

    /** Yalnız testler için — bağlantı sondasını değiştirir. null = "durum bilinmiyor". */
    @androidx.annotation.VisibleForTesting
    fun setOnlineProbeForTest(probe: (() -> Boolean)?) {
        onlineProbe = probe
    }

    fun info(message: String, tag: String = "VB") {
        Log.i(tag, message)
        // EVENT olarak GİTMEZ (kota tüketmez) — yalnız breadcrumb. Crash anında akış izi raporda görünür.
        breadcrumb(message, SentryLevel.INFO)
    }

    /** PII barındıran değer logları — yalnız Logcat DEBUG, Sentry'ye (event VE breadcrumb) GÖNDERİLMEZ. */
    fun sensitive(label: String, value: Any?, tag: String = "VB") {
        Log.d(tag, "[$label] (sensitive — not sent to Sentry)")
    }

    /**
     * NFC/ağ/biyometrik iptal/4xx/beklenen-başarısızlık → Sentry WARNING.
     * Throwable verilirse stacktrace ile yakalanır (grup/iz korunur); yoksa salt mesaj gider.
     * PII/değer mesaja eklenmez.
     */
    fun warning(message: String, tag: String = "VB", throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        capture(SentryLevel.WARNING, message, tag, throwable)
    }

    /** Kripto, decode, beklenmeyen exception → Sentry ERROR. */
    fun error(message: String, tag: String = "VB", throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        capture(SentryLevel.ERROR, message, tag, throwable)
    }

    /**
     * Seviyeyi hatanın TÜRÜNDEN türeten kayıt — iOS `Log.failure` paritesi.
     *
     * Neden gerekli: akış catch blokları her istisnayı [error] ile yazıyordu, dolayısıyla
     * kullanıcının interneti kesildiğinde de Sentry'ye faturalanabilir bir ERROR event gidiyordu.
     * Aylık error kotası çevresel olaylarla yanarken gerçek arızalar gürültüde kayboluyor. Seçim
     * artık çağrı yerinde değil burada yapılıyor: taşıma hatası ve 4xx warning, 5xx ve kripto/decode
     * error, iptaller ve internetsiz cihazdaki taşıma hataları yalnız breadcrumb.
     *
     * [httpStatus] verilirse (Retrofit `Response` yolunda istisna yoktur) sınıflandırma ondan yapılır.
     */
    fun failure(
        message: String,
        tag: String = "VB",
        throwable: Throwable? = null,
        httpStatus: Int? = null
    ) {
        when (levelOf(throwable, httpStatus)) {
            SentryLevel.INFO -> {
                // Logcat'te görünür kalsın (teşhis), Sentry'ye EVENT gitmesin.
                Log.w(tag, message, throwable)
                breadcrumb(message, SentryLevel.INFO)
            }
            SentryLevel.WARNING -> warning(message, tag, throwable)
            else -> error(message, tag, throwable)
        }
    }

    /**
     * [failure] seviye politikası. Sınıflandırılamayan hata = gerçek arıza varsayımı → ERROR
     * (güvenli taraf: yeni/bilinmeyen bir hata sessizce yutulmaz, görünür kalır).
     */
    private fun levelOf(throwable: Throwable?, httpStatus: Int?): SentryLevel {
        // 5xx = backend arızası; 4xx = istemci/kullanıcı durumu (iOS APIClientError.http paritesi).
        httpStatus?.let { return if (it in 500..599) SentryLevel.ERROR else SentryLevel.WARNING }

        // Hatasız (saf doğrulama / kullanıcı-durumu mesajı: geçersiz QR, kart yok…) → error değil.
        if (throwable == null) return SentryLevel.WARNING

        return when (throwable) {
            // Prompt iptali event üretmez; düzeltilebilir durumlar warning.
            is BiometricHelper.BiometricAuthException -> when (throwable.errorClass) {
                BiometricHelper.ErrorClass.CANCELLED -> SentryLevel.INFO
                BiometricHelper.ErrorClass.RECOVERABLE -> SentryLevel.WARNING
                BiometricHelper.ErrorClass.FAILURE -> SentryLevel.ERROR
            }
            is java.util.concurrent.CancellationException -> SentryLevel.INFO
            // Hedef uygulama kurulu değil (app-to-app dönüş) — çevresel, bizim arızamız değil.
            is android.content.ActivityNotFoundException -> SentryLevel.WARNING
            is retrofit2.HttpException ->
                if (throwable.code() in 500..599) SentryLevel.ERROR else SentryLevel.WARNING
            // Sözleşme/uygulama hatası — sessizce yutulmamalı.
            is com.google.gson.JsonParseException -> SentryLevel.ERROR
            else ->
                // OkHttp/Retrofit taşıma hatalarının tamamı IOException türevidir; sebep zinciri de
                // taranır (bkz. ServerErrorMessages.isTransportFailure). Geri kalan her şey bizim.
                if (ServerErrorMessages.isTransportFailure(throwable)) SentryLevel.WARNING
                else SentryLevel.ERROR
        }
    }

    /**
     * "Bu istisna, cihazın interneti olmadığı için mi oluştu?"
     *
     * Taşıma hatası (IOException türevi) + cihazda kullanılabilir internet yok → kullanıcının uçak
     * modu/kapsama dışı kalması demektir; bizim raporlayacağımız bir arıza değil. Sentry'ye
     * gönderilmez (bkz. [capture]). İnternet VARKEN aynı istisna anlamlıdır: bize ulaşılamıyor →
     * DNS/edge/origin arızasının erken sinyali, warning olarak gider.
     */
    private fun isOfflineTransportFailure(throwable: Throwable?): Boolean {
        if (throwable == null || !ServerErrorMessages.isTransportFailure(throwable)) return false
        val probe = onlineProbe ?: return false   // durum bilinmiyor → event'i kaybetme
        return !probe()
    }

    private fun breadcrumb(message: String, level: SentryLevel) {
        if (!Sentry.isEnabled()) return
        Sentry.addBreadcrumb(Breadcrumb(message).apply { this.level = level })
    }

    /**
     * Event gönderimi.
     *
     * `Sentry.withScope { scope.level = … }` KULLANILMAZ: Android SDK'sı `globalHubMode = true` ile
     * başlatılır (`SentryAndroid.init`), bu modda `Sentry.getCurrentScopes()` fork edilmiş scope'u
     * DEĞİL kök scope'u döndürür → `withScope` içinde set edilen level ve extra event'e HİÇ
     * uygulanmaz. Sonuç: istisnalı her kayıt varsayılan ERROR olarak gidiyordu ve buradaki warning
     * politikası kâğıt üstünde kalıyordu (5.000 error/ay kotasının yanma sebebi). Seviyeyi event'in
     * ÜSTÜNE yazmak scope'tan bağımsızdır ve her modda çalışır.
     */
    private fun capture(level: SentryLevel, message: String, tag: String, throwable: Throwable?) {
        if (!Sentry.isEnabled()) return

        // Her event aynı zamanda bir breadcrumb bırakır → crash raporu son adımların izini taşır (iOS paritesi).
        breadcrumb(message, level)

        // Cihazın interneti yokken oluşan taşıma hatası → EVENT YOK. Breadcrumb yukarıda düştüğü
        // için teşhis izi korunur; kota yalnız gerçek arızalara harcanır. Tek kapı burada: warning,
        // error ve failure'ın tamamı buradan geçer.
        if (isOfflineTransportFailure(throwable)) return

        val event = if (throwable != null) SentryEvent(throwable) else SentryEvent()
        event.level = level
        event.setTag("category", tag)
        event.setExtra("message", message)
        if (throwable == null) {
            event.message = io.sentry.protocol.Message().apply { formatted = message }
        }
        Sentry.captureEvent(event)
    }
}
