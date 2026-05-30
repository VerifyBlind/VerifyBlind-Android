package com.verifyblind.mobile.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Orchestrator for cloud backup operations.
 * Reads/writes wallet data from SharedPreferences and delegates to a CloudProvider.
 */
object CloudBackupManager {

    private const val BACKUP_FILENAME = "verifyblind_backup.json"
    private const val WALLET_PREFS = "VerifyBlind_Prefs"
    private const val USER_PREFS = "user_prefs"

    // Keys in user_prefs
    private const val KEY_PROVIDER = "cloud_provider"
    private const val KEY_LAST_BACKUP = "cloud_last_backup"

    data class BackupPayload(
        @SerializedName("history") val history: List<com.verifyblind.mobile.data.HistoryEntity>?,
        @SerializedName("partners") val partners: Map<String, com.verifyblind.mobile.data.PartnerItem>? = null
    )

    data class BackupStatus(
        val providerName: String?,
        val lastBackupTimestamp: Long
    ) {
        val isConnected get() = providerName != null
    }

    private val providers = mutableMapOf<String, CloudProvider>()
    private val gson = Gson()

    fun registerProvider(provider: CloudProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): CloudProvider? = providers[id]

    fun getAllProviders(): List<CloudProvider> = providers.values.toList()

    fun getStatus(context: Context): BackupStatus {
        val prefs = context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
        return BackupStatus(
            providerName = prefs.getString(KEY_PROVIDER, null),
            lastBackupTimestamp = prefs.getLong(KEY_LAST_BACKUP, 0)
        )
    }

    fun saveProviderChoice(context: Context, providerId: String?) {
        context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER, providerId)
            .apply()
    }

    fun saveLastBackupTimestamp(context: Context) {
        context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Backup current wallet data to the given provider.
     */
    suspend fun backup(context: Context, provider: CloudProvider, history: List<com.verifyblind.mobile.data.HistoryEntity>): Result<Unit> {
        return try {
            com.verifyblind.mobile.data.PartnerManager.init(context)
            val payload = BackupPayload(
                history = history,
                partners = com.verifyblind.mobile.data.PartnerManager.partners.value.takeIf { it.isNotEmpty() }
            )
            val json = gson.toJson(payload)

            // 3. Upload
            val result = provider.upload(BACKUP_FILENAME, json)

            if (result.isSuccess) {
                // 4. Save timestamp
                context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
                    .putString(KEY_PROVIDER, provider.id)
                    .apply()
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Restore wallet data from the given provider.
     * Returns the BackupPayload so the caller can handle history insertion.
     */
    suspend fun restore(context: Context, provider: CloudProvider): Result<BackupPayload> {
        return try {
            // 1. Download
            val result = provider.download(BACKUP_FILENAME)
            val json = result.getOrThrow()
            android.util.Log.i("VerifyBlind_Backup", "Downloaded JSON size: ${json?.length ?: 0}")
            if (json == null) return Result.failure(Exception("Bulutta yedek bulunamadı."))

            // 2. Parse
            val payload = gson.fromJson(json, BackupPayload::class.java)

            // 3. Restore partners into PartnerManager
            com.verifyblind.mobile.data.PartnerManager.init(context)
            payload.partners?.forEach { (_, item) ->
                val existing = com.verifyblind.mobile.data.PartnerManager.getPartner(item.id)
                if (existing == null || item.lastUpdated > existing.lastUpdated) {
                    com.verifyblind.mobile.data.PartnerManager.savePartner(item)
                }
            }

            // 4. Update status (Don't touch Keystore or UserPubKey!)
            context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER, provider.id)
                .apply()

            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disconnect current provider.
     */
    fun disconnect(context: Context) {
        val status = getStatus(context)
        status.providerName?.let { id ->
            getProvider(id)?.logout()
        }
        context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PROVIDER)
            .remove(KEY_LAST_BACKUP)
            .apply()
    }
}
