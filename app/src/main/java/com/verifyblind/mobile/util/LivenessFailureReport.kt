package com.verifyblind.mobile.util

/**
 * Canlılık başarısızlığının Sentry kaydı: SABİT mesaj + yapısal alanlar.
 *
 * Mesaj yalnız sınırlı bir kümeden (`reason`) oluşur. Skor, adım ve kare ölçüleri mesaja
 * girerse Sentry her denemeyi ayrı bir issue olarak açar — mesajı olayın grup anahtarı sayıyor
 * (VERIFYBLIND-ANDROID-1S: başlıkta `skor=84% luma=103 …`). Değerler [extras]'ta kalır, iz kaybolmaz.
 * iOS `LivenessViewModel.failureReport` paritesi.
 */
object LivenessFailureReport {

    data class Report(val message: String, val extras: Map<String, String>)

    fun build(
        reason: String,
        completedSteps: Int,
        totalSteps: Int,
        wrongCount: Int,
        bestScorePercent: Int,
        frameMetrics: String?,
    ): Report = Report(
        message = "Liveness başarısız (reason=$reason)",
        extras = mapOf(
            "steps" to "$completedSteps/$totalSteps",
            "wrong" to wrongCount.toString(),
            "best_score" to "$bestScorePercent%",
            "frame_metrics" to (frameMetrics ?: "kare ölçüsü yok"),
        ),
    )
}
