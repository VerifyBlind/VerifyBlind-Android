package com.verifyblind.mobile.crypto

import androidx.biometric.BiometricPrompt
import com.verifyblind.mobile.util.BiometricHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * "Kart verisini sil" teklifinin kapısı.
 *
 * Regresyon zemini (parite denetimi 2026-09-03, K-2): giriş akışı biyometrik/kripto hatalarının
 * TAMAMINI `LoginKeystoreError` diyaloğuna düşürüyordu — parmağını üç kez yanlış okutup deneme
 * kilidine takılan kullanıcıya "kayıtlı kart verisi okunamıyor, silin" deniyordu. Aşağıdaki kural
 * bozulursa aynı yıkıcı teklif geri gelir.
 *
 * Kural: yalnız bilinen-kötü tipler true; geri kalan HER ŞEY false (şüphede kullanıcının kimliğini
 * silmeyi önerme). iOS `LoginViewModel.isUnrecoverableKeyMaterial` ile aynı ayrım.
 *
 * NOT: `KeyPermanentlyInvalidatedException` burada ÖRNEKLENMİYOR — android.jar stub'ının yapıcısı
 * JVM testinde "Stub!" fırlatır. Üretim kodundaki tip kontrolü yerinde; kapsamı instrumentation
 * tarafına bırakıldı.
 */
class KeyMaterialErrorTest {

    // ── Kurtarılamaz: silme teklifi DOĞRU ──────────────────────────────────────

    @Test
    fun userKeyMissing_isUnrecoverable() {
        assertTrue(KeyMaterialError.isUnrecoverable(UserKeyMissingException("anahtar yok")))
    }

    @Test
    fun unrecoverableKey_isUnrecoverable() {
        assertTrue(KeyMaterialError.isUnrecoverable(UnrecoverableKeyException("çıkarılamadı")))
    }

    @Test
    fun decryptFailures_areUnrecoverable() {
        // Ticket bu anahtarla açılamıyor → saklı veri bu cihazda bir daha çözülemez.
        assertTrue(KeyMaterialError.isUnrecoverable(BadPaddingException("dolgu")))
        assertTrue(KeyMaterialError.isUnrecoverable(AEADBadTagException("gcm tag")))
        assertTrue(KeyMaterialError.isUnrecoverable(IllegalBlockSizeException("blok")))
    }

    @Test
    fun wrappedCause_isDetected() {
        val wrapped = RuntimeException("sarmalayıcı", IllegalStateException("ara", BadPaddingException("dolgu")))
        assertTrue(KeyMaterialError.isUnrecoverable(wrapped))
    }

    // ── Kurtarılabilir: silme teklifi YANLIŞ ───────────────────────────────────

    /** K-2'nin ta kendisi: biyometrik hata anahtarın bozulduğu anlamına GELMEZ. */
    @Test
    fun biometricAuthError_isNeverUnrecoverable() {
        val codes = listOf(
            BiometricPrompt.ERROR_LOCKOUT,            // çok deneme (30 sn)
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,  // cihaz kilidiyle açılır
            BiometricPrompt.ERROR_HW_UNAVAILABLE,     // sensör geçici meşgul
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,  // okuma işlenemedi
            BiometricHelper.ERROR_INIT_FAILED,        // prompt hiç kurulamadı
        )
        for (code in codes) {
            val e = BiometricHelper.BiometricAuthException(code, "sistem metni")
            assertFalse("kod $code silme teklifine düşmemeli", KeyMaterialError.isUnrecoverable(e))
        }
    }

    @Test
    fun unrelatedErrors_areRecoverable() {
        assertFalse(KeyMaterialError.isUnrecoverable(IOException("ağ")))
        // Düz IllegalStateException YETMEZ — yalnız UserKeyMissingException sayılır.
        assertFalse(KeyMaterialError.isUnrecoverable(IllegalStateException("başka bir şey")))
        assertFalse(KeyMaterialError.isUnrecoverable(RuntimeException("bilinmeyen")))
        assertFalse(KeyMaterialError.isUnrecoverable(null))
    }

    /** Biyometrik hata bir sebep zinciri taşısa bile kapı kapalı kalır (tip başta eleniyor). */
    @Test
    fun biometricError_withUnrecoverableCause_staysRecoverable() {
        val e = BiometricHelper.BiometricAuthException(BiometricPrompt.ERROR_LOCKOUT, "sistem metni")
        e.initCause(BadPaddingException("dolgu"))
        assertFalse(KeyMaterialError.isUnrecoverable(e))
    }

    /** Sebep zinciri tavanı: derin zincirde tarama durur, sonsuz döngü/aşırı iş yok. */
    @Test
    fun causeChainDepth_isCapped() {
        var e: Throwable = BadPaddingException("en dipte")
        repeat(6) { e = RuntimeException("katman", e) }
        assertFalse(KeyMaterialError.isUnrecoverable(e))
    }
}
