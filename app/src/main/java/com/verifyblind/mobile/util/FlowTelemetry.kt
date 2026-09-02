package com.verifyblind.mobile.util

import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.api.RetrofitClient
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
    private val sentSteps = Collections.synchronizedSet(mutableSetOf<String>())

    /** Yeni bir kart ekleme denemesi başladı — yeni gruplama anahtarı. */
    fun startFlow() {
        flowId = UUID.randomUUID().toString()
        sentSteps.clear()
    }

    /** Huni SIRASININ parçası değil — canlılık adımının neden kaybedildiğini işaretler. */
    const val STEP_LIVENESS_FAILED = "liveness_failed"

    /**
     * Canlılık testi başarısız bitti. `reason` sabit kümedendir (sunucu bilinmeyeni düşürür):
     * timeout_gesture | timeout_session | too_many_errors | match_failed | no_selfie.
     * Akış başına ilk sebep kaydedilir — tekrar denemeler istatistiği şişirmesin.
     */
    fun livenessFailed(reason: String, nonce: String?) {
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
                    )
                )
            } catch (e: Exception) {
                AppLog.info("Huni hata olayı gönderilemedi: ${e.javaClass.simpleName}", TAG)
            }
        }
    }

    /**
     * Adıma ulaşıldı. Aynı adım bir akışta yalnız BİR kez gönderilir (sunucu tekrarı zaten yok
     * sayar, ama gereksiz istek atmayalım): kullanıcı NFC'yi üç kez denerse huni üç kişi göstermemeli.
     */
    fun reached(step: String, nonce: String?) {
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
