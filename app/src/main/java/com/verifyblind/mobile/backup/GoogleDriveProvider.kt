package com.verifyblind.mobile.backup

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.verifyblind.mobile.util.AppLog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
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

        /** Yedeklemenin ihtiyaç duyduğu TEK kapsam. Giriş kontrolü de bu kapsamı arar. */
        private val DRIVE_SCOPE = Scope(DriveScopes.DRIVE_APPDATA)
    }

    override fun isLoggedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        // Oturumun VARLIĞI değil, Drive İZNİ olan bir oturum aranıyor.
        //
        // Google'ın ayrıntılı izin (granular consent) ekranında kullanıcı Drive kutucuğunu
        // işaretlemeden "Continue"ya basabiliyor: hesap döner, kapsam dönmez. Yalnız hesaba
        // bakarsak burada true deriz — çağıran taraf (BackupFragment.withCloud) login()'i bir
        // daha HİÇ çalıştırmaz, kullanıcı izin ekranını tekrar göremez ve yükleme her seferinde
        // 403 ile düşer. Tek çıkış uygulamayı silip yeniden kurmak olurdu.
        //
        // Kapsamı burada kontrol etmek eksik izni kendiliğinden onarır: izinsiz oturum "giriş
        // yapılmamış" sayılır, sonraki denemede login() çalışır ve Google izni tekrar sorar.
        // iOS GoogleDriveProvider.isLoggedIn() ile aynı davranış.
        return GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)
    }

    private var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    private var loginContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    var lastError: String? = null

    override var lastLoginError: CloudLoginError? = null
        private set

    fun register(fragment: Fragment) {
        launcher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account == null) {
                    lastError = "Giriş hesabı null döndü"
                    Log.w(TAG, lastError!!)
                    loginContinuation?.resume(false)
                } else if (!GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)) {
                    // Hesap seçildi ama Drive kutucuğu işaretlenmedi → giriş BAŞARILI DEĞİL.
                    // Burada true dönersek çağıran taraf yüklemeye gider ve 403 alır; kullanıcı
                    // "giriş yaptım ama yükleme patlıyor" durumunda kalır.
                    lastError = "Drive kapsamı onaylanmadı (izin kutucuğu işaretlenmedi)"
                    lastLoginError = CloudLoginError.PERMISSION_DENIED
                    AppLog.warning("Drive giriş başarısız: $lastError", TAG)
                    loginContinuation?.resume(false)
                } else {
                    initDriveService()
                    loginContinuation?.resume(true)
                }
            } catch (e: com.google.android.gms.common.api.ApiException) {
                lastError = "Code: ${e.statusCode} (${e.status.statusMessage})"
                AppLog.warning("Drive giriş başarısız: $lastError", TAG, e)
                loginContinuation?.resume(false)
            } catch (e: Exception) {
                lastError = e.message
                AppLog.warning("Drive giriş başarısız: $lastError", TAG, e)
                loginContinuation?.resume(false)
            } finally {
                loginContinuation = null
            }
        }
    }

    override suspend fun login(fragment: Fragment): Boolean = suspendCancellableCoroutine { cont ->
        if (launcher == null) {
            AppLog.error("Launcher kayıtlı değil! onCreate içinde register() çağırın.", TAG)
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        loginContinuation = cont
        lastLoginError = null

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_SCOPE)
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
            .requestScopes(DRIVE_SCOPE)
            .build()
        GoogleSignIn.getClient(context, gso).signOut()
        driveService = null
    }

    /**
     * Kimlik/izin hatasında YEREL oturumu temizler ve true döner.
     *
     * İkinci savunma hattı: izin sonradan geri alınabilir (kullanıcı myaccount.google.com'dan
     * erişimi kaldırır) ya da token geçersizleşir. Oturumu temizlemezsek isLoggedIn() true
     * kalır, login() bir daha çağrılmaz ve kullanıcı yine uygulamayı silmeden kurtulamaz.
     *
     * Yalnız kimlik/izin hataları temizler — hız sınırı/kota gibi geçici 403'ler değil.
     */
    private fun clearSignInIfAuthError(e: Exception): Boolean {
        val isAuthError = when (e) {
            is UserRecoverableAuthIOException -> true
            is GoogleJsonResponseException -> {
                val reason = e.details?.errors?.firstOrNull()?.reason
                e.statusCode == 401 ||
                    (e.statusCode == 403 && (reason == "insufficientPermissions" || reason == "forbidden"))
            }
            else -> false
        }
        if (isAuthError) {
            AppLog.warning("Drive yetkisi geçersiz -> yerel oturum temizlendi, sonraki denemede izin yeniden istenecek", TAG, e)
            logout()
        }
        return isAuthError
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
            clearSignInIfAuthError(e)
            AppLog.warning("Drive yükleme başarısız: ${e.message}", TAG, e)
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
            clearSignInIfAuthError(e)
            AppLog.warning("Drive indirme başarısız: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun delete(filename: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (driveService == null) initDriveService()
            val service = driveService ?: return@withContext Result.failure(Exception("Drive bağlantısı kurulamadı."))
            val fileId = findFile(service, filename)
            if (fileId != null) service.files().delete(fileId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            clearSignInIfAuthError(e)
            AppLog.warning("Drive silme başarısız: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun list(suffix: String): Result<List<CloudFileEntry>> = withContext(Dispatchers.IO) {
        try {
            if (driveService == null) initDriveService()
            val service = driveService ?: return@withContext Result.failure(Exception("Drive bağlantısı kurulamadı."))

            val result = service.files().list()
                .setSpaces("appDataFolder")
                .setQ("name contains '$suffix' and trashed = false")
                .setFields("files(id, name, modifiedTime)")
                .setPageSize(100)
                .execute()

            val entries = result.files.orEmpty()
                .filter { it.name.endsWith(suffix, ignoreCase = true) }
                .map { CloudFileEntry(it.name, it.modifiedTime?.value) }
                .sortedByDescending { it.modifiedAtMillis ?: 0L }
            Result.success(entries)
        } catch (e: Exception) {
            clearSignInIfAuthError(e)
            AppLog.warning("Drive listeleme başarısız: ${e.message}", TAG, e)
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
