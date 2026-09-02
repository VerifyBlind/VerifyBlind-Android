package com.verifyblind.mobile.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * `.vfbackup` kripto çekirdeği testleri — saf JVM (Android framework bağımlılığı yok).
 *
 * Bu sınıf çapraz-platform SÖZLEŞMEyi kilitler: buradaki golden vektörler iOS tarafının da
 * (CommonCrypto) birebir üretmesi gereken değerlerdir. PBKDF2 vektörleri RFC 7914 (scrypt)
 * Bölüm 11'den — PBKDF2-HMAC-SHA256 için yetkili, yayımlanmış test vektörleri.
 *
 * Anahtar türetimi bilinçli olarak `Mac(HmacSHA256)` üzerine ELLE kuruludur (JCE `PBEKeySpec`
 * değil): sağlayıcının parola char→byte dönüşümü belirsizliğini ortadan kaldırıp iOS ile
 * bit-bit aynı sonucu garanti eder.
 */
class BackupCryptoTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ── PBKDF2-HMAC-SHA256 golden vektörleri (RFC 7914 §11) ──

    @Test
    fun deriveKey_matchesRfc7914Vector_iterations1() {
        val out = BackupCrypto.deriveKey(
            password = "passwd",
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 1,
            dkLenBytes = 64
        )
        val expected = "55ac046e56e3089fec1691c22544b605" +
            "f9418521 6dde0465 e68b9d57 c20dacbc".replace(" ", "") +
            "49ca9ccc f179b645 991664b3 9d77ef31".replace(" ", "") +
            "7c71b845 b1e30bd5 09112041 d3a19783".replace(" ", "")
        assertEquals(expected, hex(out))
    }

    @Test
    fun deriveKey_matchesRfc7914Vector_iterations80000() {
        val out = BackupCrypto.deriveKey(
            password = "Password",
            salt = "NaCl".toByteArray(Charsets.UTF_8),
            iterations = 80000,
            dkLenBytes = 64
        )
        val expected = ("4ddcd8f6 0b98be21 830cee5e f22701f9" +
            "641a4418 d04c0414 aeff0887 6b34ab56" +
            "a1d425a1 22583354 9adb841b 51c9b317" +
            "6a272bde bba1d078 478f62b3 97f33c8d").replace(" ", "")
        assertEquals(expected, hex(out))
    }

    @Test
    fun deriveKey_defaultLengthIs32Bytes() {
        val out = BackupCrypto.deriveKey("passwd", "salt".toByteArray(Charsets.UTF_8), 1)
        assertEquals(32, out.size)
        // İlk 32 bayt, 64-baytlık vektörün önekiyle aynı olmalı (PBKDF2 blok yapısı gereği).
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(out))
    }

    // ── Parola NFC normalizasyonu (çapraz-platform tuzağı) ──

    @Test
    fun deriveKey_normalizesPasswordToNfc() {
        // "é" birleşik (U+00E9) vs ayrık (e + U+0301). NFC olmadan farklı bayt → farklı anahtar.
        val composed = "café"       // café, tek kod noktası
        val decomposed = "café"    // café, e + combining acute
        val salt = "salt".toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            BackupCrypto.deriveKey(composed, salt, 100),
            BackupCrypto.deriveKey(decomposed, salt, 100)
        )
    }

    // ── AES-256-GCM ──

    @Test
    fun encryptThenDecrypt_roundTrips() {
        val key = BackupCrypto.deriveKey("hunter2-strong", "salt".toByteArray(Charsets.UTF_8), 1000)
        val plaintext = "{\"records\":[{\"nonce\":\"abc\"}]}".toByteArray(Charsets.UTF_8)

        val (iv, ct) = BackupCrypto.encrypt(plaintext, key)
        val recovered = BackupCrypto.decrypt(iv, ct, key)

        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun encrypt_usesFreshIvPerCall() {
        val key = BackupCrypto.deriveKey("pw", "salt".toByteArray(Charsets.UTF_8), 1000)
        val data = "same".toByteArray(Charsets.UTF_8)
        val (iv1, _) = BackupCrypto.encrypt(data, key)
        val (iv2, _) = BackupCrypto.encrypt(data, key)
        assertEquals(12, iv1.size) // GCM standart 12 baytlık IV
        assertNotEquals(hex(iv1), hex(iv2))
    }

    @Test
    fun decrypt_withWrongKey_failsAuthentication() {
        val key = BackupCrypto.deriveKey("right", "salt".toByteArray(Charsets.UTF_8), 1000)
        val wrong = BackupCrypto.deriveKey("wrong", "salt".toByteArray(Charsets.UTF_8), 1000)
        val (iv, ct) = BackupCrypto.encrypt("secret".toByteArray(Charsets.UTF_8), key)
        try {
            BackupCrypto.decrypt(iv, ct, wrong)
            fail("Yanlış anahtarla çözme GCM tag doğrulamasında başarısız olmalıydı")
        } catch (e: AEADBadTagException) {
            // beklenen
        }
    }

    @Test
    fun decrypt_withTamperedCiphertext_failsAuthentication() {
        val key = BackupCrypto.deriveKey("pw", "salt".toByteArray(Charsets.UTF_8), 1000)
        val (iv, ct) = BackupCrypto.encrypt("secret".toByteArray(Charsets.UTF_8), key)
        ct[0] = (ct[0].toInt() xor 0x01).toByte() // tek bit çevir
        try {
            BackupCrypto.decrypt(iv, ct, key)
            fail("Kurcalanmış ciphertext GCM tag doğrulamasında başarısız olmalıydı")
        } catch (e: AEADBadTagException) {
            // beklenen
        }
    }
}
