package com.verifyblind.mobile.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import kotlinx.coroutines.tasks.await

object IntegrityManagerHelper {
    
    // Cloud Project Number - Updated by User
    private const val CLOUD_PROJECT_NUMBER = 295841149391 

    private var tokenProvider: StandardIntegrityTokenProvider? = null

    suspend fun prepare(context: Context) {
        if (tokenProvider != null) return
        try {
            val standardIntegrityManager = IntegrityManagerFactory.createStandard(context)
            val request = PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .build()
                
            tokenProvider = standardIntegrityManager.prepareIntegrityToken(request).await()
            Log.d("Integrity", "Standart Integrity Provider hazırlandı")
        } catch (e: Exception) {
            Log.e("Integrity", "Provider hazırlama başarısız", e)
            tokenProvider = null
        }
    }

    suspend fun requestIntegrityToken(context: Context, requestHash: String): String? {
        try {
            if (tokenProvider == null) {
                 Log.d("Integrity", "Provider hazır değil, şimdi hazırlanıyor...")
                 prepare(context)
            }
            
            val provider = tokenProvider ?: return null
            
            val tokenRequest = StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()
                
            val response = provider.request(tokenRequest).await()
            return response.token()
            
        } catch (e: Exception) {
            Log.e("Integrity", "Token alınamadı", e)
             // For testing/mocking when API is not set up yet, we might want to return a dummy token?
             // But in Real mode, this failure means "Unsafe Device" or "No Config".
             // Let's return null.
            return null
        }
    }
}
