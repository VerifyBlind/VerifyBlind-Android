package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Attestation ↔ payload (card_id) bağlama sözleşmesinin golden-vector testi.
 *
 * Bu golden değer sunucu tarafındaki golden-vector testi ve iOS Stage6SelfTest ile
 * BİREBİR aynıdır. Encoding'de en küçük kayma (ayraç, UTF-8,
 * Base64 varyantı) burada yakalanır; yakalanmazsa block-card sunucuda sessizce RED alır.
 *
 * Sözleşme: requestHash = Base64( SHA256( UTF8( fresh + "\n" + cardId ) ) )
 */
class AttestationBindingTest {

    private val goldenFresh = "11111111-1111-1111-1111-111111111111"
    private val goldenCard = "CARD_TEST_0001"
    private val goldenBase64 = "d8utuuasuOl65fOPE/J/rzOKudmIpbA+Ab2JA4BIdJQ="

    @Test
    fun requestHash_matchesServerGoldenVector() {
        assertEquals(goldenBase64, AttestationBinding.requestHash(goldenFresh, goldenCard))
    }

    @Test
    fun requestHash_differentCardId_producesDifferentHash() {
        assertNotEquals(
            AttestationBinding.requestHash(goldenFresh, "CARD_A"),
            AttestationBinding.requestHash(goldenFresh, "CARD_B")
        )
    }

    @Test
    fun requestHash_differentNonce_producesDifferentHash() {
        assertNotEquals(
            AttestationBinding.requestHash("nonce-A", goldenCard),
            AttestationBinding.requestHash("nonce-B", goldenCard)
        )
    }
}
