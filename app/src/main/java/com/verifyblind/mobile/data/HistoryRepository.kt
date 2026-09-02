package com.verifyblind.mobile.data

import com.google.gson.Gson
import com.verifyblind.mobile.crypto.CryptoUtils
import com.verifyblind.mobile.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * İşlem geçmişi deposu. Sürekli bulut senkronizasyonu KALDIRILDI → manuel Yedekle/Geri
 * Yükle. Silme SERTtir (tombstone yok). Yedek kayıtları
 * `insertBackupRecord` ile geri yüklenir (title/description/deviceName yerel anahtarla yeniden
 * şifrelenir).
 */
class HistoryRepository(private val historyDao: HistoryDao) {

    private val gson = Gson()
    private val historyPubKey: String by lazy { CryptoUtils.ensureHistoryKeyExists() }

    // Internal data class for storing encrypted logic
    private data class SecureContent(val key: String, val blob: String)

    val allHistory: Flow<List<HistoryEntity>> = historyDao.getAllHistory().map { list ->
        list.map { decryptItem(it) }
    }

    /** Raw (encrypted) flow — for progressive decryption in UI */
    val allHistoryRaw: Flow<List<HistoryEntity>> = historyDao.getAllHistory()

    /** Decrypt a single item on the calling dispatcher (call from IO) */
    suspend fun decryptItemPublic(item: HistoryEntity): HistoryEntity =
        withContext(Dispatchers.IO) { decryptItem(item) }

    suspend fun getAllHistorySnapshot(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        historyDao.getAllHistorySnapshot().map { decryptItem(it) }
    }

    suspend fun insert(
        title: String,
        description: String,
        status: Int,
        actionType: Int = 0,
        timestamp: Long = System.currentTimeMillis(),
        nonce: String = java.util.UUID.randomUUID().toString(),
        personId: String = "",
        cardId: String = "",
        partnerId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val encDevice = encryptString(com.verifyblind.mobile.util.DeviceInfo.marketingName())
                historyDao.insert(
                    HistoryEntity(
                        title = encryptString(title),
                        description = encryptString(description),
                        actionType = actionType,
                        status = status,
                        timestamp = timestamp,
                        nonce = nonce,
                        personId = personId,
                        cardId = cardId,
                        partnerId = partnerId,
                        deviceName = encDevice
                    )
                )
            } catch (e: Exception) {
                AppLog.error("Geçmiş kaydı ekleme başarısız: ${e.message}", "VerifyBlind_History", e)
            }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        historyDao.deleteAll()
    }

    /** Sert silme (tombstone değil). */
    suspend fun deleteById(id: Int) = withContext(Dispatchers.IO) {
        historyDao.deleteById(id)
    }

    /** Bir karta ait tüm geçmişi SERT siler ("işlem geçmişimi de sil"). */
    suspend fun deleteByCardId(cardId: String) = withContext(Dispatchers.IO) {
        historyDao.deleteByCardId(cardId)
    }

    suspend fun updateRevokeTime(id: Int, time: Long) = withContext(Dispatchers.IO) {
        historyDao.updateRevokeTime(id, time)
    }

    /** Yerelde var olan tüm nonce'lar — içe-aktarma tekilleştirmesi için. */
    suspend fun getAllNonces(): Set<String> = withContext(Dispatchers.IO) {
        historyDao.getAllNonces().toSet()
    }

    /**
     * `.vfbackup` geri-yüklemesinden gelen bir kaydı ekler: düz metin başlık/açıklama/deviceName
     * yerel History anahtarıyla YENİDEN şifrelenir, id=0 ile yeni satır olarak yazılır. Çağıran
     * (BackupManager) nonce tekilleştirmesini önceden yapmış olmalı.
     */
    suspend fun insertBackupRecord(record: com.verifyblind.mobile.backup.BackupRecord) = withContext(Dispatchers.IO) {
        val entity = com.verifyblind.mobile.backup.BackupMapper.toEntity(record)
        val encDevice = if (entity.deviceName.isEmpty()) "" else encryptString(entity.deviceName)
        historyDao.insert(
            entity.copy(
                title = encryptString(entity.title),
                description = encryptString(entity.description),
                deviceName = encDevice
            )
        )
    }

    // ---- Encryption ----

    private fun encryptString(plain: String): String {
        val (blob, aesKey, _) = CryptoUtils.aesEncrypt(plain)
        val encAesKey = CryptoUtils.rsaEncryptForKeystore(aesKey, historyPubKey)
        return gson.toJson(SecureContent(encAesKey, blob))
    }

    private fun decryptItem(item: HistoryEntity): HistoryEntity {
        return try {
            item.copy(
                title = decryptString(item.title),
                description = decryptString(item.description),
                deviceName = if (item.deviceName.isEmpty()) "" else decryptString(item.deviceName)
            )
        } catch (e: Exception) {
            item.copy(title = "Decryption Failed", description = "Error")
        }
    }

    private fun decryptString(json: String): String {
        return try {
            val secureObj = gson.fromJson(json, SecureContent::class.java)
            val aesKey = CryptoUtils.rsaDecryptHistory(secureObj.key)
            CryptoUtils.aesDecrypt(secureObj.blob, aesKey)
        } catch (e: Exception) {
            "Encrypted"
        }
    }
}
