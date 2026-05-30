package com.verifyblind.mobile.backup

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.verifyblind.mobile.crypto.CryptoUtils
import com.verifyblind.mobile.data.AppDatabase
import com.verifyblind.mobile.data.HistoryEntity
import com.verifyblind.mobile.data.HistoryRepository
import com.verifyblind.mobile.data.PartnerItem
import com.verifyblind.mobile.data.PartnerManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bi-directional sync manager for cloud backup (Phase 8).
 * Encryption: Person_id based AES-GCM.
 */
object SyncManager {

    private const val TAG = "VerifyBlind_Sync"
    private const val BACKUP_FILENAME = "verifyblind_backup.json"

    // Cloud payload structure
    data class CloudPayload(
        val history: List<CloudHistoryItem>?,
        val partners: Map<String, PartnerItem>? = null
    )

    // Encrypted Cloud Item
    data class CloudHistoryItem(
        val enc: String,       // AES-GCM Blob (Base64)
        val iv: String,        // IV (Base64)
        val actionType: Int,
        val status: Int,
        val transactionId: String? = null
    )

    // Inner Payload (The data inside 'enc')
    data class InnerPayload(
        val title: String,
        val description: String,
        val cardId: String,
        val personId: String,
        val timestamp: Long,
        val nonce: String,
        val partnerId: String?
    )

    private val gson: Gson = GsonBuilder().create()
    private val isSyncing = AtomicBoolean(false)

    suspend fun performSync(context: Context): SyncResult {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(TAG, "Eşitleme zaten devam ediyor, atlanıyor")
            return SyncResult(skipped = true)
        }

        try {
            val status = CloudBackupManager.getStatus(context)
            val provider = status.providerName?.let { CloudBackupManager.getProvider(it) }

            if (provider == null || !provider.isLoggedIn()) {
                return SyncResult(error = "Bulut bağlantısı yok")
            }

            PartnerManager.init(context)
            val db = AppDatabase.getDatabase(context)
            val repo = HistoryRepository(db.historyDao())

            // 1. Get Local Keys (Person_ids)
            // We need all personIds to try decrypting cloud items.
            // But we also need the current local list to upload changes.
            // Let's get all local items first (Decrypted!).
            val localItems = repo.getAllHistorySnapshot() 
            // getAllHistorySnapshot decrypts title/desc with Local RSA Key.
            
            // Extract all unique personIds from local items to use as keys
            val localPersonIds = localItems.map { it.personId }.filter { it.isNotEmpty() }.distinct()
            
            // Also fetch from DB directly in case some are deleted but keys are needed? 
            // No, if item is deleted from DB, we might lose the PersonId if it was the only item. 
            // But PersonId is also stored in `SecureStore` (future requirement). 
            // For now, we rely on existing items. 
            // If user wiped phone -> no personIds -> can't decrypt cloud.
            // Wait! If user Wipes phone -> No items -> No PersonIds.
            // How do we restore from cloud?
            // "Telefonda kayıtlı olmayan Person_id ye ait kayıtlar görmezden gelinir."
            // "Kullanıcı telefonunu sıfırlayıp kartını tekrar kaydettiğinde ... aynı Person_id yeniden elde edilir."
            // So we rely on user adding at least one card to generate a PersonId, THEN we sync history for that person.
            // Correct.
            
            if (localPersonIds.isEmpty()) {
                 Log.w(TAG, "Yerel PersonId bulunamadı. Bulut öğeleri henüz çözülemiyor.")
                 // We can still download, but we won't be able to decrypt anything effectively 
                 // unless we have keys. 
                 // But we might have items to upload? (No, if no personIds, no items).
                 // Actually, if we have local items with empty personId (legacy?), we can't encrypt them properly.
                 // We should assume valid personIds.
            }

            // ===== PHASE 1: Download =====
            Log.i(TAG, "Bulut yedek indiriliyor...")
            val downloadResult = provider.download(BACKUP_FILENAME)
            val cloudItemsDecrypted = mutableListOf<HistoryEntity>()
            val cloudNonces = mutableSetOf<String>()

            if (downloadResult.isSuccess) {
                val json = downloadResult.getOrNull()
                if (json != null) {
                    val payload = gson.fromJson(json, CloudPayload::class.java)

                    // Restore partners: merge, keep newer by lastUpdated
                    payload?.partners?.forEach { (_, cloudPartner) ->
                        val local = PartnerManager.getPartner(cloudPartner.id)
                        if (local == null || cloudPartner.lastUpdated > local.lastUpdated) {
                            PartnerManager.savePartner(cloudPartner)
                        }
                    }

                    val rawList = payload?.history ?: emptyList()

                    // Decrypt Items
                    for (raw in rawList) {
                        var decryptedPayload: InnerPayload? = null
                        
                        // Try all known personIds
                        for (pid in localPersonIds) {
                            try {
                                val jsonStr = CryptoUtils.aesGcmDecrypt(raw.enc, raw.iv, pid)
                                val obj = gson.fromJson(jsonStr, InnerPayload::class.java)
                                // Verify pid matches
                                if (obj.personId == pid) {
                                    decryptedPayload = obj
                                    break
                                }
                            } catch (e: Exception) {
                                // Wrong key or corrupt
                                continue
                            }
                        }
                        
                        // If decrypted successfully
                        if (decryptedPayload != null) {
                            val entity = HistoryEntity(
                                title = decryptedPayload.title,
                                description = decryptedPayload.description,
                                actionType = raw.actionType,
                                status = raw.status,
                                timestamp = decryptedPayload.timestamp,
                                transactionId = raw.transactionId,
                                nonce = decryptedPayload.nonce,
                                cardId = decryptedPayload.cardId,
                                personId = decryptedPayload.personId,
                                partnerId = decryptedPayload.partnerId,
                                isSent = true
                            )
                            cloudItemsDecrypted.add(entity)
                            cloudNonces.add(decryptedPayload.nonce)
                        } else {
                            Log.d(TAG, "Bulut öğesi atlanıyor (yerel anahtarla çözülemiyor)")
                        }
                    }
                }
            }

            val localNonces = repo.getAllNonces()
            val deletedNonces = repo.getDeletedNonces()

            var itemsAdded = 0
            var itemsDeleted = 0
            var itemsUploaded = 0

            // ===== PHASE 2: Add missing to Local =====
            for (cloudItem in cloudItemsDecrypted) {
                if (cloudItem.nonce !in localNonces && cloudItem.nonce !in deletedNonces) {
                    repo.insertCloudItem(cloudItem)
                    itemsAdded++
                }
            }

            // ===== PHASE 3: Delete local sent items missing in Cloud =====
            // But only if we successfully downloaded and parsed (to avoid deleting on network error)
            if (downloadResult.isSuccess) {
                val sentItems = repo.getSentItems() // These are encrypted? Repo decrypts?
                // `getSentItems` in Repo (Step 9805) calls `decryptItem`. Correct.
                
                for (sentItem in sentItems) {
                    if (sentItem.nonce !in cloudNonces) {
                        // Check if this sent item belongs to a PersonId we know (and thus should have seen in cloud)
                        // If we didn't decrypt it from cloud because we lost the key, but we have it locally?
                        // If we have it locally, we HAVE the PersonId in the entity.
                        // So if we have the PersonId, and we didn't see it in cloud, it means it was deleted from cloud.
                        repo.deleteByNonce(sentItem.nonce)
                        itemsDeleted++
                    }
                }
            }

            // ===== PHASE 4: Upload =====
            // We upload ALL valid local items (re-generating cloud payload)
            // This handles "upload new" and "maintain existing" in one go.
            // Items are encrypted with their respective PersonId.
            
            val allLocal = repo.getAllHistorySnapshot() // Decrypted, only non-deleted
            val uploadList = mutableListOf<CloudHistoryItem>()
            val unsentNonces = mutableListOf<String>()

            // 1. Prepare Upload List (Active items only)
            for (item in allLocal) {
                if (item.personId.isNotEmpty()) {
                    val inner = InnerPayload(
                        title = item.title,
                        description = item.description,
                        cardId = item.cardId,
                        personId = item.personId,
                        timestamp = item.timestamp,
                        nonce = item.nonce,
                        partnerId = item.partnerId
                    )
                    val innerJson = gson.toJson(inner)
                    try {
                        val (enc, iv) = CryptoUtils.aesGcmEncrypt(innerJson, item.personId)
                        uploadList.add(CloudHistoryItem(enc, iv, item.actionType, item.status, item.transactionId))
                        if (!item.isSent) unsentNonces.add(item.nonce)
                    } catch (e: Exception) {
                        Log.e(TAG, "Öğe şifrelenemedi ${item.nonce}: ${e.message}")
                    }
                }
            }
            
            // 2. Add deleted nonces to the 'to be marked as sent' list
            // If we are uploading a list that EXCLUDES them, their "deletion" is effectively sent.
            val localUnsent = repo.getUnsentItems() // Includes isSent=0, even if isDeleted=1
            for (item in localUnsent) {
                if (item.isDeleted && item.nonce !in unsentNonces) {
                    unsentNonces.add(item.nonce)
                }
            }
            
            Log.d(TAG, "Eşitleme: Yerel liste: ${allLocal.size}. Gönderilmemiş aday: ${unsentNonces.size}")
            
            if (unsentNonces.isNotEmpty() || itemsDeleted > 0 || itemsAdded > 0) {
                 Log.i(TAG, "Eşitleme: Değişiklikler buluta yükleniyor. Gönderildi olarak işaretlenecek: ${unsentNonces.size}")
                 // Always upload full list if there are changes (e.g. deletions)
                 // Or if there are unsent items.
                 // Actually, if itemsDeleted > 0, we removed them from local, so `uploadList` won't have them.
                 // Uploading `uploadList` effectively syncs deletions to cloud too (if we overwrite).
                 // Google Drive update overwrites the file.
                 
                 val cloudPayload = CloudPayload(
                     history = uploadList,
                     partners = PartnerManager.partners.value.takeIf { it.isNotEmpty() }
                 )
                 val json = gson.toJson(cloudPayload)
                 
                 val uploadRes = provider.upload(BACKUP_FILENAME, json)
                 if (uploadRes.isSuccess) {
                     repo.markAsSent(unsentNonces)
                     repo.cleanupSyncedTombstones()
                     itemsUploaded = unsentNonces.size
                     Log.i(TAG, "Eşitleme: Yükleme BAŞARILI. $itemsUploaded öğe gönderildi olarak işaretlendi.")
                 }
            }

            CloudBackupManager.saveLastBackupTimestamp(context)
            return SyncResult(itemsAdded, itemsDeleted, itemsUploaded)

        } catch (e: Exception) {
            Log.e(TAG, "Eşitleme başarısız", e)
            return SyncResult(error = e.message)
        } finally {
            isSyncing.set(false)
        }
    }

    data class SyncResult(
        val itemsAdded: Int = 0,
        val itemsDeleted: Int = 0,
        val itemsUploaded: Int = 0,
        val error: String? = null,
        val skipped: Boolean = false
    ) {
        val isSuccess get() = error == null && !skipped
        val hasChanges get() = itemsAdded > 0 || itemsDeleted > 0 || itemsUploaded > 0
    }
}
