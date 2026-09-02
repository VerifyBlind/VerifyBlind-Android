package com.verifyblind.mobile.nfc

/**
 * NFC okumasından SONRA, liveness'e geçmeden ÖNCE belgenin VerifyBlind akışıyla uyumlu olup
 * olmadığını belirler. Amaç: hızlı-başarısızlık — desteklenmeyen belgeyi kullanıcıyı tüm
 * liveness'ten geçirip en sonda enclave'in kriptik hatasına çarptırmak yerine, hemen net bir
 * mesajla durdurmak.
 *
 * **Kabul kuralı: yalnızca Türkiye Cumhuriyeti kimlik kartı.**
 *   1. İhraç eden ülke `TUR` olmalı.
 *   2. ICAO belge kodu `I` ya da `ID` olmalı (TD1 kimlik kartı). Pasaport (`P`) kabul EDİLMEZ.
 *   3. DG2 yüz görüntüsü JPEG olmalı. Android BitmapFactory ve enclave ImageSharp JPEG2000 (JP2)
 *      çözemez.
 *   4. Active Authentication (DG15 + Aktif İmza) bulunmalı. Enclave AA'yı sert zorunlu tutar
 *      (anti-downgrade); AA'sız (yalnız Chip-Auth'lu) belgeler reddedilir.
 *
 * Türk pasaportu bilinçli olarak kapalı: DG2'si JPEG2000 olabilir ve AA yerine yalnız Chip
 * Authentication kullanıyor olabilir — gerçek bir pasaportla test edilmeden açılmamalı.
 *
 * Bu sınıf kullanıcıya erken mesaj göstermek içindir; **otorite enclave'dedir**
 * (`DocumentPolicy.cs`) — değiştirilmiş bir istemci buradaki kapıyı atlayabilir.
 *
 * Saf-byte/saf-string mantığı (jMRTD tipi bağımlılığı yok) → birim testlerle kapsanır.
 */
object DocumentSupport {

    /** Kabul edilen tek ihraç ülkesi (ICAO 3-harf kodu). */
    const val ACCEPTED_COUNTRY = "TUR"

    /**
     * Kabul edilen ICAO belge kodları. TD1 kimlik kartında MRZ satır 1 "I<TUR.." ise kod "I",
     * "IDTUR.." ise "ID" olur — ikisi de aynı fiziksel belgedir (üretim yılına göre değişir).
     */
    val ACCEPTED_DOCUMENT_CODES = setOf("I", "ID")

    enum class Verdict {
        /** TC kimlik kartı + JPEG DG2 + Active Auth → tam akış desteklenir. */
        SUPPORTED,
        /** İhraç eden ülke Türkiye değil. */
        UNSUPPORTED_COUNTRY,
        /** Ülke Türkiye ama belge kimlik kartı değil (ör. pasaport). */
        UNSUPPORTED_DOC_TYPE,
        /** DG2'den yüz görüntüsü çıkarılamadı (biyometri imkânsız). */
        NO_FACE_IMAGE,
        /** DG2 var ama JPEG değil (ör. JPEG2000) → ne Android ne enclave çözebilir. */
        UNSUPPORTED_IMAGE,
        /** DG15/Aktif İmza yok → enclave AA zorunluluğu reddeder (ERR_ACTIVE_AUTH). */
        NO_ACTIVE_AUTH,

        /**
         * İmza var ama yapısı bozuk — okuma sırasında veri bozulmuş. Belge DESTEKSİZ DEĞİL;
         * kullanıcı kartı yeniden okutmalı (bkz. ChipSignatureCheck).
         */
        CHIP_SIGNATURE_UNREADABLE
    }

    /**
     * Belgeyi değerlendirir.
     *
     * Sıra önemlidir: **ülke → belge tipi → görüntü → AA.** Yabancı bir pasaportta kullanıcıya
     * "çip fotoğrafı JPEG2000" demek yanıltıcı olurdu (formatı düzeltirse kabul edilecekmiş gibi
     * okunur); doğru mesaj "yalnızca TC kimlik kartı"dır. Görüntü sorunu da AA'dan önce raporlanır
     * — kullanıcının ilk gördüğü problem fotoğrafın görünmemesidir ve biyometri görüntü olmadan
     * zaten yapılamaz.
     */
    fun evaluate(
        issuingState: String?,
        documentCode: String?,
        faceImage: ByteArray?,
        dg15: ByteArray?,
        activeSig: ByteArray?,
        chipSignatureReadable: Boolean = true,
    ): Verdict {
        if (normalize(issuingState) != ACCEPTED_COUNTRY) return Verdict.UNSUPPORTED_COUNTRY
        if (normalize(documentCode) !in ACCEPTED_DOCUMENT_CODES) return Verdict.UNSUPPORTED_DOC_TYPE
        if (faceImage == null || faceImage.isEmpty()) return Verdict.NO_FACE_IMAGE
        if (!isJpeg(faceImage)) return Verdict.UNSUPPORTED_IMAGE
        if (dg15 == null || dg15.isEmpty() || activeSig == null || activeSig.isEmpty()) return Verdict.NO_ACTIVE_AUTH
        // AA verisi VAR ama okuma bozulmuş → belge sorunu değil, okuma sorunu. En sona konur:
        // gerçekten desteklenmeyen bir belgeye "tekrar okutun" demek yanıltıcı olurdu.
        if (!chipSignatureReadable) return Verdict.CHIP_SIGNATURE_UNREADABLE
        return Verdict.SUPPORTED
    }

    /**
     * MRZ alanlarını karşılaştırmaya hazırlar: büyük harf, ICAO dolgu karakteri '<' ve boşluk
     * atılır. jMRTD bu alanları genelde kırpılmış döndürür; bu savunmacı normalizasyon farklı
     * kırpma davranışlarında kararın değişmemesini garanti eder.
     */
    private fun normalize(value: String?): String =
        value?.replace("<", "")?.trim()?.uppercase() ?: ""

    /**
     * JPEG SOI işareti (FF D8 FF). JPEG2000 JP2 (00 00 00 0C 6A 50 ..) ve ham J2K codestream
     * (FF 4F FF 51) bu kontrolden geçemez → reddedilir.
     */
    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
}
