package com.verifyblind.mobile.util

import android.util.Base64
import com.google.gson.Gson
import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.api.DeviceFrameMetrics
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.api.StreamingCheckPayload
import com.verifyblind.mobile.api.StreamingCheckRequest
import com.verifyblind.mobile.api.StreamingPreparePayload
import com.verifyblind.mobile.api.StreamingPrepareRequest
import com.verifyblind.mobile.api.StreamingReleaseRequest
import com.verifyblind.mobile.crypto.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canlılık sırasında enclave'e kare gönderip benzerlik kararını alan istemci.
 *
 * **Neden var:** bugün cihazdaki 0.65 kapısında düşen deneme enclave'e HİÇ ulaşmıyor, dolayısıyla
 * kaç meşru kullanıcıyı hatalı reddettiğimiz bilinmiyor. Bu sınıf iki şeyi birden çözer:
 * (a) cihaz skoru 0.65'in altında kalsa bile enclave "benzerlik geçti" derse submit açılır,
 * (b) her deneme ölçülebilir bir veri noktasına dönüşür.
 *
 * **Ekrandaki 0.65 DEĞİŞMEZ.** Kullanıcı anlık skorunu görüp ortamı düzeltmeli, gözlüğünü
 * çıkarmalı; bu geri bildirim baskısı ürünün kalitesini koruyor ve zayıflatılmıyor. Enclave
 * onayı yalnızca İKİNCİ bir submit yolu açar.
 *
 * **Bu bir güvenlik gevşemesi değildir:** cihazdaki 0.65 hiçbir zaman güvenlik kontrolü değildi
 * ([isIdentityVerified] yerel bir boolean; kötü niyetli istemci onu zaten yamalayabilir). Gerçek
 * karar hep enclave'de ve register akışından hiçbir kontrol kaldırılmadı.
 *
 * **Her şey best-effort:** ağ hatası, hazırlık başarısızlığı, oran sınırı — hiçbiri akışı bozmaz.
 * Streaming çalışmazsa kullanıcı bugünkü davranışla (yalnız cihaz kapısı) devam eder ve hiçbir
 * şey kaybetmez; kaybeden biz oluruz (ölçüm).
 */
class SimilarityStreamer(
    private val flowId: String,
    private val enclavePubKey: String?,
) {
    companion object {
        private const val TAG = "SimilarityStreamer"

        /**
         * Cihaz ölçülerini toplar — sunucu bunlara GÜVENMEZ, aralık kontrolünden geçirir ve
         * geçersizse sessizce düşürür.
         *
         * Statik: ölçüler kare başına bir kez kurulur ve hem streaming isteğine hem de
         * submit'teki aday satırına AYNI nesne olarak taşınır (iki yerde ayrı hesaplamak,
         * aynı kareye ait iki farklı ölçü üretme riski taşırdı).
         */
        fun metricsOf(
            deviceMatchScore: Int?, luma: Int?, sharpness: Int?, quality: Int?,
            yaw: Int?, pitch: Int?, roll: Int?, faceWidthRatio: Int?,
            gestureCount: Int?, wrongGestureCount: Int?, elapsedMs: Int?,
        ) = DeviceFrameMetrics(
            deviceMatchScore = deviceMatchScore,
            luma = luma,
            sharpness = sharpness,
            quality = quality,
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            faceWidthRatio = faceWidthRatio,
            gestureCount = gestureCount,
            wrongGestureCount = wrongGestureCount,
            elapsedMs = elapsedMs,
            platform = "android",
            appVersion = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )

        /**
         * İki gönderim arasındaki en kısa süre. Kare akışının kendisi değil, **en iyi karenin
         * skoru yükseldiğinde** gönderiyoruz; yine de üst üste iyileşen bir seride istekler
         * birikmesin diye alt sınır var.
         *
         * ⚠️ Trafik/pil optimizasyonu kapsam DIŞI (yeterli istatistik birikince özellik
         * kapatılacak). Bu değer bir optimizasyon değil, sunucuyu kendi kendimize
         * DDoS'lamama önlemi.
         */
        const val MIN_INTERVAL_MS = 700L

        /**
         * Akış başına gönderim tavanı. Oran sınırı sunucuda da var (akış başına 120/15dk);
         * bu istemci tarafındaki karşılığı — sunucuya boşuna 429 aldırmayalım.
         */
        const val MAX_FRAMES = 60
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val seq = AtomicInteger(0)
    private val prepared = AtomicBoolean(false)
    /** Bir kez kapandıysa bir daha denenmez: her karede tekrar denemek, düşen bir sunucuyu döver. */
    private val disabled = AtomicBoolean(false)
    @Volatile private var inFlight = false
    @Volatile private var lastSentAt = 0L

    /**
     * Enclave'in onayladığı karenin selfie yolu ve kırpma yolu — submit anında **2. aday**
     * olarak gönderilir.
     *
     * ⚠️ Hangi karenin onaylandığını hatırlamak ŞART: enclave'in geçirdiği kare ile cihazın "en
     * iyi" saydığı kare farklı olabilir (asıl ölçmek istediğimiz sapma tam olarak bu). Onaylanan
     * kareyi unutup yalnız "onay aldık" bayrağını tutmak, submit'te enclave'e gönderilecek
     * fotoğrafı kaybetmek olurdu.
     */
    @Volatile var approvedSelfiePath: String? = null
        private set
    @Volatile var approvedCropPath: String? = null
        private set

    /** Enclave en az bir kareyi benzerlikten geçirdi mi (submit'in ikinci yolu). */
    val hasEnclaveApproval: Boolean get() = approvedSelfiePath != null

    /** Son enclave skoru — teşhis bloğuna yazılır (cihaz skoruyla kıyaslanamaz, farklı model). */
    @Volatile var lastEnclaveScore: Double? = null
        private set
    @Volatile var lastPLive: Double? = null
        private set

    /**
     * Akış başı hazırlık: DG2 bir kez enclave'e gider, enclave gömme vektörünü RAM'de tutar.
     * Sonraki karelerde yalnız selfie + kırpma gider.
     *
     * Düşerse streaming sessizce kapanır — çağıran hiçbir şey yapmaz.
     */
    fun prepare(dg2Raw: ByteArray?) {
        if (dg2Raw == null || dg2Raw.isEmpty() || enclavePubKey.isNullOrEmpty()) {
            disabled.set(true)
            return
        }

        scope.launch {
            try {
                val payload = StreamingPreparePayload(DG2 = Base64.encodeToString(dg2Raw, Base64.NO_WRAP))
                val (aesBlob, aesKey, _) = CryptoUtils.aesEncrypt(gson.toJson(payload))
                val encryptedKey = CryptoUtils.rsaEncrypt(aesKey, enclavePubKey)

                val res = RetrofitClient.api.streamingPrepare(
                    flowId, StreamingPrepareRequest(flowId, encryptedKey, aesBlob))

                if (res.isSuccessful) {
                    prepared.set(true)
                    AppLog.info("Canlı benzerlik hazırlığı tamam", TAG)
                } else {
                    disabled.set(true)
                    AppLog.info("Canlı benzerlik kapalı (hazırlık ${res.code()})", TAG)
                }
            } catch (e: Exception) {
                disabled.set(true)
                AppLog.info("Canlı benzerlik hazırlığı düştü: ${e.javaClass.simpleName}", TAG)
            }
        }
    }

    /**
     * Bir kareyi enclave'e gönderir. Çağıran bunu **en iyi karenin skoru her yükseldiğinde**
     * çağırır.
     *
     * @param selfiePath hizalanmış 112×112 PNG — enclave'in benzerlik için göreceği kare
     * @param cropPath AYNI karenin 2,7× geniş kırpması — enclave'in canlılık için göreceği kare
     *
     * ⚠️ İkisi AYNI kareden olmalıdır. Benzerliği bir kareden, canlılığı başkasından almak
     * gerçek bir açıktır (saldırgan gerçek yüzü benzerliğe, canlı kırpmayı anti-spoof'a verir).
     *
     * Sessizce düşer: kuyruk doluysa, hazırlık tamamlanmadıysa, aralık dolmadıysa ya da tavan
     * aşıldıysa hiçbir şey yapmaz.
     */
    fun submitFrame(
        selfiePath: String,
        cropPath: String?,
        metrics: DeviceFrameMetrics,
        onApproved: (() -> Unit)? = null,
    ) {
        if (disabled.get() || !prepared.get() || inFlight) return
        if (enclavePubKey.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_INTERVAL_MS) return
        if (seq.get() >= MAX_FRAMES) return

        inFlight = true
        lastSentAt = now

        scope.launch {
            try {
                val selfieBytes = java.io.File(selfiePath).readBytes()
                val cropBytes = cropPath?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }

                val payload = StreamingCheckPayload(
                    UserSelfie = Base64.encodeToString(selfieBytes, Base64.NO_WRAP),
                    AntiSpoofCrop = cropBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
                )
                val (aesBlob, aesKey, _) = CryptoUtils.aesEncrypt(gson.toJson(payload))
                val encryptedKey = CryptoUtils.rsaEncrypt(aesKey, enclavePubKey)

                val res = RetrofitClient.api.streamingCheck(
                    flowId,
                    StreamingCheckRequest(flowId, encryptedKey, aesBlob, seq.getAndIncrement(), metrics))

                val body = res.body()
                if (res.isSuccessful && body != null) {
                    lastEnclaveScore = body.matchScore
                    lastPLive = body.pLive
                    if (body.similarityPassed) {
                        // ONAYLANAN KAREYİ HATIRLA — submit'te 2. aday olarak gider.
                        // Sonraki onaylar üzerine yazar: en son onaylanan kare, kullanıcının
                        // o ana kadarki en iyi durumunu temsil eder.
                        approvedSelfiePath = selfiePath
                        approvedCropPath = cropPath
                        onApproved?.invoke()
                    }
                } else if (res.code() == 429) {
                    // Oran sınırına takıldık — akışın geri kalanında susmak, 429 yağdırmaktan iyi.
                    disabled.set(true)
                    AppLog.info("Canlı benzerlik oran sınırına takıldı — kapatıldı", TAG)
                }
            } catch (e: Exception) {
                // Tek bir kare düşmesi streaming'i kapatmaz: ağ dalgalanması geçici olabilir.
                AppLog.info("Kare gönderilemedi: ${e.javaClass.simpleName}", TAG)
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Akış bitti — enclave RAM'indeki gömme vektörünü sil.
     *
     * Best-effort: çağrılmasa da TTL (15 dk) girdiyi toplar. Yine de çağrılır, çünkü enclave'de
     * gereksiz duran her girdi tavana yaklaştırır.
     */
    fun release() {
        if (!prepared.get()) return
        scope.launch {
            try {
                RetrofitClient.api.streamingRelease(flowId, StreamingReleaseRequest(flowId))
            } catch (_: Exception) {
                // Temizlik başarısızlığı hiçbir şeyi bozmaz.
            }
        }
    }

    /** [metricsOf] ile aynı — örnek üzerinden çağrılabilen kısayol. */
    fun buildMetrics(
        deviceMatchScore: Int?, luma: Int?, sharpness: Int?, quality: Int?,
        yaw: Int?, pitch: Int?, roll: Int?, faceWidthRatio: Int?,
        gestureCount: Int?, wrongGestureCount: Int?, elapsedMs: Int?,
    ) = metricsOf(
        deviceMatchScore, luma, sharpness, quality,
        yaw, pitch, roll, faceWidthRatio,
        gestureCount, wrongGestureCount, elapsedMs,
    )
}
