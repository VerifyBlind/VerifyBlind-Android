package com.verifyblind.mobile.backup

import androidx.fragment.app.Fragment

/** Bulut sağlayıcıdaki bir yedek dosyası — geri-yükleme listesinde gösterilir. */
data class CloudFileEntry(
    val name: String,
    /** Sağlayıcının bildirdiği değişiklik zamanı (ms); bilinmiyorsa null. Liste sıralaması için. */
    val modifiedAtMillis: Long?
)

/**
 * `login()` başarısızlığının ayırt edici sebebi — kullanıcıya doğru mesajı seçebilmek için.
 * null = genel/bilinmeyen hata (genel mesaj gösterilir).
 */
enum class CloudLoginError {
    /** Hesap seçildi ama istenen kapsam onaylanmadı (ayrıntılı izin ekranında kutucuk işaretlenmedi). */
    PERMISSION_DENIED
}

/**
 * Abstraction for cloud storage providers (Google Drive, Dropbox, OneDrive).
 * Each implementation handles its own OAuth login + file upload/download.
 */
interface CloudProvider {

    /** Unique key for this provider (e.g. "google_drive") */
    val id: String

    /** Display name for UI (e.g. "Google Drive") */
    val displayName: String

    /** Whether user is currently authenticated */
    fun isLoggedIn(): Boolean

    /**
     * Son `login()` denemesinin ayırt edici hatası; yoksa null.
     * Varsayılan null — yalnız sebebi ayırt edebilen sağlayıcılar override eder.
     */
    val lastLoginError: CloudLoginError? get() = null

    /**
     * Trigger OAuth login flow.
     * The fragment is used to launch ActivityResult contracts.
     * Returns true on success.
     */
    suspend fun login(fragment: Fragment): Boolean

    /** Logout and clear tokens */
    fun logout()

    /**
     * Upload data to cloud storage.
     * @param filename Name of the file to create/overwrite
     * @param data UTF-8 string content
     */
    suspend fun upload(filename: String, data: String): Result<Unit>

    /**
     * Download data from cloud storage.
     * @param filename Name of the file to read
     * @return File contents as UTF-8 string, or null if not found
     */
    suspend fun download(filename: String): Result<String?>

    /**
     * Delete a file from cloud storage.
     * Returns success even if the file did not exist.
     */
    suspend fun delete(filename: String): Result<Unit>

    /**
     * Belirtilen uzantıyla biten dosyaları listeler (ör. ".vfbackup"), en yeni önce.
     * Manuel geri-yükleme akışının dosya seçtirme adımı için.
     */
    suspend fun list(suffix: String): Result<List<CloudFileEntry>>
}
