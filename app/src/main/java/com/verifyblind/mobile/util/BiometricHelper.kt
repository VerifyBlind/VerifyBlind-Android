package com.verifyblind.mobile.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.verifyblind.mobile.R

object BiometricHelper {

    /** Prompt hiç kurulamadıysa (build/authenticate exception) kullanılan sentinel kod. */
    const val ERROR_INIT_FAILED = -1

    /**
     * BiometricPrompt hata kodu sınıfı — hem UX hem Sentry seviyesi bunun üzerinden karar verir.
     *
     * Neden kod bazlı: önce yalnız ERROR_USER_CANCELED/ERROR_NEGATIVE_BUTTON "iptal" sayılıyordu.
     * Kullanıcı prompt'a hiç dokunmayınca gelen ERROR_CANCELED (ekran kapandı / sensör devre dışı) ve
     * ERROR_TIMEOUT "gerçek hata" gibi işlenip kullanıcıya teknik metinli hata diyaloğu gösteriyor,
     * üstelik Sentry'ye ERROR event yolluyordu (VERIFYBLIND-ANDROID-W, 2026-08-21). İkisi de bizim
     * kodumuzda arıza değil; beklenen istemci durumu.
     */
    enum class ErrorClass {
        /** Prompt kullanıcı ya da sistem tarafından kapatıldı → sessiz iptal, Sentry'ye EVENT YOK. */
        CANCELLED,
        /** Kullanıcı düzeltebilir (deneme kilidi, sensör geçici meşgul) → Sentry WARNING. */
        RECOVERABLE,
        /** Beklenmeyen durum → Sentry ERROR. */
        FAILURE
    }

    fun classify(errorCode: Int): ErrorClass = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,       // 10 — kullanıcı prompt dışına/geri dokundu
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,     // 13 — negatif buton
        BiometricPrompt.ERROR_CANCELED,            // 5  — sistem iptal etti (ekran kapandı, sensör başkasında)
        BiometricPrompt.ERROR_TIMEOUT ->           // 3  — kullanıcı sensöre hiç dokunmadı
            ErrorClass.CANCELLED

        BiometricPrompt.ERROR_LOCKOUT,             // 7  — çok fazla başarısız deneme (30 sn)
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,   // 9  — kalıcı kilit, cihaz kilidiyle açılır
        BiometricPrompt.ERROR_HW_UNAVAILABLE,      // 1  — donanım geçici meşgul
        BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> // 2  — sensör okumayı işleyemedi
            ErrorClass.RECOVERABLE

        else -> ErrorClass.FAILURE
    }

    /** Kullanıcıya gösterilecek mesaj — sistemin `errString`'i ASLA ekrana basılmaz (teknik/İngilizce). */
    fun userMessageRes(errorCode: Int): Int = when (errorCode) {
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> R.string.biometric_error_lockout_message
        else -> R.string.biometric_error_retry_message
    }

    /**
     * Biyometrik prompt hatası. Exception mesajında YALNIZCA sayısal kod bulunur: sistemin `errString`'i
     * cihaz diline ve üreticiye göre değişir ve Sentry'de aynı durumu ayrı issue'lara böler (mesaja
     * nonce/GUID gömme hatasının eşi). Ham sistem metni [systemMessage] içinde durur, logcat'e gider.
     */
    class BiometricAuthException(
        val errorCode: Int,
        val systemMessage: String
    ) : Exception("Biometric error (code=$errorCode)") {
        val errorClass: ErrorClass get() = classify(errorCode)
    }

    /**
     * User key'in (V6) kabul ettiği authenticator kümesi — biyometri VEYA cihaz kilidi.
     * `CryptoUtils.ensureKeyExists` ile AYNI olmak ZORUNDA: anahtar hangi kümeyle üretildiyse
     * prompt da onunla açılır. Ayrışırlarsa anahtar üretilir ama açılamaz.
     */
    private const val ALLOWED_AUTHENTICATORS =
        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Cihaz kilidi durumu — anahtar ÜRETMEDEN ÖNCE kontrol edilir.
     *
     * V6'da biyometri VEYA cihaz kilidi yeterli olduğu için "kayıtlı biyometri yok" ARTIK ENGEL
     * DEĞİL (V5'te öyleydi ve Sentry'de 81 InvalidAlgorithmParameterException üretiyordu).
     * Geriye tek gerçek engel kalıyor: cihazda **hiç** ekran kilidi olmaması.
     */
    enum class Availability {
        /** Biyometri ya da cihaz kilidi var — anahtar üretilebilir. */
        AVAILABLE,
        /** Cihazda HİÇ ekran kilidi yok (ne biyometri ne PIN/desen/şifre) → kullanıcı düzeltebilir. */
        NO_DEVICE_LOCK,
        /** Geçici olarak kullanılamıyor (donanım meşgul vb.) ya da bilinmeyen durum. */
        UNAVAILABLE
    }

    fun availability(context: android.content.Context): Availability {
        val manager = androidx.biometric.BiometricManager.from(context)
        return when (manager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS ->
                Availability.AVAILABLE
            // DEVICE_CREDENTIAL dahil sorulduğu için bu kod ancak HİÇBİR kilit yokken döner.
            // (NO_HARDWARE da aynı anlama gelir: biyometri donanımı yok VE kilit kurulmamış.)
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                Availability.NO_DEVICE_LOCK
            else ->
                Availability.UNAVAILABLE
        }
    }

    /** Kullanıcıya gösterilecek açıklama; hazırsa null. */
    fun unavailableMessageRes(availability: Availability): Int? = when (availability) {
        Availability.AVAILABLE -> null
        Availability.NO_DEVICE_LOCK -> R.string.biometric_no_device_lock_message
        Availability.UNAVAILABLE -> R.string.biometric_unavailable_message
    }

    /**
     * Kullanıcıyı ekran kilidi kurulum ekranına götürür — NO_DEVICE_LOCK mesajının yanındaki
     * eylem butonu buraya bağlanır.
     *
     * "Ayarlardan bir kilit tanımlayın" demek yetmiyordu: kullanıcı uygulamadan çıkıp doğru
     * ekranı kendisi bulmak zorunda kalıyor ve akış orada bitiyordu. API 30+ doğrudan
     * "Biyometri ve ekran kilidi" kurulumunu açar; istenen kapsam ALLOWED_AUTHENTICATORS ile
     * BİREBİR aynı olmalı, aksi halde sistem farklı bir kilit türü kurdurup kontrolümüz yine
     * NO_DEVICE_LOCK dönerdi. Eski sürümlerde en yakın hedef genel Güvenlik ayarlarıdır.
     */
    fun openDeviceLockSettings(context: Context) {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, ALLOWED_AUTHENTICATORS)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        // Bazı OEM ROM'larında bu action'lar yok; kullanıcıyı hiçbir yere götürememektense
        // genel Ayarlar'a düş. Üçü de açılamazsa sessiz kal — diyalogdaki metin hâlâ ne
        // yapılacağını anlatıyor.
        for (intent in listOf(primary, Intent(Settings.ACTION_SETTINGS))) {
            try {
                context.startActivity(intent)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                AppLog.info("Ayarlar açılamadı (${intent.action}) — sıradaki hedef deneniyor")
            }
        }
    }

    // Holder-of-key (Y-4): user key (V5) time-bound olduğundan TEK BiometricPrompt (CryptoObject YOK)
    // yeterli; başarıdan sonra AUTH_VALIDITY_SECONDS penceresinde hem ticket decrypt hem holder-of-key
    // sign yapılır. iOS tek-LAContext (decrypt+sign) akışının Android eşi. CryptoObject KULLANMA —
    // o auth-per-use semantiği getirir ve ikinci işlem için ikinci prompt gerektirirdi.
    fun authenticateForKeyUse(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
        onError: (errorCode: Int, systemMessage: String) -> Unit
    ) {
        activity.runOnUiThread {
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                // Anahtar (V6) biyometri VEYA cihaz kilidi kabul ediyor → prompt AYNI kümeyi
                // kullanmalı, yoksa biyometrisi olmayan kullanıcı anahtarı açamaz.
                // DEVICE_CREDENTIAL varken setNegativeButtonText ÇAĞRILAMAZ (build() exception atar);
                // sistem "PIN kullan" seçeneğini kendisi koyar, iptal geri tuşuyla yapılır.
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.biometric_title))
                    .setSubtitle(activity.getString(R.string.biometric_subtitle_decrypt))
                    .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            // İptal/zaman aşımı (kod 3, 5, 10, 13) hata DEĞİL → onCancel; bkz. [classify].
                            if (classify(errorCode) == ErrorClass.CANCELLED) onCancel()
                            else onError(errorCode, errString.toString())
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // System handles UI
                        }
                    })

                // CryptoObject YOK — time-bound anahtarda kullanıcı doğrulaması pencereyi açar.
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                onError(ERROR_INIT_FAILED, "Biometric Init Failed: ${e.message}")
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
                // Bkz. authenticateForKeyUse: anahtarla aynı authenticator kümesi + DEVICE_CREDENTIAL
                // varken negatif buton metni verilemez.
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.biometric_title))
                    .setSubtitle(activity.getString(R.string.biometric_subtitle_auth))
                    .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
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
