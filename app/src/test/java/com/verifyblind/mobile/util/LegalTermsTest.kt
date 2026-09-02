package com.verifyblind.mobile.util

import org.junit.Assert.*
import org.junit.Test

/**
 * LegalTerms birim testleri.
 *
 * Kullanıcı tarafında hukuki metin (Kullanım Şartları + DPA + Aydınlatma) kabulünün kanıtı
 * CİHAZDA tutulur — zero-knowledge mimaride kullanıcı hesabı yoktur, sunucuda kişiye bağlı
 * kabul kaydı tutulamaz. Bu yüzden buradaki karar mantığı hukuki kapının tamamıdır:
 *
 *   - Gerekli sürüm = max(gömülü taban, sunucunun bildirdiği sürüm). Sunucu YALNIZCA yükseltir;
 *     ayar boş/bozuk/erişilemez olsa bile kapı taban sürümle ayakta kalır (fail-open YOK).
 *   - Kabul yoksa veya kabul edilen sürüm gerekliden eskiyse yeniden onay istenir.
 */
class LegalTermsTest {

    // ── Gerekli sürüm: sunucu yalnızca yükseltir ──────────────────────────────

    @Test
    fun requiredVersion_serverNewer_usesServer() {
        assertEquals("2.0", LegalTerms.requiredVersion(serverVersion = "2.0", baseline = "1.0"))
    }

    @Test
    fun requiredVersion_serverOlder_keepsBaseline() {
        // Sunucu yanlışlıkla geri alınırsa kabul kapısı geriye düşmemeli.
        assertEquals("1.5", LegalTerms.requiredVersion(serverVersion = "1.0", baseline = "1.5"))
    }

    @Test
    fun requiredVersion_serverBlankOrNull_keepsBaseline() {
        assertEquals("1.0", LegalTerms.requiredVersion(serverVersion = "", baseline = "1.0"))
        assertEquals("1.0", LegalTerms.requiredVersion(serverVersion = null, baseline = "1.0"))
        assertEquals("1.0", LegalTerms.requiredVersion(serverVersion = "   ", baseline = "1.0"))
    }

    @Test
    fun requiredVersion_serverGarbage_keepsBaseline() {
        // Bozuk değer kapıyı düşürmemeli — ayrıştırılamayan sürüm yok sayılır.
        assertEquals("1.0", LegalTerms.requiredVersion(serverVersion = "sürüm-yok", baseline = "1.0"))
    }

    // ── Kabul gerekli mi ──────────────────────────────────────────────────────

    @Test
    fun needsAcceptance_neverAccepted_returnsTrue() {
        assertTrue(LegalTerms.needsAcceptance(acceptedVersion = null, requiredVersion = "1.0"))
        assertTrue(LegalTerms.needsAcceptance(acceptedVersion = "", requiredVersion = "1.0"))
    }

    @Test
    fun needsAcceptance_acceptedCurrent_returnsFalse() {
        assertFalse(LegalTerms.needsAcceptance(acceptedVersion = "1.0", requiredVersion = "1.0"))
    }

    @Test
    fun needsAcceptance_acceptedOlder_returnsTrue() {
        // Metinler güncellendi → kullanıcı yeniden onaylamalı (ToS §7).
        assertTrue(LegalTerms.needsAcceptance(acceptedVersion = "1.0", requiredVersion = "1.1"))
    }

    @Test
    fun needsAcceptance_acceptedNewer_returnsFalse() {
        // Sunucu ayarı geri alınmış olabilir; kullanıcıyı boşuna tekrar rahatsız etme.
        assertFalse(LegalTerms.needsAcceptance(acceptedVersion = "2.0", requiredVersion = "1.0"))
    }

    @Test
    fun needsAcceptance_acceptedGarbage_returnsTrue() {
        // Bozuk/kurcalanmış kayıt kanıt sayılmaz → yeniden onay iste (fail-closed).
        assertTrue(LegalTerms.needsAcceptance(acceptedVersion = "bozuk", requiredVersion = "1.0"))
    }

    @Test
    fun needsAcceptance_multiSegmentVersions_comparesNumerically() {
        assertTrue(LegalTerms.needsAcceptance(acceptedVersion = "1.9", requiredVersion = "1.10"))
        assertFalse(LegalTerms.needsAcceptance(acceptedVersion = "1.10", requiredVersion = "1.9"))
    }
}
