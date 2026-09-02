package com.verifyblind.mobile.util

import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.api.RetrofitClient
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * Kart ekleme hunisi — kullanıcının akışı NEREDE bıraktığını görebilmek için "adıma ULAŞILDI"
 * olayları gönderir.
 *
 * Terk olayını YAKALAMAYA ÇALIŞMAZ: kullanıcı uygulamayı zorla kapatır, telefon ölür, sistem süreci
 * öldürür — o olay hiçbir zaman ulaşmaz. Terk sunucuda huniden çıkarılır: NFC'ye ulaşıp liveness'a
 * ulaşmayan akış NFC'de bırakılmıştır.
 *
 * ⚠️ KİMLİKLE BAĞ YOK. [flowId] her akış başında üretilen rastgele bir gruplama anahtarıdır ve
 * yalnızca adımları birbirine bağlar; sunucu nonce'u sadece isteğin geçerliliğini doğrulamak için
 * kullanıp atar. Gönderim best-effort'tur — hata kullanıcıya YANSIMAZ ve akışı bloklamaz.
 */
object FlowTelemetry {

    const val STEP_HANDSHAKE = "handshake"
    const val STEP_MRZ = "mrz"
    const val STEP_NFC = "nfc"
    const val STEP_LIVENESS = "liveness"
    const val STEP_SUBMIT = "submit"
    const val STEP_SUCCESS = "success"

    private const val TAG = "FlowTelemetry"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var flowId: String = UUID.randomUUID().toString()

    /**
     * Demo akışı huniyi KİRLETMEZ: demo kart ekleme gerçek bir kullanıcı denemesi değildir ve
     * handshake yapmadığı için hunide "Başlatıldı"sız bir akış olarak görünürdü. Kapı tek noktada
     * durur — her çağrı yerine ayrı ayrı `isDemoMode` koşulu koymak unutulmaya açıktı (iOS'ta
     * `RegisterViewModel.track` içindeki `guard !isDemo` paritesi).
     */
    @Volatile
    private var demoFlow: Boolean = false

    private val sentSteps = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Geri bildirim e-postasına konur — kullanıcının ANLATISI ile hunideki satır ancak bu anahtarla
     * birleşir. Bugüne kadar birleşmiyordu: "NFC'de zorlandım" diyen postayla, o akışın hangi adımda
     * kaç saniye durduğunu gösteren satır arasında hiçbir bağ yoktu.
     *
     * ⚠️ Bağın bedeli bilinçli: tablo tasarım gereği kimlikle bağsız kalır, bağ YALNIZ gönüllü
     * anlatan kullanıcı için ve YALNIZ destek posta kutusunda kurulur.
     */
    val currentFlowId: String get() = flowId

    /** Yeni bir kart ekleme denemesi başladı — yeni gruplama anahtarı. */
    fun startFlow(isDemo: Boolean) {
        flowId = UUID.randomUUID().toString()
        demoFlow = isDemo
        sentSteps.clear()
        // Demo akışı sunucuya HİÇ olay göndermez → etiketlemek, DB'de karşılığı olmayan bir
        // flow_id üretirdi. Bunun yerine etiket TEMİZLENİR: aksi halde demo sırasında çıkan
        // hatalar bir ÖNCEKİ gerçek akışa yazılırdı ki asıl yanıltıcı olan budur.
        setSentryTag(if (isDemo) null else flowId)
    }

    /**
     * Akış kapandı — Sentry etiketini temizler (iOS `FlowTelemetry.endFlow` paritesi).
     *
     * Temizlemezsek akıştan SONRA üretilen olaylar bitmiş bir akışın kimliğini taşır ve incelemede
     * yanlış satıra bakarız. [startFlow] etiketi zaten üzerine yazar; bu metot akış ile akış
     * ARASINDAKİ boşluk için.
     */
    fun endFlow() {
        setSentryTag(null)
    }

    /**
     * `flow_id`'yi GLOBAL Sentry scope'una etiket olarak yazar.
     *
     * Neden tek bir log mesajına değil de scope'a: destek postasındaki flow_id ile Sentry kaydını
     * birleştirmek isterken tıkanan şey, olayın hangi akışa ait olduğunun HİÇBİR yerde yazmaması
     * idi (eşleştirme zaman + cihaz + skor tahminiyle yapılıyordu). Scope etiketi bunu akış
     * boyunca üretilen HER olaya taşır — yalnız canlılığa değil; NFC, kripto ve ağ hataları da
     * aynı akışa bağlanır.
     */
    private fun setSentryTag(value: String?) {
        try {
            Sentry.configureScope { scope ->
                if (value != null) scope.setTag("flow_id", value) else scope.removeTag("flow_id")
            }
        } catch (e: Exception) {
            // Telemetri etiketi hiçbir koşulda akışı bozmamalı.
            AppLog.info("Sentry flow_id etiketi yazılamadı: ${e.javaClass.simpleName}", TAG)
        }
    }

    /** Huni SIRASININ parçası değil — canlılık adımının neden kaybedildiğini işaretler. */
    const val STEP_LIVENESS_FAILED = "liveness_failed"

    /**
     * Canlılık testi başarısız bitti. `reason` sabit kümedendir (sunucu bilinmeyeni düşürür):
     * timeout_gesture | timeout_session | too_many_errors | match_failed | no_selfie.
     * Akış başına ilk sebep kaydedilir — tekrar denemeler istatistiği şişirmesin.
     *
     * [score] cihaz-içi en iyi eşleşme yüzdesi (0-100). Cihaz kapısı (MobileFaceNet, eşik %65) ile
     * enclave kapısı (ArcFace, eşik 0.20) farklı model ve farklı ölçektir; cihazda düşen deneme
     * enclave'e HİÇ ulaşmaz, dolayısıyla sunucudaki skor histogramı bu redleri göremez. Skoru
     * buraya koymak "%64'te sınırdan dönen" ile "%10'da hiç tutmayan" ayrımını mümkün kılar.
     */
    fun livenessFailed(reason: String, nonce: String?, score: Int? = null) {
        if (demoFlow) return
        if (nonce.isNullOrEmpty()) return
        if (!sentSteps.add(STEP_LIVENESS_FAILED)) return

        val currentFlow = flowId
        scope.launch {
            try {
                RetrofitClient.api.flowEvent(
                    mapOf(
                        "nonce" to nonce,
                        "flow_id" to currentFlow,
                        "step" to STEP_LIVENESS_FAILED,
                        "platform" to "android",
                        "app_version" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                        "detail" to reason,
                    ) + (score?.let { mapOf("score" to it.coerceIn(0, 100)) } ?: emptyMap())
                )
            } catch (e: Exception) {
                AppLog.info("Huni hata olayı gönderilemedi: ${e.javaClass.simpleName}", TAG)
            }
        }
    }

    /** Çip okumasının başarısız bittiği işareti — huni sırasının parçası DEĞİL. */
    const val STEP_NFC_FAILED = "nfc_failed"

    /**
     * Çip okuması başarısız bitti. `reason` sabit kümedendir (sunucu bilinmeyeni düşürür):
     * tag_lost | auth_failed | chip_unreadable | aa_failed | doc_unsupported | read_error.
     *
     * Neden gerekti: hata sebebi bugüne kadar YALNIZ canlılık adımı için toplanıyordu. NFC
     * muhtemelen en çok düşüş yaşanan adım ve "neden" sorusuna verecek tek satırımız yoktu —
     * kart mı kaydı, MRZ anahtarı mı çipi açamadı, belge mi desteklenmiyor: üçü bambaşka düzeltme.
     *
     * Akış başına ilk sebep kaydedilir. SESSİZ TEKRARLARDA gönderilmez: MainActivity üç kez
     * sessizce yeniden dener, olay ancak kullanıcı gerçekten hata ekranını gördüğünde düşer.
     */
    fun nfcFailed(reason: String, nonce: String?) {
        if (demoFlow) return
        if (nonce.isNullOrEmpty()) return
        if (!sentSteps.add(STEP_NFC_FAILED)) return

        val currentFlow = flowId
        scope.launch {
            try {
                RetrofitClient.api.flowEvent(
                    mapOf(
                        "nonce" to nonce,
                        "flow_id" to currentFlow,
                        "step" to STEP_NFC_FAILED,
                        "platform" to "android",
                        "app_version" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                        "detail" to reason,
                    )
                )
            } catch (e: Exception) {
                AppLog.info("Huni NFC hata olayı gönderilemedi: ${e.javaClass.simpleName}", TAG)
            }
        }
    }

    /**
     * TEK BİR HAREKETİN sonucu: hangi hareket, kaç ms sürdü, çözülene dek kaç yanlış yapıldı.
     *
     * Neden bu ayrıntı alınıyor da kare akışı alınmıyor: bu satırlar AKIŞLA büyür, kareyle değil.
     * Jest kümesi dört elemanlı ve `(flow_id, step)` benzersiz → akış başına en fazla dört satır.
     * Her frame'in ML Kit çıktısını göndermek ise kareyle büyürdü ve hiçbir kararı değiştirmezdi.
     *
     * Ne kararı değiştirir: gülümseme ortalama 9 saniye sürüyor ve göz kırpma 2 saniyeyse, ya jest
     * kümesi ya da ekrandaki yönerge değişir. `wrongCount` ise ayrı bir şey söyler — süre "zor mu"
     * derken o "komut anlaşılıyor mu" der: kullanıcı istenen yerine başka bir şey yapıyorsa sorun
     * hareketin kendisi değil, metnidir.
     *
     * Hareket TÜRÜ adıma gömülüdür ki "hangi hareket zor" sorusu doğrudan GROUP BY ile yanıtlansın.
     */
    fun gestureResolved(step: String, durationMs: Long, wrongCount: Int, timedOut: Boolean, nonce: String?) {
        if (demoFlow) return
        if (nonce.isNullOrEmpty()) return
        if (!sentSteps.add(step)) return

        val currentFlow = flowId
        scope.launch {
            try {
                RetrofitClient.api.flowEvent(
                    mapOf(
                        "nonce" to nonce,
                        "flow_id" to currentFlow,
                        "step" to step,
                        "platform" to "android",
                        "app_version" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                        "detail" to if (timedOut) "timeout" else "ok",
                        "duration_ms" to durationMs.coerceIn(0L, 120_000L).toInt(),
                        "wrong_count" to wrongCount.coerceIn(0, 10),
                    )
                )
            } catch (e: Exception) {
                AppLog.info("Huni hareket olayı gönderilemedi ($step): ${e.javaClass.simpleName}", TAG)
            }
        }
    }

    /**
     * Adıma ulaşıldı. Aynı adım bir akışta yalnız BİR kez gönderilir (sunucu tekrarı zaten yok
     * sayar, ama gereksiz istek atmayalım): kullanıcı NFC'yi üç kez denerse huni üç kişi göstermemeli.
     */
    fun reached(step: String, nonce: String?) {
        // Başarı akışın son adımıdır: etiket burada düşer ki başarıdan SONRAKİ olaylar (cüzdan,
        // ayarlar) bitmiş bir akışa bağlanmasın. Demo kapısının ÜSTÜNDE, çünkü demo akışı da
        // etiketi set etmiş olabilir.
        if (step == STEP_SUCCESS) endFlow()
        if (demoFlow) return
        if (nonce.isNullOrEmpty()) return
        if (!sentSteps.add(step)) return

        val currentFlow = flowId
        scope.launch {
            try {
                RetrofitClient.api.flowEvent(
                    mapOf(
                        "nonce" to nonce,
                        "flow_id" to currentFlow,
                        "step" to step,
                        "platform" to "android",
                        "app_version" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                    )
                )
            } catch (e: Exception) {
                // Telemetri sessizdir: kullanıcı akışı bu isteğin sonucuna bağlı değil.
                AppLog.info("Huni olayı gönderilemedi ($step): ${e.javaClass.simpleName}", TAG)
            }
        }
    }
}
