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
}
