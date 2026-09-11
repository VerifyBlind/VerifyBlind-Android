package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TD1 (TC kimlik) MRZ ayrıştırma sınır koşulları.
 *
 * Regresyon zemini (parite denetimi 2026-09-03, O-9): satır 1 kontrolü `length >= 14` iken check
 * digit `cleanerLine[14]` okunuyordu — tam 14 karakterlik bir satırda
 * `StringIndexOutOfBoundsException`. İstisna ML Kit'in başarı dinleyicisinin İÇİNDE doğduğu için
 * uygulamayı çökertiyordu. Satır filtresi HAM metne uygulandığından (`length > 20`, boşluklar
 * dahil) OCR'ın boşluklu okuduğu bir satır temizlendikten sonra rahatlıkla 14'e inebiliyor.
 *
 * Beklenen değerler elle hesaplandı (ICAO ağırlık 7-3-1): "A12345678"→4, "920101"→7, "301231"→6.
 */
class MrzAnalyzerTest {

    /** TD1 satır 2: DOB 920101 (check 7), erkek, son geçerlilik 301231 (check 6). 30 karakter. */
    private val dobLine = "9201017M3012316TUR<<<<<<<<<<<<"

    private class Capture {
        var docNo: String? = null
        var dob: String? = null
        var expiry: String? = null
        var type: String? = null
        var count = 0
    }

    private fun analyzerWith(capture: Capture) = MrzAnalyzer { d, b, e, t ->
        capture.docNo = d; capture.dob = b; capture.expiry = e; capture.type = t; capture.count++
    }

    /**
     * ASIL REGRESYON: temizlendikten sonra tam 14 karakter kalan TD1 satırı çökertmemeli.
     * Ham satır 26 karakter (boşluklarla) → `length > 20` filtresinden geçiyor, temizlenince 14.
     */
    @Test
    fun td1Line_exactly14CharsAfterCleanup_doesNotCrash() {
        val capture = Capture()
        val analyzer = analyzerWith(capture)
        val spaced = "I< T U R A 1 2 3 4 5 6 7 8"   // temiz hâli: "I<TURA12345678" (14)

        assertEquals(14, spaced.replace(" ", "").length)
        repeat(3) { analyzer.processText(spaced) }   // çökmemeli

        assertEquals("eksik satırdan sonuç ÜRETİLMEMELİ", 0, capture.count)
        assertNull(capture.docNo)
    }

    /** Sınırın hemen üstü: temizlenince 15 karakter → check digit okunabilir, belge no ayrışır. */
    @Test
    fun td1Line_exactly15CharsAfterCleanup_parses() {
        val capture = Capture()
        val analyzer = analyzerWith(capture)
        val spaced = "I< T U R A 1 2 3 4 5 6 7 8 4"  // temiz hâli: "I<TURA123456784" (15)

        assertEquals(15, spaced.replace(" ", "").length)
        repeat(3) { analyzer.processText(spaced + "\n" + dobLine) }

        assertEquals(1, capture.count)
        assertEquals("A12345678", capture.docNo)
        assertEquals("920101", capture.dob)
        assertEquals("301231", capture.expiry)
        assertEquals("ID", capture.type)
    }

    /** Normal TD1 (30 karakter, '<' dolgulu) — fix'in olağan yolu bozmadığının kanıtı. */
    @Test
    fun standardTd1_parsesAfterThreeStableReads() {
        val capture = Capture()
        val analyzer = analyzerWith(capture)
        val docLine = "I<TURA123456784<<<<<<<<<<<<<<<"   // 30 karakter

        assertEquals(30, docLine.length)
        analyzer.processText(docLine + "\n" + dobLine)
        assertEquals("iki okumada sonuç VERİLMEMELİ (stabilite 3)", 0, capture.count)
        analyzer.processText(docLine + "\n" + dobLine)
        assertEquals(0, capture.count)
        analyzer.processText(docLine + "\n" + dobLine)

        assertEquals(1, capture.count)
        assertEquals("A12345678", capture.docNo)
        assertEquals("ID", capture.type)
    }
}
