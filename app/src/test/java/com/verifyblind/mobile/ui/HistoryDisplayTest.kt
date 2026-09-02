package com.verifyblind.mobile.ui

import com.verifyblind.mobile.data.HistoryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cihaz adı satırı gösterim kararı testleri (pure JVM — Android API yok).
 * Kural: listede >= 2 FARKLI (boş olmayan) cihaz adı varsa göster.
 */
class HistoryDisplayTest {

    private fun item(deviceName: String): HistoryEntity =
        HistoryEntity(
            title = "t",
            description = "d",
            status = 1,
            timestamp = 0L,
            nonce = "n-$deviceName-${System.nanoTime()}",
            deviceName = deviceName
        )

    @Test
    fun emptyList_hidden() {
        assertFalse(HistoryDisplay.shouldShowDevice(emptyList()))
    }

    @Test
    fun singleDevice_hidden() {
        val items = listOf(item("Google Pixel 7"), item("Google Pixel 7"))
        assertFalse(HistoryDisplay.shouldShowDevice(items))
    }

    @Test
    fun twoDistinctDevices_shown() {
        val items = listOf(item("Google Pixel 7"), item("Samsung SM-S911B"))
        assertTrue(HistoryDisplay.shouldShowDevice(items))
    }

    @Test
    fun legacyEmptyNamesIgnored_singleRealDevice_hidden() {
        // Eski (boş adlı) kayıtlar + tek gerçek cihaz → gizli.
        val items = listOf(item(""), item("Google Pixel 7"), item(""))
        assertFalse(HistoryDisplay.shouldShowDevice(items))
    }

    @Test
    fun blankNameTreatedAsEmpty() {
        val items = listOf(item("   "), item("iPhone 14 Pro"))
        assertFalse(HistoryDisplay.shouldShowDevice(items))
    }
}
