package com.verifyblind.mobile.backup

import androidx.fragment.app.Fragment

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
}
