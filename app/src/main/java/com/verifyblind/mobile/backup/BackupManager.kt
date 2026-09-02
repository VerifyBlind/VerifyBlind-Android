package com.verifyblind.mobile.backup

import com.verifyblind.mobile.data.HistoryRepository

/**
 * Manuel Yedekle/Geri Yükle orkestrasyonu — geçmiş DB'si ile `.vfbackup` kayıt listesi arasındaki
 * köprü. Saf eşleme/tekilleştirme `BackupMapper`'da (birim-testli); burası yalnız repository glue'su.
 */
object BackupManager {

    /** İçe-aktarma sonucu — onay/toast ekranında gösterilir. */
    data class ImportResult(val added: Int, val skipped: Int)

    /** Yedeklenecek kayıtlar: yerel geçmişin çözülmüş anlık görüntüsü → `BackupRecord` listesi. */
    suspend fun collectRecords(repo: HistoryRepository): List<BackupRecord> =
        repo.getAllHistorySnapshot().map { BackupMapper.toRecord(it) }

    /**
     * Kayıtları içe aktarır: yerelde OLMAYAN nonce'lar eklenir (yeniden şifrelenerek), var olanlar
     * atlanır. Additive + idempotent — aynı dosya iki kez yüklense de değişiklik olmaz.
     */
    suspend fun importRecords(repo: HistoryRepository, incoming: List<BackupRecord>): ImportResult {
        val localNonces = repo.getAllNonces()
        val toAdd = BackupMapper.selectNewRecords(incoming, localNonces)
        toAdd.forEach { repo.insertBackupRecord(it) }
        return ImportResult(added = toAdd.size, skipped = incoming.size - toAdd.size)
    }
}
