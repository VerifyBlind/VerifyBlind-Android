package com.verifyblind.mobile.nfc

import org.junit.Assert.*
import org.junit.Test

/**
 * DocumentSupport birim testleri.
 *
 * Kapsam: NFC okumasından SONRA, liveness'e geçmeden ÖNCE belgenin VerifyBlind akışıyla
 * uyumlu olup olmadığını saf-byte/saf-string düzeyinde belirler (jMRTD tipi gerektirmez → kolay test).
 *
 *   - TUR + I/ID + JPEG DG2 + Active Auth (DG15+imza) → SUPPORTED
 *   - İhraç ülkesi TUR değil → UNSUPPORTED_COUNTRY
 *   - TUR ama kimlik kartı değil (ör. pasaport "P") → UNSUPPORTED_DOC_TYPE
 *   - DG2 yüz görüntüsü yok → NO_FACE_IMAGE
 *   - DG2 JPEG değil (ör. JPEG2000 DG2'li pasaport) → UNSUPPORTED_IMAGE
 *   - DG15/Aktif İmza yok (ör. yalnız Chip-Auth'lu belge) → NO_ACTIVE_AUTH
 */
class DocumentSupportTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
    // JPEG2000 JP2 signature box: 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
    private val jp2 = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A)
    // Ham J2K codestream: FF 4F FF 51
    private val j2kRaw = byteArrayOf(0xFF.toByte(), 0x4F.toByte(), 0xFF.toByte(), 0x51.toByte())
    private val dg15 = byteArrayOf(0x6F, 0x10, 0x30, 0x0E) // sahte ama boş değil
    private val sig = byteArrayOf(0x01, 0x02, 0x03)

    /** TC kimlik kartı varsayılanı — testler yalnız ilgilendikleri alanı değiştirir. */
    private fun evaluate(
        issuingState: String? = "TUR",
        documentCode: String? = "I",
        faceImage: ByteArray? = jpeg,
        dg15: ByteArray? = this.dg15,
        activeSig: ByteArray? = sig
    ) = DocumentSupport.evaluate(issuingState, documentCode, faceImage, dg15, activeSig)

    // ── Kabul ────────────────────────────────────────────────────────────────

    @Test
    fun turkishIdCard_isSupported() {
        assertEquals(DocumentSupport.Verdict.SUPPORTED, evaluate())
    }

    @Test
    fun turkishIdCard_twoLetterDocumentCode_isSupported() {
        // Bazı kartlar "I<" yerine "ID" taşır — ikisi de aynı fiziksel belge.
        assertEquals(DocumentSupport.Verdict.SUPPORTED, evaluate(documentCode = "ID"))
    }

    @Test
    fun mrzFillerAndLowercase_areNormalized() {
        // jMRTD alanları kırpmadan döndürürse ya da beklenmedik biçimde verirse karar değişmemeli.
        assertEquals(DocumentSupport.Verdict.SUPPORTED, evaluate(issuingState = "tur", documentCode = "i<"))
        assertEquals(DocumentSupport.Verdict.SUPPORTED, evaluate(issuingState = " TUR ", documentCode = "I<"))
    }

    // ── Ülke kapısı ──────────────────────────────────────────────────────────

    @Test
    fun foreignIdCard_isUnsupportedCountry() {
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_COUNTRY, evaluate(issuingState = "DEU"))
    }

    @Test
    fun foreignPassport_isUnsupportedCountry() {
        // Ülke kapısı belge tipinden ÖNCE gelir: yabancı pasaportta "pasaport desteklenmiyor"
        // demek yanıltıcı olurdu (Türk olsaydı kabul edilecekmiş gibi okunur).
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_COUNTRY, evaluate(issuingState = "USA", documentCode = "P"))
    }

    @Test
    fun missingIssuingState_isUnsupportedCountry() {
        // Fail-closed: ülke okunamadıysa kabul etme.
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_COUNTRY, evaluate(issuingState = null))
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_COUNTRY, evaluate(issuingState = ""))
    }

    // ── Belge tipi kapısı ────────────────────────────────────────────────────

    @Test
    fun turkishPassport_isUnsupportedDocType() {
        // Pasaport desteği bilinçli kapalı (JP2 DG2 + AA politikası test edilmedi).
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_DOC_TYPE, evaluate(documentCode = "P"))
    }

    @Test
    fun missingDocumentCode_isUnsupportedDocType() {
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_DOC_TYPE, evaluate(documentCode = null))
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_DOC_TYPE, evaluate(documentCode = ""))
    }

    @Test
    fun countryTakesPrecedenceOverDocType() {
        assertEquals(
            DocumentSupport.Verdict.UNSUPPORTED_COUNTRY,
            evaluate(issuingState = "DEU", documentCode = "P")
        )
    }

    // ── Görüntü ve AA kapıları (TC kimlik kartı bağlamında) ──────────────────

    @Test
    fun nullFaceImage_isNoFaceImage() {
        assertEquals(DocumentSupport.Verdict.NO_FACE_IMAGE, evaluate(faceImage = null))
    }

    @Test
    fun emptyFaceImage_isNoFaceImage() {
        assertEquals(DocumentSupport.Verdict.NO_FACE_IMAGE, evaluate(faceImage = ByteArray(0)))
    }

    @Test
    fun jpeg2000Image_isUnsupportedImage() {
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_IMAGE, evaluate(faceImage = jp2))
    }

    @Test
    fun rawJ2kImage_isUnsupportedImage() {
        assertEquals(DocumentSupport.Verdict.UNSUPPORTED_IMAGE, evaluate(faceImage = j2kRaw))
    }

    @Test
    fun jpegButNoDg15_isNoActiveAuth() {
        assertEquals(DocumentSupport.Verdict.NO_ACTIVE_AUTH, evaluate(dg15 = null))
    }

    @Test
    fun jpegButEmptySignature_isNoActiveAuth() {
        assertEquals(DocumentSupport.Verdict.NO_ACTIVE_AUTH, evaluate(activeSig = ByteArray(0)))
    }

    @Test
    fun imageProblemTakesPrecedenceOverAa() {
        // Hem JPEG2000 hem AA yok → görüntü sorunu önce raporlanır (kullanıcının ilk gördüğü).
        assertEquals(
            DocumentSupport.Verdict.UNSUPPORTED_IMAGE,
            evaluate(faceImage = jp2, dg15 = null, activeSig = null)
        )
    }

    @Test
    fun isJpeg_detectsSoiMarker() {
        assertTrue(DocumentSupport.isJpeg(jpeg))
        assertFalse(DocumentSupport.isJpeg(jp2))
        assertFalse(DocumentSupport.isJpeg(j2kRaw))
        assertFalse(DocumentSupport.isJpeg(ByteArray(2)))
    }

    // ── Çip imza YAPISI (ChipSignatureCheck) ────────────────────────────────────
    // İstemci kontrolü SUNUCUDAN DAHA KATI OLAMAZ: yalnız yapısal olarak imkânsız blokları eler.

    @Test
    fun `iso9796 ortuk trailer blogu okunabilir sayilir`() {
        val block = ByteArray(192).also { it[0] = 0x6A; it[191] = 0xBC.toByte() }
        assertTrue(ChipSignatureCheck.looksLikeSignatureBlock(block))
    }

    @Test
    fun `iso9796 acik trailer blogu okunabilir sayilir`() {
        val block = ByteArray(192).also { it[0] = 0x4A; it[190] = 0x34; it[191] = 0xCC.toByte() }
        assertTrue(ChipSignatureCheck.looksLikeSignatureBlock(block))
    }

    @Test
    fun `pkcs1 blogu okunabilir sayilir`() {
        val block = ByteArray(192).also { it[0] = 0x01; it[1] = 0xFF.toByte() }
        assertTrue(ChipSignatureCheck.looksLikeSignatureBlock(block))
    }

    /** 2026-08-24'te gerçekten gözlenen bozuk okuma: hdr=4A, trailer=C1AD. */
    @Test
    fun `bozuk okuma blogu elenir`() {
        val block = ByteArray(192).also { it[0] = 0x4A; it[190] = 0xC1.toByte(); it[191] = 0xAD.toByte() }
        assertFalse(ChipSignatureCheck.looksLikeSignatureBlock(block))
    }

    @Test
    fun `bos veya eksik girdide karar sunucuya birakilir`() {
        assertTrue(ChipSignatureCheck.isReadable(null, null))
        assertTrue(ChipSignatureCheck.isReadable(ByteArray(0), ByteArray(0)))
        // Ayrıştırılamayan DG15 → şüphede kullanıcıyı durdurma.
        assertTrue(ChipSignatureCheck.isReadable(byteArrayOf(1, 2, 3), ByteArray(192)))
    }

    @Test
    fun `imza yapisi bozuksa belge desteksiz degil okunamaz sayilir`() {
        val verdict = DocumentSupport.evaluate(
            "TUR", "I", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), chipSignatureReadable = false)
        assertEquals(DocumentSupport.Verdict.CHIP_SIGNATURE_UNREADABLE, verdict)
    }
}
