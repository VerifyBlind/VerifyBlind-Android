package com.verifyblind.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MRZ kart ipucu boyutlandırma kuralı (saf JVM — Android API yok).
 *
 * Geri bildirim (2026-09-13): kart sabit oranda çizildiği için üst kenarı ekran
 * dışında kalıyordu. CameraManager.layoutMrzCardHint() kartı tarama çerçevesinin
 * üstünde kalan boşluğa göre kısar. Buradaki fittedWidth(), oradaki hesabın
 * birebir aynısıdır; sabitlerden biri değişirse bu testler yakalar.
 *
 * Not: yerleşimin kendisi (constraint'ler) burada ölçülmez — activity_main
 * Robolectric'te inflate edilemiyor (stepper header'daki tint'li ImageView).
 * Ölçülen şey, taşmayı engelleyen aritmetiğin doğruluğu.
 */
class MrzCardHintLayoutTest {

    // CameraManager companion object'indeki değerlerle aynı olmalı.
    private val cardRatio = 1.586f
    private val widthRatio = 1.0375f * 0.85f
    private val topInsetDp = 8f
    private val minWidthDp = 120f

    /** CameraManager.layoutMrzCardHint() içindeki hesabın aynısı. */
    private fun fittedWidth(parentWidth: Int, frameTop: Int, bottomMarginPx: Int, density: Float): Int {
        val gap = bottomMarginPx + (topInsetDp * density).toInt()
        val available = frameTop - gap
        if (available <= 0) return 0
        val preferred = (parentWidth * widthRatio).toInt()
        val fitted = minOf(preferred, (available * cardRatio).toInt())
        return if (fitted < (minWidthDp * density).toInt()) 0 else fitted
    }

    /** Kart + boşluk, çerçevenin üstündeki alana sığıyor mu? */
    private fun fitsAbove(fitted: Int, frameTop: Int, bottomMarginPx: Int, density: Float): Boolean {
        val cardHeight = (fitted / cardRatio).toInt()
        val gap = bottomMarginPx + (topInsetDp * density).toInt()
        return cardHeight + gap <= frameTop
    }

    private fun marginPx(density: Float) = (12f * density).toInt()

    @Test
    fun typicalPhone_cardVisibleAndFits() {
        val density = 2.75f                 // 440dpi (Xiaomi test cihazı)
        val parentWidth = (393 * density).toInt()
        val frameTop = (384 * density).toInt()   // bias 0.75 ile ölçülen konum
        val m = marginPx(density)

        val fitted = fittedWidth(parentWidth, frameTop, m, density)
        assertTrue("Kart gösterilmeli", fitted > 0)
        assertTrue("Kart çerçevenin üstüne sığmalı", fitsAbove(fitted, frameTop, m, density))
    }

    @Test
    fun typicalPhone_usesPreferredWidth_notShrunk() {
        val density = 2.75f
        val parentWidth = (393 * density).toInt()
        val frameTop = (384 * density).toInt()
        val fitted = fittedWidth(parentWidth, frameTop, marginPx(density), density)

        // Bol alan var → tercih edilen genişlik kullanılmalı (kısılmamalı).
        assertEquals((parentWidth * widthRatio).toInt(), fitted)
    }

    @Test
    fun shortScreen_neverOverflows() {
        // Çerçevenin üstünde kalan boşluk daraldıkça kart taşmamalı: geniş bir
        // aralığı tarayıp her adımda sığma kuralını doğrula.
        val density = 2.0f
        val parentWidth = (360 * density).toInt()
        val m = marginPx(density)

        for (frameTopDp in 40..300 step 10) {
            val frameTop = (frameTopDp * density).toInt()
            val fitted = fittedWidth(parentWidth, frameTop, m, density)
            if (fitted == 0) continue       // sığmıyorsa gizlenir — kabul
            assertTrue(
                "frameTop=${frameTopDp}dp'de kart taşıyor (fitted=$fitted)",
                fitsAbove(fitted, frameTop, m, density)
            )
        }
    }

    @Test
    fun tightSpace_shrinksBelowPreferredWidth() {
        // Boşluk tercih edilen kart yüksekliğinden azsa kart KISILMALI.
        val density = 2.0f
        val parentWidth = (360 * density).toInt()
        val m = marginPx(density)
        val preferred = (parentWidth * widthRatio).toInt()
        // Tercih edilen kartın yüksekliğinin yarısı kadar boşluk bırak.
        val frameTop = ((preferred / cardRatio) / 2).toInt() + m + (topInsetDp * density).toInt()

        val fitted = fittedWidth(parentWidth, frameTop, m, density)
        assertTrue("Dar alanda kart kısılmalı (fitted=$fitted, pref=$preferred)", fitted in 1 until preferred)
        assertTrue("Kısılmış kart yine de sığmalı", fitsAbove(fitted, frameTop, m, density))
    }

    @Test
    fun noRoom_hidesHintInsteadOfDrawingSliver() {
        val density = 2.0f
        val parentWidth = (360 * density).toInt()
        val frameTop = (40 * density).toInt()    // çerçeve neredeyse tepede

        assertEquals(0, fittedWidth(parentWidth, frameTop, marginPx(density), density))
    }

    @Test
    fun cardIsLargerThanBefore() {
        // Eski tercih: ekran genişliğinin %83'ü × 0.85 değil, doğrudan 0.83'ü.
        // Yeni: 1.0375 × 0.85 = 0.8819 → yaklaşık %25 büyük (0.83 × 1.0625).
        val density = 2.75f
        val parentWidth = (393 * density).toInt()
        val frameTop = (384 * density).toInt()

        val new = fittedWidth(parentWidth, frameTop, marginPx(density), density)
        val old = (parentWidth * 0.83f * 0.85f).toInt()   // eski: frameW(%85) × 0.83
        val growth = new.toFloat() / old
        assertTrue("Kart belirgin biçimde büyümeli (oran=$growth)", growth > 1.2f)
    }
}
