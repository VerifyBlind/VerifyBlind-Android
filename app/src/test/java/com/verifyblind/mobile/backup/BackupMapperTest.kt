package com.verifyblind.mobile.backup

import com.verifyblind.mobile.data.HistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `HistoryEntity` (çözülmüş) ↔ `BackupRecord` eşlemesi ve nonce-ile-tekilleştirme — saf JVM.
 *
 * Not: eşleme ÇÖZÜLMÜŞ entity ile çalışır (title/description/deviceName düz metin). Şifreleme
 * repository katmanının işidir — bu katman kimlik/kripto taşımaz.
 */
class BackupMapperTest {

    private fun decryptedEntity() = HistoryEntity(
        id = 42,
        title = "Acme'ye kimlik kanıtlandı",
        description = "Giriş doğrulaması",
        actionType = 3,
        status = 1,
        timestamp = 1_690_000_000_000,
        transactionId = "tx-1",
        nonce = "nonce-1",
        personId = "person-A",
        cardId = "card-1",
        partnerId = "partner-x",
        deviceName = "Pixel 7",
        isSent = true,
        isDeleted = false
    )

    @Test
    fun toRecord_mapsAllDomainFields() {
        val r = BackupMapper.toRecord(decryptedEntity())
        assertEquals("nonce-1", r.nonce)
        assertEquals("person-A", r.personId)
        assertEquals("card-1", r.cardId)
        assertEquals("partner-x", r.partnerId)
        assertEquals("Acme'ye kimlik kanıtlandı", r.title)
        assertEquals("Giriş doğrulaması", r.description)
        assertEquals(3, r.actionType)
        assertEquals(1, r.status)
        assertEquals(1_690_000_000_000, r.timestamp)
        assertEquals("tx-1", r.transactionId)
        assertEquals("Pixel 7", r.deviceName)
    }

    @Test
    fun toEntity_mapsFieldsWithZeroIdAndPlaintextPreserved() {
        val record = BackupMapper.toRecord(decryptedEntity())
        val e = BackupMapper.toEntity(record)
        assertEquals(0, e.id) // yeni satır → autogenerate
        assertEquals("nonce-1", e.nonce)
        assertEquals("person-A", e.personId)
        assertEquals("card-1", e.cardId)
        assertEquals("partner-x", e.partnerId)
        assertEquals("Acme'ye kimlik kanıtlandı", e.title)
        assertEquals("Giriş doğrulaması", e.description)
        assertEquals("Pixel 7", e.deviceName)
        assertEquals(3, e.actionType)
        assertEquals(1, e.status)
        assertEquals(1_690_000_000_000, e.timestamp)
        assertEquals("tx-1", e.transactionId)
    }

    // ── Nonce-ile-tekilleştirme (additive, idempotent) ──

    @Test
    fun selectNewRecords_keepsOnlyNoncesNotPresentLocally() {
        val incoming = listOf(
            BackupMapper.toRecord(decryptedEntity()),                       // nonce-1
            BackupMapper.toRecord(decryptedEntity().copy(nonce = "nonce-2"))
        )
        val local = setOf("nonce-1")
        val selected = BackupMapper.selectNewRecords(incoming, local)
        assertEquals(1, selected.size)
        assertEquals("nonce-2", selected[0].nonce)
    }

    @Test
    fun selectNewRecords_emptyLocal_takesAll() {
        val incoming = listOf(
            BackupMapper.toRecord(decryptedEntity()),
            BackupMapper.toRecord(decryptedEntity().copy(nonce = "nonce-2"))
        )
        assertEquals(2, BackupMapper.selectNewRecords(incoming, emptySet()).size)
    }

    @Test
    fun selectNewRecords_allPresent_takesNone() {
        val incoming = listOf(BackupMapper.toRecord(decryptedEntity()))
        assertEquals(0, BackupMapper.selectNewRecords(incoming, setOf("nonce-1")).size)
    }
}
