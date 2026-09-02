package com.verifyblind.mobile.backup

import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `.vfbackup` kripto çekirdeği — tüm-dosya AES-256-GCM, anahtar paroladan yerel PBKDF2 ile.
 *
 * İki platform (Android/iOS) BİT-BİT aynı sonucu üretmek zorundadır → değerler
 * `BackupCryptoTest` içindeki RFC 7914 golden vektörleriyle kilitlidir.
 *
 * **Neden elle PBKDF2 (JCE `PBEKeySpec` değil):** `PBEKeySpec`+`PBKDF2WithHmacSHA256` parolanın
 * char→byte dönüşümünü sağlayıcıya bırakır (Conscrypt/SunJCE farklı davranabilir). HMAC-SHA256
 * üzerine elle kurmak, parolayı NFC+UTF-8 baytlarına biz çevirdiğimiz için iOS CommonCrypto ile
 * aynı sonucu garanti eder.
 *
 * **Anahtar malzemesi yalnız:** kullanıcı parolası + dosyadaki rastgele salt. Kodda sabit/pepper
 * YOKTUR (public kod). Salt sır değildir; dosyada düz durur.
 */
object BackupCrypto {

    private const val HMAC_ALGO = "HmacSHA256"
    private const val HLEN = 32                 // SHA-256 çıktı uzunluğu
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12         // GCM standart IV

    /**
     * PBKDF2-HMAC-SHA256. Parola önce Unicode NFC'ye normalize edilir, sonra UTF-8 baytlarına
     * çevrilir (çapraz-platform tuzağı: iOS/Android klavyeleri farklı Unicode formu üretebilir).
     */
    fun deriveKey(password: String, salt: ByteArray, iterations: Int, dkLenBytes: Int = 32): ByteArray {
        val pwBytes = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(pwBytes, HMAC_ALGO))

        val blocks = (dkLenBytes + HLEN - 1) / HLEN
        val out = ByteArray(blocks * HLEN)

        for (i in 1..blocks) {
            // U1 = PRF(password, salt || INT_32_BE(i))
            mac.update(salt)
            mac.update(byteArrayOf(
                (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()
            ))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (c in 2..iterations) {
                u = mac.doFinal(u)           // doFinal aynı anahtarla Mac'i sıfırlar → yeniden kullanılabilir
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * HLEN, HLEN)
        }
        return out.copyOf(dkLenBytes)
    }

    /**
     * AES-256-GCM şifreler. Her çağrıda rastgele 12 baytlık IV üretir.
     * Dönüş: (iv, ciphertext‖16-bayt-GCM-tag) — Java `doFinal` tag'i ciphertext'e ekler.
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return Pair(iv, cipher.doFinal(plaintext))
    }

    /**
     * AES-256-GCM çözer. `ciphertextWithTag` = ciphertext‖tag. Yanlış anahtar / kurcalanmış veri
     * `AEADBadTagException` fırlatır (çağıran "yanlış parola / bozuk dosya" olarak yorumlar).
     */
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertextWithTag)
    }
}
