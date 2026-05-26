package com.verifyblind.mobile.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Geçici ağ hatalarını exponential backoff'la 1-2 kez yeniden dener.
 *
 * OkHttp'nin default retryOnConnectionFailure'ı DNS resolve fail olduğunda
 * retry YAPMAZ (alternatif route yok). Mobil ağlarda DNS resolver hücresel↔WiFi
 * geçişlerinde, CGNAT'larda saniyelik flake yapar — bu interceptor o açığı kapatır.
 *
 * Retry yapılan hatalar (gönderim öncesi olduğu için POST bile güvenle tekrar edilir):
 *  - UnknownHostException  → DNS resolve fail (veri gönderilmemiş)
 *  - ConnectException      → TCP connect refused (veri gönderilmemiş)
 *  - SSLHandshakeException → TLS handshake mid-flight, request body henüz yazılmadı
 *
 * Ayrıca HTTP 503 (Cloudflare/altyapı kaynaklı geçici servis indirimleri) için retry yapar.
 */
class NetworkRetryInterceptor : Interceptor {

    companion object {
        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = chain.proceed(request)

                if (response.code == 503 && attempt < MAX_RETRIES) {
                    response.close()
                    Thread.sleep(backoff(attempt))
                    continue
                }
                return response
            } catch (e: UnknownHostException) {
                lastException = e
                if (attempt == MAX_RETRIES) throw e
                Thread.sleep(backoff(attempt))
            } catch (e: ConnectException) {
                lastException = e
                if (attempt == MAX_RETRIES) throw e
                Thread.sleep(backoff(attempt))
            } catch (e: SSLHandshakeException) {
                lastException = e
                if (attempt == MAX_RETRIES) throw e
                Thread.sleep(backoff(attempt))
            }
        }
        throw lastException ?: IOException("NetworkRetryInterceptor: unreachable")
    }

    private fun backoff(attempt: Int): Long = INITIAL_BACKOFF_MS shl attempt
}
