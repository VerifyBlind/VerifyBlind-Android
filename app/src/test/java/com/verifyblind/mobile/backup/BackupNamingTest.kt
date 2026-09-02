package com.verifyblind.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** `.vfbackup` otomatik dosya adı — `:` YASAK (Android SAF/paylaşım). */
class BackupNamingTest {

    @Test
    fun defaultFileName_isTimestampedWithoutColons() {
        val name = BackupNaming.defaultFileName(Instant.parse("2026-07-23T14:30:52Z"), ZoneOffset.UTC)
        assertEquals("VerifyBlind-20260723-143052.vfbackup", name)
        assertFalse("dosya adında ':' olmamalı", name.contains(":"))
        assertTrue(name.endsWith(BackupNaming.EXTENSION))
    }
}
