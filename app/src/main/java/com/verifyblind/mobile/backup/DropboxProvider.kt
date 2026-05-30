package com.verifyblind.mobile.backup

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.WriteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Dropbox provider using official Dropbox SDK v7.
 * Uses Auth.startOAuth2PKCE for seamless OAuth with automatic redirect.
 */
class DropboxProvider(private val context: Context) : CloudProvider {

    override val id = "dropbox"
    override val displayName = "Dropbox"

    companion object {
        private const val TAG = "DropboxProvider"
        const val APP_KEY = "dtraxzj2rcv7vrg"

        private const val PREFS_NAME = "dropbox_prefs"
        private const val KEY_CREDENTIAL = "credential"

        // Track whether OAuth flow was explicitly started
        @Volatile
        var isOAuthFlowInProgress = false
    }

    override fun isLoggedIn(): Boolean {
        return getStoredCredential() != null
    }

    override suspend fun login(fragment: Fragment): Boolean {
        withContext(Dispatchers.Main) {
            isOAuthFlowInProgress = true
            val requestConfig = DbxRequestConfig("VerifyBlind")
            val scopes = listOf("files.content.write", "files.content.read")
            com.dropbox.core.android.Auth.startOAuth2PKCE(
                fragment.requireContext(),
                APP_KEY,
                requestConfig,
                scopes
            )
        }
        return true
    }

    /**
     * Call from onResume after Dropbox OAuth redirect returns to the app.
     * Returns true if a new credential was captured and saved.
     */
    fun checkForAuthResult(): Boolean {
        // Only check if we explicitly started an OAuth flow
        if (!isOAuthFlowInProgress) return false
        
        val credential = com.dropbox.core.android.Auth.getDbxCredential() as? DbxCredential
        if (credential != null) {
            isOAuthFlowInProgress = false
            saveCredential(credential)
            Log.d(TAG, "Dropbox işlem geçmişi başarıyla alındı")
            return true
        }
        // Flow was started but no result yet (user might have cancelled)
        isOAuthFlowInProgress = false
        return false
    }

    override fun logout() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun getClient(): DbxClientV2? {
        val credential = getStoredCredential() ?: return null
        val config = DbxRequestConfig("VerifyBlind")
        return DbxClientV2(config, credential)
    }

    override suspend fun upload(filename: String, data: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
                ?: return@withContext Result.failure(Exception("Dropbox oturumu yok."))

            val inputStream = ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))
            client.files().uploadBuilder("/$filename")
                .withMode(WriteMode.OVERWRITE)
                .withAutorename(false)
                .withMute(true)
                .uploadAndFinish(inputStream)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Yükleme başarısız: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun download(filename: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
                ?: return@withContext Result.failure(Exception("Dropbox oturumu yok."))

            val outputStream = ByteArrayOutputStream()
            client.files().download("/$filename").download(outputStream)
            Result.success(outputStream.toString(Charsets.UTF_8.name()))
        } catch (e: com.dropbox.core.v2.files.DownloadErrorException) {
            // File not found
            if (e.errorValue?.isPath == true) {
                Result.success(null)
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "İndirme başarısız: ${e.message}")
            Result.failure(e)
        }
    }

    // --- Credential Storage ---

    private fun saveCredential(credential: DbxCredential) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("access_token", credential.accessToken)
            .putString("refresh_token", credential.refreshToken)
            .putString("app_key", credential.appKey)
            .putLong("expires_at", credential.expiresAt)
            .apply()
    }

    private fun getStoredCredential(): DbxCredential? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString("access_token", null) ?: return null
        val refreshToken = prefs.getString("refresh_token", null)
        val appKey = prefs.getString("app_key", APP_KEY)
        val expiresAt = prefs.getLong("expires_at", 0L)

        return DbxCredential(accessToken, expiresAt, refreshToken, appKey)
    }
}
