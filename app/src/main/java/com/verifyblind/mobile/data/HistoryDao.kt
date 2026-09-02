package com.verifyblind.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * İşlem geçmişi DAO'su. Sürekli bulut senkronizasyonu KALDIRILDI (manuel Yedekle/Geri Yükle
 * modeline geçildi) → tombstone yok, sert DELETE. `isDeleted`/`isSent` sütunları şemada FİZİKSEL
 * kalır (yıkıcı Room migration'dan kaçınmak için) ama artık yazılmaz; sorgular `isDeleted = 0`
 * filtresini korur (sütun daima 0 olduğundan davranışı değişmez).
 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_table WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(historyItem: HistoryEntity)

    @Query("DELETE FROM history_table")
    fun deleteAll()

    /** Sert silme (tombstone değil) — manuel modelde silme yerelde kalıcıdır. */
    @Query("DELETE FROM history_table WHERE id = :id")
    fun deleteById(id: Int)

    @Query("SELECT * FROM history_table WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllHistorySnapshot(): List<HistoryEntity>

    /** Yerelde var olan tüm nonce'lar — içe-aktarma tekilleştirmesi (BackupManager) için. */
    @Query("SELECT nonce FROM history_table")
    fun getAllNonces(): List<String>

    /** Bir karta ait tüm geçmişi SERT siler (kart silinirken "işlem geçmişimi de sil" seçilince). */
    @Query("DELETE FROM history_table WHERE cardId = :cardId")
    fun deleteByCardId(cardId: String)

    @Query("UPDATE history_table SET revokeTime = :time WHERE id = :id")
    fun updateRevokeTime(id: Int, time: Long)
}
