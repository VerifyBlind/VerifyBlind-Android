package com.verifyblind.mobile.nfc

import org.junit.Assert.*
import org.junit.Test

/**
 * PassportReader yardımcı fonksiyonlarının birim testleri.
 *
 * NOT: readPassport() NFC donanımı gerektirdiği için bu dosyada test edilmiyor.
 * DG1/DG2/SOD parsing testleri için sentetik ICAO TLV binary verisi gerekir;
 * src/VerifyBlind.Bomber/test-data/ dizinindeki .bin dosyaları gerçek kimlik kartı
 * dump'ı içerdiğinden (TCKN, MRZ, yüz fotoğrafı) bu testlerde kullanılmamıştır.
 *
 * Kapsam:
 *   - cleanDocNo: boşluk temizleme, büyük harf dönüşümü
 *   - correctDateInput: format dönüşümü (DDMMYYYY → YYMMDD), OCR hata düzeltme,
 *     ayraç temizleme, uzun girdi kısaltma
 */
class PassportReaderTest {

    // ─────────────────────────── cleanDocNo ───────────────────────────────────────

    @Test
    fun cleanDocNo_removesSpaces() {
        assertEquals("AB1234567", PassportReader.cleanDocNo("AB 123 4567"))
    }

    @Test
    fun cleanDocNo_uppercasesLowercase() {
        assertEquals("A1B2C3D4", PassportReader.cleanDocNo("a1b2c3d4"))
    }

    @Test
    fun cleanDocNo_alreadyClean_unchanged() {
        assertEquals("TR01234567", PassportReader.cleanDocNo("TR01234567"))
    }

    @Test
    fun cleanDocNo_leadingAndTrailingSpaces() {
        assertEquals("AB12345", PassportReader.cleanDocNo("  ab12345  "))
    }

    @Test
    fun cleanDocNo_emptyString_returnsEmpty() {
        assertEquals("", PassportReader.cleanDocNo(""))
    }

    @Test
    fun cleanDocNo_onlySpaces_returnsEmpty() {
        assertEquals("", PassportReader.cleanDocNo("   "))
    }

    @Test
    fun cleanDocNo_mixedCaseWithSpaces() {
        assertEquals("TRTEST123", PassportReader.cleanDocNo("tr test 123"))
    }

    // ─────────────────────────── correctDateInput ────────────────────────────────

    // DDMMYYYY (8 hane) → YYMMDD dönüşümü
    @Test
    fun correctDateInput_ddmmyyyy_convertsToYymmdd() {
        // 22/10/1985 → yearShort=85, month=10, day=22 → "851022"
        assertEquals("851022", PassportReader.correctDateInput("22101985"))
    }

    @Test
    fun correctDateInput_ddmmyyyy_year2000Plus() {
        // 15/03/2001 → yearShort=01, month=03, day=15 → "010315"
        assertEquals("010315", PassportReader.correctDateInput("15032001"))
    }

    @Test
    fun correctDateInput_ddmmyyyy_year1990() {
        // 01/01/1990 → "900101"
        assertEquals("900101", PassportReader.correctDateInput("01011990"))
    }

    // Ayraçlar temizlenmeli
    @Test
    fun correctDateInput_slashSeparators_removed() {
        assertEquals("851022", PassportReader.correctDateInput("22/10/1985"))
    }

    @Test
    fun correctDateInput_dotSeparators_removed() {
        assertEquals("851022", PassportReader.correctDateInput("22.10.1985"))
    }

    @Test
    fun correctDateInput_dashSeparators_removed() {
        assertEquals("851022", PassportReader.correctDateInput("22-10-1985"))
    }

    @Test
    fun correctDateInput_spaceSeparators_removed() {
        assertEquals("851022", PassportReader.correctDateInput("22 10 1985"))
    }

    // 6 haneli girdi (zaten YYMMDD formatında) değiştirilmemeli
    @Test
    fun correctDateInput_sixDigits_returnedAsIs() {
        assertEquals("851022", PassportReader.correctDateInput("851022"))
    }

    @Test
    fun correctDateInput_sixDigits_expiryDate() {
        assertEquals("301231", PassportReader.correctDateInput("301231"))
    }

    // 7+ haneli girdi → ilk 6 hane alınır
    @Test
    fun correctDateInput_sevenDigits_truncatedToSix() {
        assertEquals("851022", PassportReader.correctDateInput("8510221"))
    }

    @Test
    fun correctDateInput_nineDigits_truncatedToSix() {
        assertEquals("851022", PassportReader.correctDateInput("851022123"))
    }

    // OCR hata düzeltmeleri
    @Test
    fun correctDateInput_ocr_O_treatedAsZero() {
        // "O" → "0": "221O1985" → "22101985" → "851022"
        assertEquals("851022", PassportReader.correctDateInput("221O1985"))
    }

    @Test
    fun correctDateInput_ocr_lowercase_o_treatedAsZero() {
        assertEquals("851022", PassportReader.correctDateInput("221o1985"))
    }

    @Test
    fun correctDateInput_ocr_I_treatedAsOne() {
        // "I" → "1": "I5032001" → "15032001" → "010315"
        assertEquals("010315", PassportReader.correctDateInput("I5032001"))
    }

    @Test
    fun correctDateInput_ocr_l_treatedAsOne() {
        assertEquals("010315", PassportReader.correctDateInput("l5032001"))
    }

    @Test
    fun correctDateInput_ocr_S_treatedAsFive() {
        // "S" → "5": "22101S85" → "22101585"
        // 8 hane → DDMMYYYY: day=22, month=10, yearFull=1585, yearShort=85 → "851022"
        assertEquals("851022", PassportReader.correctDateInput("22101S85"))
    }

    @Test
    fun correctDateInput_ocr_B_treatedAsEight() {
        // "B" → "8": "2210198B" → "22101988" → "881022"
        assertEquals("881022", PassportReader.correctDateInput("2210198B"))
    }

    @Test
    fun correctDateInput_ocr_Z_treatedAsTwo() {
        // "Z" → "2": "ZZ101985" → "22101985" → "851022"
        assertEquals("851022", PassportReader.correctDateInput("ZZ101985"))
    }

    @Test
    fun correctDateInput_ocr_G_treatedAsSix() {
        // "G" → "6": "221G1985" → "22161985" (not a real date but tests the mapping)
        // 8 hane → day=22, month=16 (geçersiz ama parse edilir), yearFull=1985, short=85 → "851622"
        assertEquals("851622", PassportReader.correctDateInput("221G1985"))
    }

    @Test
    fun correctDateInput_ocr_multipleSubstitutions() {
        // Birden fazla OCR hatası aynı anda: "OIO12OOO" → "01012000"
        // 8 hane → day=01, month=01, yearFull=2000, yearShort=00 → "000101"
        assertEquals("000101", PassportReader.correctDateInput("OIO12OOO"))
    }

    // Sayısal olmayan karakterler (OCR maplemesinden kaçanlar) temizlenmeli
    @Test
    fun correctDateInput_colonSeparators_stripped() {
        assertEquals("851022", PassportReader.correctDateInput("85:10:22"))
    }

    @Test
    fun correctDateInput_mixedGarbage_stripped() {
        assertEquals("851022", PassportReader.correctDateInput("85!10@22"))
    }

    // Sınır durumları
    @Test
    fun correctDateInput_emptyString_returnsEmpty() {
        assertEquals("", PassportReader.correctDateInput(""))
    }

    @Test
    fun correctDateInput_allNonNumeric_returnsEmpty() {
        // "Z" maps to "2" in OCR table — use chars not in OCR map (X, Y, W are not mapped)
        assertEquals("", PassportReader.correctDateInput("XYW"))
    }

    @Test
    fun correctDateInput_lessThanSixDigits_returnedAsIs() {
        // < 6 hane: ne 8 ne de >6 → olduğu gibi döner (temizlenmiş)
        assertEquals("1234", PassportReader.correctDateInput("1234"))
    }
}
