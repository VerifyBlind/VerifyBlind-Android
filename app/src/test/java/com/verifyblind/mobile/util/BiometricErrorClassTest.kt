package com.verifyblind.mobile.util

import androidx.biometric.BiometricPrompt
import com.verifyblind.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BiometricPrompt hata kodu sınıflandırması — Sentry gürültüsünün ve hata diyaloğunun tek kaynağı.
 *
 * Regresyon zemini: 2026-08-21'de kullanıcı kart ekleme sırasında telefonu masaya bıraktı, prompt
 * ~106 sn dokunulmadan kaldı ve sistem onu iptal etti (ERROR_CANCELED). Eski kod yalnız kod 10/13'ü
 * "iptal" saydığı için bu durum gerçek hata gibi işlenip Sentry'ye ERROR event yolladı
 * (VERIFYBLIND-ANDROID-W). Aşağıdaki eşleme bozulursa aynı gürültü geri gelir.
 *
 * Sayısal değerler bilerek literal yazıldı: androidx sabitleri derleme anında satır içine alınır,
 * bir sürüm yükseltmesi değeri kaydırırsa bu test yakalar.
 */
class BiometricErrorClassTest {

    @Test
    fun androidxConstants_haveExpectedNumericValues() {
        assertEquals(1, BiometricPrompt.ERROR_HW_UNAVAILABLE)
        assertEquals(2, BiometricPrompt.ERROR_UNABLE_TO_PROCESS)
        assertEquals(3, BiometricPrompt.ERROR_TIMEOUT)
        assertEquals(5, BiometricPrompt.ERROR_CANCELED)
        assertEquals(7, BiometricPrompt.ERROR_LOCKOUT)
        assertEquals(9, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        assertEquals(10, BiometricPrompt.ERROR_USER_CANCELED)
        assertEquals(13, BiometricPrompt.ERROR_NEGATIVE_BUTTON)
    }

    @Test
    fun systemCancelAndTimeout_areCancelled_notErrors() {
        // Asıl regresyon: 5 ve 3 eskiden FAILURE sayılıyordu.
        assertEquals(BiometricHelper.ErrorClass.CANCELLED, BiometricHelper.classify(BiometricPrompt.ERROR_CANCELED))
        assertEquals(BiometricHelper.ErrorClass.CANCELLED, BiometricHelper.classify(BiometricPrompt.ERROR_TIMEOUT))
    }

    @Test
    fun userCancelAndNegativeButton_areCancelled() {
        assertEquals(BiometricHelper.ErrorClass.CANCELLED, BiometricHelper.classify(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(BiometricHelper.ErrorClass.CANCELLED, BiometricHelper.classify(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
    }

    @Test
    fun lockoutAndTransientHardware_areRecoverable() {
        assertEquals(BiometricHelper.ErrorClass.RECOVERABLE, BiometricHelper.classify(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(BiometricHelper.ErrorClass.RECOVERABLE, BiometricHelper.classify(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
        assertEquals(BiometricHelper.ErrorClass.RECOVERABLE, BiometricHelper.classify(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertEquals(BiometricHelper.ErrorClass.RECOVERABLE, BiometricHelper.classify(BiometricPrompt.ERROR_UNABLE_TO_PROCESS))
    }

    @Test
    fun unknownAndInitFailure_areFailures() {
        assertEquals(BiometricHelper.ErrorClass.FAILURE, BiometricHelper.classify(BiometricHelper.ERROR_INIT_FAILED))
        assertEquals(BiometricHelper.ErrorClass.FAILURE, BiometricHelper.classify(8))   // ERROR_VENDOR
        assertEquals(BiometricHelper.ErrorClass.FAILURE, BiometricHelper.classify(999)) // bilinmeyen
    }

    @Test
    fun userMessage_lockoutHasItsOwnText() {
        assertEquals(R.string.biometric_error_lockout_message, BiometricHelper.userMessageRes(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(R.string.biometric_error_lockout_message, BiometricHelper.userMessageRes(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
        assertEquals(R.string.biometric_error_retry_message, BiometricHelper.userMessageRes(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertEquals(R.string.biometric_error_retry_message, BiometricHelper.userMessageRes(BiometricHelper.ERROR_INIT_FAILED))
    }

    /** Sentry gruplaması: mesajda yalnız sayısal kod olmalı, cihaz diline göre değişen metin OLMAMALI. */
    @Test
    fun exceptionMessage_containsOnlyNumericCode() {
        val e = BiometricHelper.BiometricAuthException(
            BiometricPrompt.ERROR_LOCKOUT,
            "Too many attempts. Try again later."
        )
        assertEquals("Biometric error (code=7)", e.message)
        assertEquals(BiometricHelper.ErrorClass.RECOVERABLE, e.errorClass)
        assertEquals("Too many attempts. Try again later.", e.systemMessage)
    }
}
