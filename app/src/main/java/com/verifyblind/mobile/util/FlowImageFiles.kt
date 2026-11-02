package com.verifyblind.mobile.util

import android.content.Context
import java.io.File

/**
 * Kart ekleme ve doğrulama akışlarının önbelleğe yazdığı yüz ve çip görüntüleri.
 *
 * Biyometrik rıza metni "canlı yüz görüntünüz eşleşmeden sonra silinir; çipinizdeki fotoğraf
 * yalnız sizin telefonunuzda şifreli olarak durur" der. Bu dosyalar o sözün dışında kalan
 * şifresiz kopyalardır: akış bitince ve süreç açılışında (yarıda kalmış akışın artığı) silinir.
 * iOS bu görüntüleri diske yazmaz, yalnız bellekte tutar.
 *
 * Yeni bir yüz ya da çip dosyası eklersen adını [NAMES]'e de ekle. Olay dizisi kareleri
 * (`event_*.jpg`, bkz. [EventCollector]) adla değil kalıpla yakalanır.
 */
object FlowImageFiles {

    private val NAMES = listOf(
        // Kart ekleme: çipin yüz fotoğrafı, ham DG2 ve 112×112 hizalı kırpımı
        "chip_temp.jpg", "dg2_stream.bin", "chip_aligned.png",
        // Kart ekleme: canlı yüz kareleri ve enclave'in onayladığı karenin kopyaları
        "selfie_best.png", "antispoof_crop.jpg", "antispoof_crop40.jpg",
        "approved_selfie.png", "approved_crop.jpg",
        // Doğrulama: canlı yüz kareleri ve kareleri taşıyan hareket kanıtı
        "login_selfie.png", "login_antispoof_crop.jpg", "login_move_proof.json",
    )

    /**
     * [keep] içindeki yollar silinmez: geri bildirim kutusunun teklif edeceği iki kare onları
     * kutu kapanınca FeedbackFragment siler.
     */
    fun deleteAll(context: Context, keep: Collection<String?> = emptyList()) =
        deleteAll(context.cacheDir, keep)

    internal fun deleteAll(dir: File, keep: Collection<String?> = emptyList()) {
        val kept = keep.filterNotNull().map { File(it).absolutePath }.toSet()
        val eventFrames = dir.listFiles { f -> f.name.startsWith("event_") && f.name.endsWith(".jpg") }
            ?.toList() ?: emptyList()
        (NAMES.map { File(dir, it) } + eventFrames)
            .filter { it.absolutePath !in kept }
            .forEach { runCatching { it.delete() } }
    }
}
