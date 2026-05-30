package com.verifyblind.mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose

@Entity(
    tableName = "history_table",
    indices = [Index(value = ["nonce"], unique = false)]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @Expose(serialize = false) // Exclude from cloud JSON
    val id: Int = 0,
    
    val title: String, // Encrypted with LocalKey (for now) -> Repacked for Cloud
    val description: String, // Encrypted
    val actionType: Int = 0, 
    val status: Int, 
    val timestamp: Long,
    
    val transactionId: String? = null,
    val nonce: String, 
    
    // New Fields for Phase 8
    val personId: String = "", 
    
    val cardId: String = "",
    
    val partnerId: String? = null, // Replaces logoUrl

    @ColumnInfo(defaultValue = "0")
    @Expose(serialize = false) 
    val isSent: Boolean = false,
    
    @ColumnInfo(defaultValue = "0")
    @Expose(serialize = false)
    val isDeleted: Boolean = false,

    val revokeTime: Long? = null
)
/*
 * NOTE: 
 * personId and cardId are stored in the local database. 
 * Whether they are encrypted in the local DB depends on implementation. 
 * For simplicity in querying or sync, we might keep them plain or obfuscated, 
 * but the REQUIREMENT is for CLOUD sync to be encrypted with Person_id.
 * 
 * Ideally, title/description are encrypted with a key derived from Person_id or Device Key.
 * Current implementation uses a device-specific RSA key.
 * 
 * For this phase, we add the fields.
 */

object HistoryAction {
    const val GENERIC = 0
    const val REGISTRATION = 1
    const val SHARED_IDENTITY = 2
    const val DELETED_CARD = 3
    const val RESTORED_BACKUP = 4
    const val REVOKED_IDENTITY = 5
}
