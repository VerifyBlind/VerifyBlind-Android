package com.verifyblind.mobile.data

import com.google.gson.Gson
import com.verifyblind.mobile.crypto.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
        val list = historyDao.getAllHistorySnapshot()
        list.map { decryptItem(it) }
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
        android.util.Log.d("VerifyBlind_History", "Kayıt ekleniyor: $title, Tür: $actionType, Nonce: $nonce")
        withContext(Dispatchers.IO) {
            try {
                val encTitle = encryptString(title)
                val encDesc = encryptString(description)
                
                val item = HistoryEntity(
                    title = encTitle,
                    description = encDesc,
                    actionType = actionType,
                    status = status,
                    timestamp = timestamp,
                    nonce = nonce,
                    personId = personId,
                    cardId = cardId,
                    partnerId = partnerId,
                    isSent = false
                )
                historyDao.insert(item)
                android.util.Log.d("VerifyBlind_History", "Ekleme BAŞARILI: $nonce")
            } catch (e: Exception) {
                android.util.Log.e("VerifyBlind_History", "Ekleme BAŞARISIZ: ${e.message}", e)
            }
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            historyDao.deleteAll()
        }
    }
    
    suspend fun deleteById(id: Int) {
        withContext(Dispatchers.IO) {
            historyDao.markAsDeleted(id)
        }
    }

    suspend fun updateRevokeTime(id: Int, time: Long) {
        withContext(Dispatchers.IO) {
            historyDao.updateRevokeTime(id, time)
        }
    }

    suspend fun getDeletedNonces(): Set<String> = withContext(Dispatchers.IO) {
        historyDao.getDeletedNonces().toSet()
    }

    // ---- Sync Operations ----

    /** Get all local nonces */
    suspend fun getAllNonces(): Set<String> = withContext(Dispatchers.IO) {
        historyDao.getAllNonces().toSet()
    }
    
    /** Get all Person IDs */
    suspend fun getAllPersonIds(): List<String> = withContext(Dispatchers.IO) {
        historyDao.getAllPersonIds()
    }

    /** Find item by nonce */
    suspend fun findByNonce(nonce: String): HistoryEntity? = withContext(Dispatchers.IO) {
        historyDao.findByNonce(nonce)
    }

    /** Insert a cloud item (decrypted from cloud -> re-encrypt for local) */
    suspend fun insertCloudItem(item: HistoryEntity) = withContext(Dispatchers.IO) {
        val encTitle = encryptString(item.title)
        val encDesc = encryptString(item.description)
        
        // Re-encrypt specific fields if needed, but for now Title/Desc are main ones
        // CardId/PersonId/PartnerId are plain in local DB or handled at app level
        
        val localItem = item.copy(
            id = 0, 
            isSent = true,
            title = encTitle, 
            description = encDesc
        )
        historyDao.insert(localItem)
    }

    /** Mark items as successfully sent to cloud */
    suspend fun markAsSent(nonces: List<String>) = withContext(Dispatchers.IO) {
        nonces.forEach { historyDao.markAsSent(it) }
    }

    /** Get all items that were sent to cloud */
    suspend fun getSentItems(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        historyDao.getSentItems()
    }

    /** Get items not yet sent to cloud */
    suspend fun getUnsentItems(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        historyDao.getUnsentItems()
    }

    /** Delete by nonce (Mark as deleted for sync) */
    suspend fun deleteByNonce(nonce: String) = withContext(Dispatchers.IO) {
        historyDao.markAsDeletedByNonce(nonce)
    }

    /** Permanently delete from local DB (Only if absolutely sure, e.g. already synced deletion) */
    suspend fun permanentlyDeleteByNonce(nonce: String) = withContext(Dispatchers.IO) {
        historyDao.deleteByNonce(nonce)
    }

    suspend fun cleanupSyncedTombstones() = withContext(Dispatchers.IO) {
        historyDao.cleanupSyncedTombstones()
    }

    /** Get raw (encrypted) snapshot for cloud upload - no decryption needed */
    suspend fun getAllRawSnapshot(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        historyDao.getAllHistorySnapshot()
    }

    // ---- Encryption ----

    private fun encryptString(plain: String): String {
        // 1. AES Encrypt
        val (blob, aesKey, _) = CryptoUtils.aesEncrypt(plain)
        
        // 2. RSA Encrypt AES Key (using History Key)
        val encAesKey = CryptoUtils.rsaEncryptForKeystore(aesKey, historyPubKey)
        
        // 3. Serialize to JSON
        val secureObj = SecureContent(encAesKey, blob)
        return gson.toJson(secureObj)
    }

    private fun decryptItem(item: HistoryEntity): HistoryEntity {
        return try {
            item.copy(
                title = decryptString(item.title),
                description = decryptString(item.description)
            )
        } catch (e: Exception) {
            item.copy(title = "Decryption Failed", description = "Error")
        }
    }

    private fun decryptString(json: String): String {
        try {
            val secureObj = gson.fromJson(json, SecureContent::class.java)
            
            // 1. Decrypt AES Key
            val aesKey = CryptoUtils.rsaDecryptHistory(secureObj.key)
            
            // 2. Decrypt Blob
            return CryptoUtils.aesDecrypt(secureObj.blob, aesKey)
        } catch (e: Exception) {
             return "Encrypted"
        }
    }
}
