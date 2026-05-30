package com.verifyblind.mobile.util

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.verifyblind.mobile.R
import javax.crypto.Cipher

object BiometricHelper {
    private fun isCancelCode(errorCode: Int) =
        errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON

    fun authenticateForDecrypt(
        activity: FragmentActivity,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onCancel: () -> Unit,
        onError: (String) -> Unit
    ) {
        activity.runOnUiThread {
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.biometric_title))
                    .setSubtitle(activity.getString(R.string.biometric_subtitle_decrypt))
                    .setNegativeButtonText(activity.getString(R.string.btn_cancel))
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            val cryptoObject = result.cryptoObject
                            if (cryptoObject?.cipher != null) {
                                onSuccess(cryptoObject.cipher!!)
                            } else {
                                onSuccess(cipher)
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (isCancelCode(errorCode)) onCancel() else onError(errString.toString())
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // System handles UI
                        }
                    })

                biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                onError("Biometric Init Failed: ${e.message}")
            }
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        activity.runOnUiThread {
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.biometric_title))
                    .setSubtitle(activity.getString(R.string.biometric_subtitle_auth))
                    .setNegativeButtonText(activity.getString(R.string.btn_cancel))
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            // Ignore User Cancel (code 10 or 13) if we want to keep lock screen
                            onError(errString.toString())
                        }
                    })

                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                onError("Biometric Init Failed: ${e.message}")
            }
        }
    }
}
