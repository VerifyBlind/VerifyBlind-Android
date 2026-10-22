package com.verifyblind.mobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo kart ekleme "Başla"daki kilit kapısı (VERIFYBLIND-ANDROID-1M).
 *
 * Demo da kaydın sonunda bileti cihaz kilidiyle çözer; kilit hiç yoksa akış canlılık testinden
 * SONRA başarısız oluyordu. Kapı yalnız kesin durumda durdurmalı: geçici bir donanım durumu
 * demoyu ve pilot koşularını engellememeli.
 */
class DemoLockGateTest {

    @Test
    fun demoStart_isBlockedWhenDeviceHasNoLock() {
        assertTrue(BiometricHelper.blocksDemoStart(BiometricHelper.Availability.NO_DEVICE_LOCK))
    }

    @Test
    fun demoStart_proceedsWhenLockExists() {
        assertFalse(BiometricHelper.blocksDemoStart(BiometricHelper.Availability.AVAILABLE))
    }

    @Test
    fun demoStart_isNotBlockedByTransientUnavailability() {
        assertFalse(BiometricHelper.blocksDemoStart(BiometricHelper.Availability.UNAVAILABLE))
    }
}
