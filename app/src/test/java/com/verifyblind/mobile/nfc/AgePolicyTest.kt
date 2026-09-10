package com.verifyblind.mobile.nfc

import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

/**
 * [AgePolicy] birim testleri. Kritik davranışlar:
 *   - 15 yaşını doldurmamış → UNDER_MINIMUM_AGE
 *   - Doğum günü sınırı (tam 15 / bir gün eksik)
 *   - MRZ 2 haneli yıl yüzyıl çözümü (geleceğe düşen yıl 1900'e iner)
 *   - Ayrıştırılamayan MRZ → ALLOWED (fail-open; otorite enclave)
 */
class AgePolicyTest {

    /** Testlerin bugünü — sabit tutulur ki takvim ilerledikçe testler kırılmasın. */
    private val today = LocalDate.of(2026, 9, 11)

    private fun evaluate(dob: String?) = AgePolicy.evaluate(dob, today)

    @Test
    fun `on yasindaki cocuk reddedilir`() {
        // 2016-05-20 → 2026-09-11'de 10 yaşında
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("160520"))
    }

    @Test
    fun `on dort yasindaki cocuk reddedilir`() {
        // 2012-01-01 → 14 yaşında
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("120101"))
    }

    @Test
    fun `dogum gununden bir gun once hala reddedilir`() {
        // 2011-09-12 → 2026-09-11'de henüz 14 (doğum günü yarın)
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("110912"))
    }

    @Test
    fun `on besinci dogum gununde kabul edilir`() {
        // 2011-09-11 → bugün tam 15 oldu
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("110911"))
    }

    @Test
    fun `on alti yasindaki kullanici kabul edilir`() {
        // 2010-03-15 → 16 yaşında
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("100315"))
    }

    @Test
    fun `yetiskin kabul edilir`() {
        // 1990-01-01 → "90" yılı 2090 olarak okunamaz (gelecekte) → 1990
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("900101"))
    }

    @Test
    fun `yuzyil cozumu gelecege dusen yili 1900lere indirir`() {
        // "270101" → 2027-01-01 gelecekte olduğu için 1927-01-01 kabul edilir → yaşlı, ALLOWED
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("270101"))
    }

    @Test
    fun `bu yil dogan bebek reddedilir`() {
        // 2026-01-05 → 0 yaşında
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("260105"))
    }

    @Test
    fun `null mrz kararı enclave'e birakir`() {
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate(null))
    }

    @Test
    fun `bos mrz karari enclave'e birakir`() {
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate(""))
    }

    @Test
    fun `eksik haneli mrz karari enclave'e birakir`() {
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("1105"))
    }

    @Test
    fun `rakam olmayan mrz karari enclave'e birakir`() {
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("11AB11"))
    }

    @Test
    fun `gecersiz ay gun karari enclave'e birakir`() {
        // "991332" → 13. ay / 32. gün yok
        assertEquals(AgePolicy.Verdict.ALLOWED, evaluate("991332"))
    }

    @Test
    fun `mrz dolgu karakteri temizlenir`() {
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("120101<"))
    }

    @Test
    fun `29 subat artik yil dogru ayristirilir`() {
        // 2012-02-29 geçerli bir tarih → 14 yaşında
        assertEquals(AgePolicy.Verdict.UNDER_MINIMUM_AGE, evaluate("120229"))
    }
}
