package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canlılık başarısızlığının Sentry mesajı değerlerden BAĞIMSIZ olmalı.
 *
 * Regresyon zemini: VERIFYBLIND-ANDROID-1S — mesaj `skor=84% luma=103 …` taşıyordu ve Sentry
 * mesajı grup anahtarı saydığı için her deneme ayrı bir issue açıyordu.
 */
class LivenessFailureReportTest {

    private fun report(score: Int, metrics: String?, steps: Int = 3, wrong: Int = 0) =
        LivenessFailureReport.build("too_many_errors", steps, 5, wrong, score, metrics)

    @Test
    fun message_isIndependentOfMeasuredValues() {
        val a = report(84, "luma=103 sharp=474", steps = 3, wrong = 0)
        val b = report(12, "luma=40 sharp=90", steps = 1, wrong = 2)

        assertEquals(a.message, b.message)
        assertEquals("Liveness başarısız (reason=too_many_errors)", a.message)
    }

    @Test
    fun message_keepsTheBoundedReason() {
        // Sebep sınırlı bir küme (timeout / too_many_errors / match_or_selfie): ayrı issue olması işe yarar.
        val timeout = LivenessFailureReport.build("timeout", 0, 5, 0, 0, null)
        assertTrue(timeout.message.contains("reason=timeout"))
    }

    @Test
    fun values_moveToExtras() {
        val r = report(84, "luma=103 sharp=474")

        assertEquals("3/5", r.extras["steps"])
        assertEquals("0", r.extras["wrong"])
        assertEquals("84%", r.extras["best_score"])
        assertEquals("luma=103 sharp=474", r.extras["frame_metrics"])
        assertFalse(r.message.contains("84"))
    }

    @Test
    fun missingFrameMetrics_isExplicit() {
        assertEquals("kare ölçüsü yok", report(0, null).extras["frame_metrics"])
    }
}
