package com.verifyblind.mobile.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * "Cihazın şu anda çalışan bir internet bağlantısı var mı?" — TEK kaynak.
 *
 * Neden gerekli: bir isteğin taşıma katmanında düşmesi (UnknownHostException, ConnectException,
 * SocketTimeoutException…) iki BAMBAŞKA durumu aynı istisnayla temsil eder:
 *  1) Kullanıcının interneti yok  → bizim arızamız DEĞİL, ölçülecek bir şey yok, Sentry'ye
 *     gitmemeli (ücretsiz plan kotası çevresel olaylarla yanıyordu — bkz. VERIFYBLIND-ANDROID-12).
 *  2) İnternet var ama bize ulaşılamıyor → gerçek sinyal (DNS/edge/origin arızası) → warning.
 *
 * Ayrımı istisnanın TÜRÜNDEN yapmak mümkün değil; bağlantı durumunu işletim sisteminden sormak
 * gerekiyor. `ACCESS_NETWORK_STATE` normal (tehlikeli olmayan) izindir, kullanıcıya sorulmaz.
 */
object NetworkStatus {

    /**
     * `true` = cihazda kullanılabilir internet YOK (uçak modu, kapsama dışı, doğrulanmamış Wi-Fi…).
     *
     * Emin olunamayan her durumda `false` döner: bilinmeyeni "internet yok" saymak gerçek arızaları
     * sessizce yutardı. Yani bu fonksiyon yalnız KESİN offline'da true'dur.
     */
    fun isOffline(context: Context): Boolean = !isOnline(context)

    /** `true` = doğrulanmış internet erişimi var (veya durum okunamadı → varsayılan "var"). */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                // VALIDATED = sistem gerçekten dışarı çıkabildiğini doğruladı. Captive portal
                // (otel/kafe girişi) bu bayrağı almaz → "bağlı ama internet yok" doğru sınıflanır.
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            // İzin yoksa/servis alınamıyorsa durum BİLİNMİYOR → "online" varsay (event kaybetme).
            true
        }
    }
}
