package com.verifyblind.mobile.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.verifyblind.mobile.util.BiometricHelper
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * Keystore'da kullanıcı anahtarı YOK (silinmiş ya da geçersiz kılınmış).
 *
 * Neden ayrı tip: çağıranın "tekrar denenebilir hata" ile "cihazdaki kart verisi kalıcı olarak
 * kullanılamaz"ı ayırt etmesi gerekiyor ve düz `IllegalStateException` bunu söylemiyordu — mesaj
 * metnine bakmak da kırılgan olurdu. `IllegalStateException`'ı genişletir, yani eski davranışa
 * bağlı çağıranlar etkilenmez.
 */
class UserKeyMissingException(message: String) : IllegalStateException(message)

/**
 * Cihazdaki kripto materyali KALICI olarak kullanılamaz mı — yani tekrar denemek çare olmaz mı?
 *
 * ## Neden var
 * Giriş akışı biyometrik/kripto hatalarının TAMAMINI "kart verisi okunamıyor → SİL" diyaloğuna
 * düşürüyordu (`LoginKeystoreError`). Parmağını üç kez yanlış okutup deneme kilidine takılan
 * kullanıcıya kimliğini silmesi öneriliyordu: mesaj yanlış ("veri okunamıyor" değil, "çok deneme"),
 * eylem geri alınamaz. Kayıt akışı aynı hatayı zaten yıkıcı olmayan bir mesajla kapatıyordu
 * (parite denetimi 2026-09-03, K-2).
 *
 * iOS'ta karşılığı `LoginViewModel.isUnrecoverableKeyMaterial`: orada da `authCancelled`/`authFailed`
 * BİLEREK dışlanır — "kullanıcı/donanım kaynaklı, tekrar denenebilir".
 *
 * ## Kural
 * Yalnız bilinen-kötü tipler `true` döner; geri kalan HER ŞEY `false` (güvenli taraf: şüphede
 * kullanıcının kimliğini silmeyi önerme). Sarmalanmış olabileceği için sebep zinciri de taranır
 * (`ServerErrorMessages.isTransportFailure` ile aynı desen).
 */
object KeyMaterialError {

    /** Sebep zinciri tavanı — döngüsel zincire karşı (isTransportFailure ile aynı sınır). */
    private const val MAX_CAUSE_DEPTH = 5

    fun isUnrecoverable(throwable: Throwable?): Boolean {
        // Biyometrik prompt hatası HİÇBİR koşulda "kart verisi bozuldu" demek değildir: deneme
        // kilidi, meşgul sensör, kurulamayan prompt — üçü de tekrar denenebilir. Zincire hiç
        // bakılmaz, çünkü bu tip bir anahtar arızası TAŞIMAZ.
        if (throwable is BiometricHelper.BiometricAuthException) return false

        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (isUnrecoverableType(current)) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun isUnrecoverableType(e: Throwable): Boolean = when (e) {
        // Anahtar Keystore'da yok (bkz. CryptoUtils.getCipherForDecrypt/getSignatureForSign).
        is UserKeyMissingException -> true
        // Anahtar var ama çıkarılamıyor / yeni biyometri kaydı anahtarı geçersiz kıldı.
        is UnrecoverableKeyException, is KeyPermanentlyInvalidatedException -> true
        // Ticket bu anahtarla çözülemedi (AEADBadTagException dahil) ya da Keystore doFinal'da
        // anahtarın gittiğini bildirdi — saklı veri bu cihazda bir daha açılamaz.
        is BadPaddingException, is IllegalBlockSizeException -> true
        else -> false
    }
}
