package com.verifyblind.mobile.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores sensitive identifiers (personId, cardId) using Keystore-backed encryption.
 */
object SecureStore {

    private const val PREFS_NAME = "VerifyBlind_SecurePrefs"

    private fun getSharedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Android Keystore can get corrupted (e.g., app reinstall, biometrics change)
            // If it throws, delete the corrupted SharedPreferences file and try again
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                val file = java.io.File(dir, "$PREFS_NAME.xml")
                if (file.exists()) file.delete()
                
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (ex: Exception) {
                // Return a non-null message so the caller doesn't show "null"
                throw Exception("SecureStore Fallback Failed: ${ex.message ?: ex.toString()}")
            }
        }
    }

    fun saveIds(context: Context, personId: String, cardId: String) {
        getSharedPrefs(context).edit()
            .putString("personId", personId)
            .putString("cardId", cardId)
            .apply()
    }

    fun getPersonId(context: Context): String? {
        return getSharedPrefs(context).getString("personId", null)
    }

    fun getCardId(context: Context): String? {
        return getSharedPrefs(context).getString("cardId", null)
    }

    // --- DEK (bulut yedek veri anahtarı) ---
    //
    // DEK rastgeledir ve personId'den TÜRETİLEMEZ → yerelde saklanmak zorunda. Buradaki kopya
    // yalnızca bir ÖNBELLEKTİR: DEK'in kalıcı evi bulut yedeğindeki KEK-sarılı wrap'tir ve
    // senkronda BULUT OTORİTEDİR (açılabilen wrap varsa o DEK benimsenir, buradaki üzerine yazılır).
    // Önbellek yalnız "bulutta henüz wrap yok" (ilk yedek) durumunda üretilen DEK'i iki senkron
    // arasında yaşatmak için var.

    fun saveDek(context: Context, personId: String, dekB64: String) {
        getSharedPrefs(context).edit().putString("dek_$personId", dekB64).apply()
    }

    fun getDek(context: Context, personId: String): String? {
        return getSharedPrefs(context).getString("dek_$personId", null)
    }

    // --- PIN UUID (TCKN'siz kimlikler) ---
    //
    // UUID sır DEĞİLDİR — yedek dosyasında düz metin durur. Görevi per-user salt: salt olmasaydı
    // aynı PIN'i seçen iki kullanıcı aynı person_id'yi türetir ve birbirlerinin yedeğini çözebilirdi.

    fun savePinUuid(context: Context, personId: String, uuid: String) {
        getSharedPrefs(context).edit().putString("pinuuid_$personId", uuid).apply()
    }

    fun getPinUuid(context: Context, personId: String): String? {
        return getSharedPrefs(context).getString("pinuuid_$personId", null)
    }

    fun saveFcmToken(context: Context, token: String) {
        getSharedPrefs(context).edit().putString("fcm_token", token).apply()
    }

    fun getFcmToken(context: Context): String? {
        return getSharedPrefs(context).getString("fcm_token", null)
    }

    fun clear(context: Context) {
        getSharedPrefs(context).edit().clear().apply()
    }
}
