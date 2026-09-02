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
}
