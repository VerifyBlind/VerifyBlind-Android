package com.verifyblind.mobile.backup

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GoogleDriveProvider(private val context: Context) : CloudProvider {

    override val id = "google_drive"
    override val displayName = "Google Drive"

    private var driveService: Drive? = null

    companion object {
        private const val TAG = "GoogleDriveProvider"
    }

    override fun isLoggedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    private var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    private var loginContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    var lastError: String? = null

    fun register(fragment: Fragment) {
        launcher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account != null) {
                    initDriveService()
                    loginContinuation?.resume(true)
                } else {
                    lastError = "Giriş hesabı null döndü"
                    Log.w(TAG, lastError!!)
                    loginContinuation?.resume(false)
                }
            } catch (e: com.google.android.gms.common.api.ApiException) {
                lastError = "Code: ${e.statusCode} (${e.status.statusMessage})"
                Log.e(TAG, "Giriş başarısız: $lastError")
                loginContinuation?.resume(false)
            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "Giriş başarısız: $lastError")
                loginContinuation?.resume(false)
            } finally {
                loginContinuation = null
            }
        }
    }

    override suspend fun login(fragment: Fragment): Boolean = suspendCancellableCoroutine { cont ->
        if (launcher == null) {
            Log.e(TAG, "Launcher kayıtlı değil! onCreate içinde register() çağırın.")
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        loginContinuation = cont
        
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        
        try {
            launcher?.launch(client.signInIntent)
        } catch (e: Exception) {
            cont.resume(false)
            loginContinuation = null
        }
    }

    override fun logout() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        GoogleSignIn.getClient(context, gso).signOut()
        driveService = null
    }

    private fun initDriveService() {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("VerifyBlind")
            .build()
    }

    override suspend fun upload(filename: String, data: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (driveService == null) initDriveService()
            val service = driveService ?: return@withContext Result.failure(Exception("Drive bağlantısı kurulamadı."))

            // Check if file already exists
            val existingId = findFile(service, filename)

            if (existingId != null) {
                // Update existing
                val content = ByteArrayContent.fromString("application/json", data)
                service.files().update(existingId, null, content).execute()
            } else {
                // Create new in appDataFolder
                val metadata = com.google.api.services.drive.model.File()
                    .setName(filename)
                    .setParents(listOf("appDataFolder"))
                val content = ByteArrayContent.fromString("application/json", data)
                service.files().create(metadata, content)
                    .setFields("id")
                    .execute()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Yükleme başarısız: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun download(filename: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (driveService == null) initDriveService()
            val service = driveService ?: return@withContext Result.failure(Exception("Drive bağlantısı kurulamadı."))

            val fileId = findFile(service, filename)
                ?: return@withContext Result.success(null)

            val outputStream = java.io.ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            Result.success(outputStream.toString("UTF-8"))
        } catch (e: Exception) {
            Log.e(TAG, "İndirme başarısız: ${e.message}")
            Result.failure(e)
        }
    }

    private fun findFile(service: Drive, filename: String): String? {
        val result = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$filename'")
            .setFields("files(id, name)")
            .setPageSize(1)
            .execute()
        return result.files?.firstOrNull()?.id
    }
}
