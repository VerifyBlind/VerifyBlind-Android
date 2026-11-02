package com.verifyblind.mobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kırpma kuralı: gözlerden BİRİNİN kapanması yeter. Komut emojisi (😉) tek göz kırpmayı
 * gösteriyor; sahada tek gözle kırpan kullanıcı algılanmadı (2026-09-25).
 */
class EventCollectorTest {

    @Test
    fun winkWithEitherEyeCounts() {
        assertTrue(EventCollector.isClosing(0.05f, 0.95f))
        assertTrue(EventCollector.isClosing(0.95f, 0.05f))
    }

    @Test
    fun bothEyesClosedCounts() {
        assertTrue(EventCollector.isClosing(0.05f, 0.05f))
    }

    /** Ağır iş yalnız kırpmada ertelenir; gülümseme/ağız açmada benzerlik güncellenmeye devam eder. */
    @Test
    fun onlyBlinksQuietTheHeavyWork() {
        assertTrue(EventCollector.quietFor(EventCollector.Event.BLINK))
        assertTrue(EventCollector.quietFor(EventCollector.Event.DOUBLE_BLINK))
        assertFalse(EventCollector.quietFor(EventCollector.Event.SMILE))
        assertFalse(EventCollector.quietFor(EventCollector.Event.MOUTH_OPEN))
    }

    @Test
    fun halfOpenEyesDoNotCount() {
        assertFalse(EventCollector.isClosing(0.5f, 0.5f))
        assertFalse(EventCollector.isClosing(0.25f, 0.9f))
    }

    @Test
    fun stillWindowStaysCoveredAtLowFrameRate() {
        val samples = ArrayDeque<Pair<Long, Float>>()
        for (t in 0L..760L step 190L) {
            samples.addLast(t to 1f)
            EventCollector.trimStillWindow(samples, t - 500L)
        }
        assert(760L - samples.first().first >= 500L)
        assert(samples.size == 4)
    }

    /** Enclave kanıtı sürüme göre ölçer: sürüm 2 giden kanıt yüzsüz kartta reddedilir. */
    @Test
    fun loginProofDefaultsToThreeMoveVersion() {
        val proof = com.verifyblind.mobile.api.ChoreographyProof(steps = emptyList())
        assert(proof.version == 3)
        assert(com.google.gson.Gson().toJson(proof).contains("\"version\":3"))
    }
}
