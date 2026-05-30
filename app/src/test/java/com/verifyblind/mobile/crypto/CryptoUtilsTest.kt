package com.verifyblind.mobile.crypto

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoUtils unit tests — JVM/Robolectric (no Android Keystore).
 *
 * Kapsamı:
 *   - AES-GCM encrypt/decrypt (random key, IV-embedded blob)
 *   - AES-GCM encrypt/decrypt (personId-derived key)
 *   - RSA-OAEP-SHA256 şifreleme round-trip (.NET Enclave uyumu)
 *   - RSA-OAEP-SHA1  şifreleme round-trip (Keystore soft-side)
 *   - OAEP spec uyumsuzluğu kontrolü
 *   - SHA-256 hash vektörleri
 *   - deriveKeyFromPersonId determinizm
 *
 * Kapsam dışı (AndroidKeystore donanım gerektiriyor — instrumentation testlerine bırakıldı):
 *   ensureKeyExists, ensureHistoryKeyExists, rsaDecryptHistory, getCipherForDecrypt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CryptoUtilsTest {

    // ─────────────────────────── AES-GCM (random key) ───────────────────────────

    @Test
    fun aesEncryptDecryptRoundTrip_succeeds() {
        val plaintext = "Zero-Knowledge Identity Test — Türkçe 😀"
        val (blob, key, _) = CryptoUtils.aesEncrypt(plaintext)
        val decrypted = CryptoUtils.aesDecrypt(blob, key)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun aesEncryptDecrypt_emptyString_roundTrip() {
        val (blob, key, _) = CryptoUtils.aesEncrypt("")
        assertEquals("", CryptoUtils.aesDecrypt(blob, key))
    }

    @Test
    fun aesEncrypt_differentCallsProduceDifferentBlobs() {
        // Her çağrı rastgele IV kullanmalı → aynı plaintext farklı ciphertext üretmeli
        val plaintext = "Same input"
        val (blob1, _, _) = CryptoUtils.aesEncrypt(plaintext)
        val (blob2, _, _) = CryptoUtils.aesEncrypt(plaintext)
        assertNotEquals("AES-GCM her şifrelemede farklı IV kullanmalı", blob1, blob2)
    }

    @Test
    fun aesDecrypt_wrongKey_throwsException() {
        val (blob, _, _) = CryptoUtils.aesEncrypt("Sensitive payload")
        val wrongKey = Base64.encodeToString(ByteArray(32) { 0x42 }, Base64.NO_WRAP)
        try {
            CryptoUtils.aesDecrypt(blob, wrongKey)
            fail("Yanlış anahtar ile şifre çözme başarısız olmalıydı")
        } catch (e: Exception) {
            // Bekleniyor: AEADBadTagException veya benzer GCM auth hatası
        }
    }

    // ─────────────────────────── AES-GCM (personId-derived key) ─────────────────

    @Test
    fun aesGcmEncryptDecryptRoundTrip_succeeds() {
        val data = "Cloud sync test payload"
        val personId = "test-person-abc123"
        val (ciphertext, iv) = CryptoUtils.aesGcmEncrypt(data, personId)
        val decrypted = CryptoUtils.aesGcmDecrypt(ciphertext, iv, personId)
        assertEquals(data, decrypted)
    }

    @Test
    fun aesGcmDecrypt_wrongPersonId_throwsException() {
        val data = "Sensitive cloud data"
        val (ciphertext, iv) = CryptoUtils.aesGcmEncrypt(data, "correct-person-id")
        try {
            CryptoUtils.aesGcmDecrypt(ciphertext, iv, "wrong-person-id")
            fail("Yanlış personId ile şifre çözme başarısız olmalıydı")
        } catch (e: Exception) {
            // Bekleniyor: GCM authentication tag mismatch
        }
    }

    @Test
    fun aesGcmEncrypt_differentIvEachCall() {
        val data = "Same data"
        val pid = "person-123"
        val (_, iv1) = CryptoUtils.aesGcmEncrypt(data, pid)
        val (_, iv2) = CryptoUtils.aesGcmEncrypt(data, pid)
        assertNotEquals("GCM IV her çağrıda farklı olmalı", iv1, iv2)
    }

    @Test
    fun aesGcmEncryptDecrypt_longPayload_succeeds() {
        val data = "A".repeat(10_000)
        val personId = "load-test-person"
        val (ct, iv) = CryptoUtils.aesGcmEncrypt(data, personId)
        assertEquals(data, CryptoUtils.aesGcmDecrypt(ct, iv, personId))
    }

    // ─────────────────────────── RSA-OAEP-SHA256 (.NET Enclave uyumu) ────────────

    @Test
    fun rsaEncrypt_oaepSha256_roundTrip() {
        // Yazılım RSA key pair (Keystore dışı) — enclave public key senaryosu
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pubKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

        val plaintext = "RSA OAEP-SHA256 cross-platform test"
        val encrypted = CryptoUtils.rsaEncrypt(plaintext, pubKeyB64)

        // .NET tarafının kullandığı spec ile aynı: OAEP-SHA256 / MGF1-SHA256
        val oaepSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, kp.private, oaepSpec)
        val decrypted = String(
            cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)),
            StandardCharsets.UTF_8
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun rsaEncrypt_producesBase64Output() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pubKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val encrypted = CryptoUtils.rsaEncrypt("test", pubKeyB64)

        // Çıktı geçerli Base64 olmalı (exception fırlamamalı)
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        assertEquals(256, decoded.size) // RSA-2048 → 256 byte ciphertext
    }

    // ─────────────────────────── RSA-OAEP-SHA1 (Keystore soft-side) ─────────────

    @Test
    fun rsaEncryptForKeystore_oaepSha1_roundTrip() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pubKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

        val plaintext = "RSA OAEP-SHA1 keystore compatibility test"
        val encrypted = CryptoUtils.rsaEncryptForKeystore(plaintext, pubKeyB64)

        // Android Keystore TEE beklediği spec: OAEP-SHA1 / MGF1-SHA1
        val oaepSpec = OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, kp.private, oaepSpec)
        val decrypted = String(
            cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)),
            StandardCharsets.UTF_8
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun rsaEncrypt_sha256_notCompatibleWith_sha1() {
        // OAEP-SHA256 ile şifrelenmiş veriyi OAEP-SHA1 ile çözmek başarısız olmalı
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pubKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

        val encrypted = CryptoUtils.rsaEncrypt("cross-spec test", pubKeyB64) // SHA256

        val wrongSpec = OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, kp.private, wrongSpec)
        try {
            cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))
            fail("SHA-256 ciphertext'i SHA-1 spec ile çözmek başarısız olmalıydı")
        } catch (e: Exception) {
            // Bekleniyor
        }
    }

    // ─────────────────────────── SHA-256 ──────────────────────────────────────────

    @Test
    fun sha256_emptyString_knownVector() {
        // SHA-256("") bilinen NIST test vektörü
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CryptoUtils.sha256("")
        )
    }

    @Test
    fun sha256_matchesJvmMessageDigest() {
        // CryptoUtils.sha256 çıktısı, JVM MessageDigest referansıyla eşleşmeli
        val input = "VerifyBlind-SHA256-CrossCheck"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, CryptoUtils.sha256(input))
    }

    @Test
    fun sha256_outputIs64HexChars() {
        val hash = CryptoUtils.sha256("any input")
        assertEquals(64, hash.length)
        assertTrue("Hex karakter dışı değer var", hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun sha256Bytes_stringAndByteArrayOverloads_match() {
        val input = "consistency-check"
        val fromString = CryptoUtils.sha256Bytes(input)
        val fromBytes = CryptoUtils.sha256Bytes(input.toByteArray(StandardCharsets.UTF_8))
        assertArrayEquals(fromString, fromBytes)
    }

    @Test
    fun sha256_deterministicForSameInput() {
        val input = "determinism-test"
        assertEquals(CryptoUtils.sha256(input), CryptoUtils.sha256(input))
    }

    @Test
    fun sha256_differentInputsDifferentHashes() {
        assertNotEquals(CryptoUtils.sha256("input-a"), CryptoUtils.sha256("input-b"))
    }

    // ─────────────────────────── deriveKeyFromPersonId ───────────────────────────

    @Test
    fun deriveKeyFromPersonId_deterministic() {
        val pid = "person-abc-xyz-789"
        val key1 = CryptoUtils.deriveKeyFromPersonId(pid)
        val key2 = CryptoUtils.deriveKeyFromPersonId(pid)
        assertArrayEquals(key1.encoded, key2.encoded)
    }

    @Test
    fun deriveKeyFromPersonId_differentIdsDifferentKeys() {
        val key1 = CryptoUtils.deriveKeyFromPersonId("person-1")
        val key2 = CryptoUtils.deriveKeyFromPersonId("person-2")
        assertFalse(
            "Farklı personId'ler aynı anahtar üretmemeli",
            key1.encoded.contentEquals(key2.encoded)
        )
    }

    @Test
    fun deriveKeyFromPersonId_produces256BitAesKey() {
        val key = CryptoUtils.deriveKeyFromPersonId("test-person")
        assertEquals("AES", key.algorithm)
        assertEquals(256, key.encoded.size * 8)
    }

    @Test
    fun deriveKeyFromPersonId_keyCanBeUsedForAesGcm() {
        // Türetilen anahtar gerçekten AES-GCM şifreleme için geçerli olmalı
        val key = CryptoUtils.deriveKeyFromPersonId("valid-person-id")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal("test".toByteArray())
        assertNotNull(ct)
        assertTrue(ct.isNotEmpty())
    }
}
