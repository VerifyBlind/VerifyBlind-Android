package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Olay karesinin kırpma dikdörtgeni. Hata burada sessizdir: yanlış yere kırpılan karede
 * enclave yüz bulamaz, kimlik 0 sayılır ve meşru kullanıcı ERR_CHOREO_IDENTITY alır.
 */
class FaceCropTest {

    @Test
    fun squareAround_isCenteredAndSized() {
        // 1080×1920 dik kare, 400 px'lik yüz ortada.
        val b = FaceCrop.squareAround(340, 760, 740, 1160, 1080, 1920)
        assertEquals(880, b.width)          // 400 × 2,2
        assertEquals(b.width, b.height)
        assertEquals(540, (b.left + b.right) / 2)
        assertEquals(960, (b.top + b.bottom) / 2)
    }

    /** Yüz kenardayken kırpma KAYDIRILIR, kesilmez: kare hep kadrajın içinde ve tam boyda. */
    @Test
    fun squareAround_shiftsInsideAtEdges() {
        val b = FaceCrop.squareAround(0, 0, 300, 300, 1080, 1920)
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(660, b.width)
        val r = FaceCrop.squareAround(900, 1700, 1080, 1920, 1080, 1920)
        assertEquals(1080, r.right)
        assertEquals(1920, r.bottom)
    }

    /** Yüz kadrajdan büyükse kare kadrajın kısa kenarına küçülür. */
    @Test
    fun squareAround_neverExceedsFrame() {
        val b = FaceCrop.squareAround(0, 200, 1080, 1500, 1080, 1920)
        assertEquals(1080, b.width)
        assertTrue(b.left >= 0 && b.right <= 1080 && b.top >= 0 && b.bottom <= 1920)
    }

    /**
     * Dik uzaydaki dikdörtgenin sensör uzayına çevrilip aynı açıyla döndürülünce kendine dönmesi.
     * Döndürme, sensör görüntüsünü saat yönünde çevirir (CameraX rotationDegrees).
     */
    @Test
    fun toSensor_roundTripsThroughRotation() {
        val sensorW = 1920
        val sensorH = 1080
        for (rotation in listOf(0, 90, 180, 270)) {
            val uprightW = if (rotation % 180 == 0) sensorW else sensorH
            val uprightH = if (rotation % 180 == 0) sensorH else sensorW
            val upright = FaceCrop.Box(100, 300, 500, 700)
            val s = FaceCrop.toSensor(upright, rotation, sensorW, sensorH)
            assertTrue("$rotation: ${s}", s.left >= 0 && s.top >= 0 && s.right <= sensorW && s.bottom <= sensorH)
            assertEquals(upright.width * upright.height, s.width * s.height)
            assertEquals(upright, rotateClockwise(s, rotation, sensorW, sensorH))
            // Dik boyutlar tutarlı: dönmüş kare dik görüntünün içinde.
            assertTrue(upright.right <= uprightW && upright.bottom <= uprightH)
        }
    }

    /** Sensör uzayındaki bir dikdörtgeni saat yönünde döndürür — bağımsız başvuru. */
    private fun rotateClockwise(b: FaceCrop.Box, rotation: Int, w: Int, h: Int): FaceCrop.Box {
        // Köşeleri tek tek döndür: (x, y) → 90: (h − y, x) · 180: (w − x, h − y) · 270: (y, w − x)
        fun map(x: Int, y: Int): Pair<Int, Int> = when (rotation) {
            0 -> x to y
            90 -> (h - y) to x
            180 -> (w - x) to (h - y)
            270 -> y to (w - x)
            else -> error("rotation")
        }
        val a = map(b.left, b.top)
        val c = map(b.right, b.bottom)
        return FaceCrop.Box(minOf(a.first, c.first), minOf(a.second, c.second),
            maxOf(a.first, c.first), maxOf(a.second, c.second))
    }
}
