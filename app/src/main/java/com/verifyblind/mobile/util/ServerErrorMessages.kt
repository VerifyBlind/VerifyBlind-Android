package com.verifyblind.mobile.util

import android.content.Context
import com.verifyblind.mobile.R

/**
 * HTTP hata kodu → kullanıcı dostu mesaj eşlemesi. Tek kaynak: Android'in her yerinde (ViewModel,
 * Fragment'ler) AYNI metinler gösterilsin ve iOS `APIClientError.http` ile sözleşme uyumlu kalsın diye
 * eşleme burada toplanır (503/52x sınırı tek yerde — kopya mantık → drift riski yok).
 *
 * 5xx = sunucu/altyapı tarafı; kullanıcının KENDİ bağlantısı 5xx ÜRETMEZ (o yol bir exception/timeout
 * olarak ayrı yakalanır → [connectionFailed]).
 */
object ServerErrorMessages {

    /** Sunucu (5xx) hatası ise nazik mesaj; değilse null (çağıran kendi 4xx/fallback mantığına düşer). */
    fun serverErrorOrNull(context: Context, statusCode: Int): String? = when {
        // 503 + Cloudflare 52x (520-527, "origin unreachable" vb.) = en net "geçici hizmet dışı/bakım".
        statusCode == 503 || statusCode in 520..527 -> context.getString(R.string.error_service_unavailable)
        statusCode in 500..599 -> context.getString(R.string.error_server_temporary)
        else -> null
    }

    /** HTTP cevabı hiç alınamadığında (DNS/TCP/timeout/no-internet) gösterilecek tek tip bağlantı mesajı. */
    fun connectionFailed(context: Context): String = context.getString(R.string.error_connection_generic)

    /** Uygulama içi bir arıza (bağlantı DEĞİL) kullanıcıya bu şekilde gösterilir. */
    fun internalError(context: Context): String = context.getString(R.string.error_internal_generic)

    /**
     * Yakalanan bir istisnayı kullanıcıya GÖSTERİLEBİLİR başlık+metne çevirir.
     *
     * Neden: catch blokları uzun süre `e.message`'ı doğrudan ekrana basıyordu. İnternet kapalıyken
     * kullanıcı `Unable to resolve host "api.verifyblind.com"` görüyordu — hem anlaşılmaz, hem de
     * sunucu adını içerdiği için "servis çökmüş" izlenimi veriyordu; oysa sorun cihazın bağlantısı.
     * Tek dönüşüm noktası burada olsun ki her akış aynı metni göstersin (iOS `APIClientError`
     * `errorDescription`/`suggestedTitleKey` paritesi).
     */
    fun friendlyTitle(context: Context, e: Throwable): String =
        context.getString(
            if (isTransportFailure(e)) R.string.connection_error_title else R.string.error_system_title
        )

    /** [friendlyTitle] ile eşleşen gövde metni. */
    fun friendlyMessage(context: Context, e: Throwable): String =
        if (isTransportFailure(e)) connectionFailed(context) else internalError(context)

    /**
     * İstek gerçekten taşıma katmanında mı düştü (DNS/TCP/TLS/timeout), yoksa uygulama içinde bir
     * şey mi bozuldu?
     *
     * Bu ayrım teşhis için kritik: `catch (e: Exception)` her istisnayı "İnternete ulaşılamadı"
     * diye gösterdiği sürece, bir derleme/serileştirme arızası kullanıcı tarafında bağlantı
     * sorunu gibi görünür. R8 açıldığında tam olarak bu oldu — Retrofit'in
     * ClassCastException'ı bağlantı hatası sanıldı (Sentry 143088976).
     *
     * OkHttp/Retrofit taşıma hatalarının tamamı IOException türevidir (UnknownHostException,
     * SocketTimeoutException, ConnectException, SSLException...). Geri kalan her şey bizim
     * hatamızdır. Sarmalanmış olabileceği için sebep zinciri de taranır.
     */
    fun isTransportFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            if (current is java.io.IOException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
