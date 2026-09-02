package com.verifyblind.mobile.nfc

import com.verifyblind.mobile.util.AppLog
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PublicKeyFactory

/**
 * Çip imzasının **okunabilir** olup olmadığını NFC adımında anlar — iOS `ChipSignatureCheck` ile
 * birebir aynı kural.
 *
 * ## Neden var
 * Çip bazı okumalarda bozuk/eksik bir Aktif Kimlik Doğrulama yanıtı döndürüyor (kart oynadı,
 * kuplaj zayıf kaldı). Kullanıcı bunu bilmeden ~90 saniyelik canlılık testini yapıyor ve en sonda
 * sunucudan "çip doğrulanamadı" yiyordu (2026-08-24 vakası: aynı kartın ardışık iki okumasından
 * biri sağlam, diğeri bozuk). Bozukluğu NFC adımında yakalarsak kullanıcı kartı hemen yeniden okutur.
 *
 * ## Neden DOĞRULAMA değil, YAPI kontrolü
 * İstemci **sunucudan daha katı olamaz**. Buradaki kontrol hash'i doğrulamaz, imzayı kabul/ret
 * etmez; yalnız çözülen bloğun ISO 9796-2 (ya da PKCS#1) şeklinde OLUP OLMADIĞINA bakar. Tam
 * doğrulamayı istemciye taşımak, enclave'in kabul ettiği bir varyantı istemcinin tanımaması riskini
 * doğururdu — bu haftanın hatası tam olarak buydu (enclave yalnız SHA-256/açık trailer tanıyordu ve
 * SHA-1/örtük kartları reddediyordu). Yapı kontrolünde böyle bir sapma mümkün değil: enclave'in
 * kabul ettiği HER varyant bu testten geçer.
 *
 * Bozuk okumada gözlenen blok: `hdr=4A … tr=C1AD` — başlık makul, trailer hiçbir şemaya uymuyor.
 */
object ChipSignatureCheck {

    private const val TAG = "ChipSignature"

    /**
     * Blok yapısı bir imza bloğuna benziyor mu? (Hash DOĞRULANMAZ.)
     *
     * - ISO 9796-2: ilk baytın üst yarısı `0x4` (tam kurtarma) ya da `0x6` (kısmi kurtarma),
     *   son bayt `0xBC` (örtük trailer) veya `0xCC` (açık trailer'ın son baytı).
     * - PKCS#1 v1.5: blok `0x01` ile başlar (baştaki `0x00` ham RSA çıktısında görünmez).
     */
    fun looksLikeSignatureBlock(block: ByteArray): Boolean {
        if (block.size < 3) return false
        val first = block.first().toInt() and 0xFF
        val last = block.last().toInt() and 0xFF
        val head = first and 0xF0
        val isIso = (head == 0x40 || head == 0x60) && (last == 0xBC || last == 0xCC)
        val isPkcs1 = first == 0x01
        return isIso || isPkcs1
    }

    /**
     * DG15'teki açık anahtarla ham RSA uygulayıp blok yapısını kontrol eder.
     *
     * Karar veremediğimiz her durumda (anahtar ayrıştırılamadı, RSA çalışmadı…) `true` döner:
     * **şüphede kullanıcıyı durdurmayız**, kararı sunucuya bırakırız.
     */
    fun isReadable(dg15: ByteArray?, signature: ByteArray?): Boolean {
        if (dg15 == null || signature == null || dg15.isEmpty() || signature.isEmpty()) return true

        return try {
            val spki = spkiFromDg15(dg15) ?: return true
            val key = PublicKeyFactory.createKey(SubjectPublicKeyInfo.getInstance(spki))
            if (key !is RSAKeyParameters) return true

            val keyLen = (key.modulus.bitLength() + 7) / 8
            if (signature.size < keyLen) return true
            val sig = signature.copyOfRange(signature.size - keyLen, signature.size)

            // Ham RSA (padding yok) = m^e mod n.
            val engine = RSAEngine().apply { init(false, key) }
            val block = engine.processBlock(sig, 0, sig.size)

            // Ham çıktı baştaki sıfırlarla gelebilir; blok ilk anlamlı bayttan başlar.
            val trimmed = block.dropWhile { it.toInt() == 0 }.toByteArray()
            val ok = looksLikeSignatureBlock(trimmed)
            if (!ok) {
                AppLog.warning(
                    "Çip imzası okunabilir değil (blok yapısı bozuk) — yeniden okutma gerekiyor", TAG
                )
            }
            ok
        } catch (e: Exception) {
            // Kararsızlıkta kullanıcıyı durdurma — sunucu zaten doğrulayacak.
            AppLog.info("Çip imza yapısı kontrol edilemedi (${e.javaClass.simpleName})", TAG)
            true
        }
    }

    /** DG15 = `0x6F | uzunluk | SubjectPublicKeyInfo`. Sarmalayıcı yoksa girdi zaten SPKI'dır. */
    private fun spkiFromDg15(dg15: ByteArray): ByteArray? {
        if (dg15.size <= 4) return null
        if ((dg15[0].toInt() and 0xFF) != 0x6F) return dg15

        var offset = 1
        val lengthByte = dg15[offset].toInt() and 0xFF
        var length: Int
        if (lengthByte and 0x80 == 0) {
            length = lengthByte
            offset += 1
        } else {
            val count = lengthByte and 0x7F
            offset += 1
            if (count <= 0 || count > 4 || offset + count > dg15.size) return null
            length = 0
            repeat(count) {
                length = (length shl 8) or (dg15[offset].toInt() and 0xFF)
                offset += 1
            }
        }
        if (length <= 0 || offset + length > dg15.size) return null
        return dg15.copyOfRange(offset, offset + length)
    }
}
