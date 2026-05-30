package com.verifyblind.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_table WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(historyItem: HistoryEntity)

    @Query("DELETE FROM history_table")
    fun deleteAll()
    
    @Query("DELETE FROM history_table WHERE id = :id")
    fun deleteById(id: Int)

    @Query("SELECT * FROM history_table WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllHistorySnapshot(): List<HistoryEntity>

    // ---- Sync Queries ----

    /** Find a history item by its unique nonce */
    @Query("SELECT * FROM history_table WHERE nonce = :nonce LIMIT 1")
    fun findByNonce(nonce: String): HistoryEntity?

    /** Get all nonces currently in local DB */
    @Query("SELECT nonce FROM history_table")
    fun getAllNonces(): List<String>

    /** Mark an item as sent to cloud */
    @Query("UPDATE history_table SET isSent = 1 WHERE nonce = :nonce")
    fun markAsSent(nonce: String)

    /** Get items that have been sent but may need deletion check */
    @Query("SELECT * FROM history_table WHERE isSent = 1 AND isDeleted = 0")
    fun getSentItems(): List<HistoryEntity>

    /** Mark as deleted (Tombstone) */
    @Query("UPDATE history_table SET isDeleted = 1, isSent = 0 WHERE id = :id")
    fun markAsDeleted(id: Int)

    @Query("UPDATE history_table SET isDeleted = 1, isSent = 0 WHERE nonce = :nonce")
    fun markAsDeletedByNonce(nonce: String)

    @Query("SELECT nonce FROM history_table WHERE isDeleted = 1")
    fun getDeletedNonces(): List<String>

    @Query("DELETE FROM history_table WHERE isDeleted = 1 AND isSent = 1")
    fun cleanupSyncedTombstones()

    /** Get items that haven't been sent to cloud yet */
    @Query("SELECT * FROM history_table WHERE isSent = 0")
    fun getUnsentItems(): List<HistoryEntity>

    /** Get all distinct Person IDs for sync decryption */
    @Query("SELECT DISTINCT personId FROM history_table WHERE personId != ''")
    fun getAllPersonIds(): List<String>

    /** Delete by nonce */
    @Query("DELETE FROM history_table WHERE nonce = :nonce")
    fun deleteByNonce(nonce: String)

    @Query("UPDATE history_table SET revokeTime = :time WHERE id = :id")
    fun updateRevokeTime(id: Int, time: Long)
}
