package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 Enclave `ChoreographyGeneratorTests.GirisTuretmesiSabittir` ile AYNI vektörler (iOS
 * `LoginEventTests` de öyle). Biri kırılırsa istemci enclave'in istemediği hareketi yaptırır ve
 * her doğrulama reddedilir.
 */
class LoginEventTest {

    @Test
    fun matchesEnclaveVectors() {
        assertEquals(EventCollector.Event.SMILE, LoginEvent.forNonce("00000000000000000000000000000000"))
        assertEquals(EventCollector.Event.MOUTH_OPEN, LoginEvent.forNonce("b6f1c1c56d0a4b8e9d3a2f5c7e8a9b10"))
        assertEquals(EventCollector.Event.MOUTH_OPEN, LoginEvent.forNonce("7c9e6679-7425-40de-944b-e07fc1f90ae7"))
        assertEquals(EventCollector.Event.DOUBLE_BLINK, LoginEvent.forNonce("vector-0"))
        assertEquals(EventCollector.Event.SMILE, LoginEvent.forNonce("A1b2-C3d4"))
    }

    @Test
    fun neverAsksForAPlainBlinkAndUsesAllThree() {
        val seen = (0 until 3000).map { LoginEvent.forNonce("n-$it") }.groupingBy { it }.eachCount()
        assertTrue(EventCollector.Event.BLINK !in seen)
        for (ev in listOf(EventCollector.Event.SMILE, EventCollector.Event.MOUTH_OPEN, EventCollector.Event.DOUBLE_BLINK))
            assertTrue("$ev: ${seen[ev]}", (seen[ev] ?: 0) > 850)
    }
}
