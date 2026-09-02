package com.verifyblind.mobile.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `.vfbackup` otomatik dosya adı üretimi. Biçim: `VerifyBlind-yyyyMMdd-HHmmss.vfbackup`.
 * İki nokta (`:`) KULLANILMAZ — Android SAF ve paylaşım hedeflerinde geçersizdir.
 */
object BackupNaming {

    const val EXTENSION = ".vfbackup"
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun defaultFileName(
        instant: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val stamp = FORMATTER.format(instant.atZone(zone))
        return "VerifyBlind-$stamp$EXTENSION"
    }
}
