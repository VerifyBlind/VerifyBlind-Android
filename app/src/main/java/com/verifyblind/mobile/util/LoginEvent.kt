package com.verifyblind.mobile.util

import java.security.MessageDigest

/**
 * Doğrulamanın TEK hareketi — QR isteğinin nonce'undan türetilir (2026-09-26).
 *
 * Enclave `ChoreographyGenerator.ForLogin` kuralının BİREBİR kopyası. Neden istemcide de var:
 * login-handshake QR okunmadan, nonce bilinmeden hazırlanıyor; sunucunun hareketi söyleyeceği bir
 * tur yok. Karar yine enclave'de — burada yanlış türetilirse kanıt istenen hareketle uyuşmaz ve
 * doğrulama reddedilir. Türetme değişirse enclave, Android ve iOS birlikte değişir; test
 * vektörleri üçünde de aynı ([LoginEventTest]).
 *
 * Düz kırpma listede YOK: her videoda kendiliğinden var, tek hareketlik dizide videoyu
 * zorlamaz.
 */
object LoginEvent {

    private const val DOMAIN = "vb-choreo-login-v2|"

    /** Sıra türetmenin parçası — enclave'deki `LoginEvents` ile aynı, değiştirilmez. */
    private val EVENTS = listOf(
        EventCollector.Event.SMILE, EventCollector.Event.MOUTH_OPEN, EventCollector.Event.DOUBLE_BLINK)

    fun forNonce(nonce: String): EventCollector.Event {
        require(nonce.isNotEmpty()) { "nonce boş" }
        val draw = DeterministicDraw(sha256((DOMAIN + nonce).toByteArray(Charsets.UTF_8)))
        return EVENTS[draw.next(EVENTS.size)]
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * SHA-256 sayaç kipi, reddetme örneklemeli yansız çekiliş — enclave `DeterministicDraw`.
     * Blok = SHA256(tohum ‖ sayaç, 4 bayt little-endian); değer = bloktan 4 bayt little-endian.
     */
    private class DeterministicDraw(private val seed: ByteArray) {
        private var block = ByteArray(0)
        private var offset = 0
        private var counter = 0L

        fun next(n: Int): Int {
            val max = 0xFFFF_FFFFL
            val limit = max - (max % n)
            while (true) {
                val v = nextUInt()
                if (v < limit) return (v % n).toInt()
            }
        }

        private fun nextUInt(): Long {
            if (offset + 4 > block.size) {
                val c = counter++
                block = sha256(seed + byteArrayOf(
                    c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), (c shr 24).toByte()))
                offset = 0
            }
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (block[offset + i].toLong() and 0xFF)
            offset += 4
            return v
        }
    }
}
