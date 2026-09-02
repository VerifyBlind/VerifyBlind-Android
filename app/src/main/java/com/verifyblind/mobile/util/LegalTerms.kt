package com.verifyblind.mobile.util

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * Hukuki metin demeti (Kullanım Şartları + Veri İşleme Sözleşmesi + Aydınlatma Metni) kabulü.
 *
 * Zero-knowledge mimaride kullanıcının sunucuda hesabı YOKTUR; `consent_records` bilinçli olarak
 * user_id/person_id/card_id tutmaz. Bu yüzden "kullanıcı X kabul etti" kaydı sunucuya yazılamaz ve
 * kabulün kanıtı üç ayağa dayanır:
 *
 *   1. Uygulama kabul alınmadan kullanılamaz (SplashActivity → LegalTermsActivity kapısı).
 *   2. Kabul edilen sürüm + zaman damgası cihazda saklanır ve Ayarlar'da gösterilir.
 *   3. Yürürlükteki sürüm sunucudan yönetilir (/api/public/app-config → legal_terms_version):
 *      metinler değişip sürüm yükseltilince tüm cihazlar yeniden onay ister — app release beklemez.
 *
 * Sunucu sürümü YALNIZCA yükseltebilir: ayar boş, bozuk veya erişilemez olsa bile kapı gömülü
 * [BASELINE_VERSION] ile ayakta kalır (fail-open yok), ayrıca hatalı bir ayar uygulamayı kilitlemez.
 */
object LegalTerms {

    /**
     * Uygulamaya gömülü taban sürüm — landing-site'taki metinlerin "Versiyon" etiketiyle hizalı
     * tutulur. Metinler değiştiğinde asıl yükseltme sunucudan yapılır; buradaki değer yalnızca
     * sunucuya hiç ulaşılamadığında geçerli olan alt sınırdır.
     */
    const val BASELINE_VERSION = "1.0"

    private const val PREFS = "user_prefs"
    private const val KEY_ACCEPTED_VERSION = "legal_terms_accepted_version"
    private const val KEY_ACCEPTED_AT = "legal_terms_accepted_at"

    /** Yürürlükteki sürüm: sunucu değeri yalnızca daha yeniyse geçerlidir. */
    fun requiredVersion(serverVersion: String?, baseline: String = BASELINE_VERSION): String {
        val server = serverVersion?.trim().orEmpty()
        if (server.isEmpty()) return baseline
        // Ayrıştırılamayan değer kapıyı düşürmemeli.
        if (parse(server) == null) return baseline
        return if (compare(server, baseline) > 0) server else baseline
    }

    /** Kabul yoksa, bozuksa veya gerekliden eskiyse yeniden onay gerekir (fail-closed). */
    fun needsAcceptance(acceptedVersion: String?, requiredVersion: String): Boolean {
        val accepted = acceptedVersion?.trim().orEmpty()
        if (accepted.isEmpty()) return true
        if (parse(accepted) == null) return true
        return compare(accepted, requiredVersion) < 0
    }

    // ── Cihaz kaydı ───────────────────────────────────────────────────────────

    fun acceptedVersion(context: Context): String? =
        prefs(context).getString(KEY_ACCEPTED_VERSION, null)

    fun acceptedAt(context: Context): Long =
        prefs(context).getLong(KEY_ACCEPTED_AT, 0L)

    /** Kabulü kalıcılaştırır. Sürüm + zaman damgası birlikte yazılır; kanıt ikisidir. */
    fun recordAcceptance(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_ACCEPTED_VERSION, version)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Ayarlar ekranı için "1.0 · 27.07.2026" biçiminde özet; kabul yoksa null. */
    fun acceptanceSummary(context: Context): String? {
        val version = acceptedVersion(context)?.takeIf { it.isNotBlank() } ?: return null
        val at = acceptedAt(context)
        if (at <= 0L) return version
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
        return "$version · $date"
    }

    fun needsAcceptance(context: Context, serverVersion: String? = null): Boolean =
        needsAcceptance(acceptedVersion(context), requiredVersion(serverVersion))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Sürüm karşılaştırma ───────────────────────────────────────────────────

    /** "1.10" > "1.9" olacak şekilde parça parça sayısal karşılaştırma. */
    private fun compare(a: String, b: String): Int {
        val left = parse(a) ?: return -1
        val right = parse(b) ?: return 1
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /** Yalnızca nokta ile ayrılmış sayılar kabul edilir; aksi halde null (bozuk sürüm). */
    private fun parse(version: String): List<Int>? {
        val parts = version.trim().split(".")
        if (parts.isEmpty()) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }
}
