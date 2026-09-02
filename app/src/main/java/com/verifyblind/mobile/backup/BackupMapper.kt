package com.verifyblind.mobile.backup

import com.verifyblind.mobile.data.HistoryEntity

/**
 * `HistoryEntity` (ÇÖZÜLMÜŞ) ↔ `BackupRecord` eşlemesi ve içe-aktarma tekilleştirmesi.
 *
 * Bu katman kimlik/kripto TAŞIMAZ: `toRecord` çözülmüş entity ile çalışır, `toEntity` düz metin
 * üretir (repository insert sırasında title/description/deviceName'i yerel anahtarla yeniden
 * şifreler). Tüm fonksiyonlar saftır → `BackupMapperTest` ile birim-test edilir.
 */
object BackupMapper {

    fun toRecord(entity: HistoryEntity): BackupRecord = BackupRecord(
        nonce = entity.nonce,
        personId = entity.personId,
        cardId = entity.cardId,
        partnerId = entity.partnerId,
        title = entity.title,
        description = entity.description,
        actionType = entity.actionType,
        status = entity.status,
        timestamp = entity.timestamp,
        transactionId = entity.transactionId,
        deviceName = entity.deviceName
    )

    /** Düz metin entity üretir (id=0 → autogenerate). Şifreleme repository'de yapılır. */
    fun toEntity(record: BackupRecord): HistoryEntity = HistoryEntity(
        title = record.title,
        description = record.description,
        actionType = record.actionType,
        status = record.status,
        timestamp = record.timestamp,
        transactionId = record.transactionId,
        nonce = record.nonce,
        personId = record.personId,
        cardId = record.cardId,
        partnerId = record.partnerId,
        deviceName = record.deviceName
    )

    /**
     * İçe-aktarma additive + idempotent: yerelde zaten var olan nonce'lar ATLANIR, yalnız yeni
     * olanlar döner. (Spec: "aynı nonce var ise güncelleME".)
     */
    fun selectNewRecords(incoming: List<BackupRecord>, localNonces: Set<String>): List<BackupRecord> =
        incoming.filter { it.nonce !in localNonces }
}
