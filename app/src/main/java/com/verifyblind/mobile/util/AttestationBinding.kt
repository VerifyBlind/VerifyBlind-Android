package com.verifyblind.mobile.util

import java.security.MessageDigest
import java.util.Base64

/**
 * Cihaz attestation'ını isteğin hassas payload'ına (card_id) kriptografik olarak bağlar.
 *
 * Play Integrity token'ı "gerçek cihaz + gerçek app" kanıtlar ama istek gövdesini imzalamaz.
 * TLS pinning kaldırıldığından (Cloudflare/OWASP kararı) araya giren biri gövdedeki card_id'yi
 * değiştirebilir. requestHash'e card_id'yi katınca token yalnız bu tam card_id için geçerli olur.
 *
 * Sözleşme — sunucu ve iOS istemcisiyle BİREBİR aynı:
 *   bind        = SHA256( UTF8( fresh + "\n" + cardId ) )
 *   requestHash = Base64(bind)          → Play Integrity token'ına gömülür
 *
 * java.util.Base64 kullanılır (API 26+, minSdk=26): standart Base64 + padding, satır kaydırma YOK
 * → C# Convert.ToBase64String ile birebir eşleşir. android.util.Base64 tercih EDİLMEDİ; JVM birim
 * testinde Robolectric gerektirirdi ve golden vector'ün Robolectric'siz koşması önemli.
 *
 * Golden vector: AttestationBindingTest (Android) ↔ AttestationBindingTests.cs (sunucu).
 * Ayraç veya kodlama değiştirilirse üç tarafta birden değişmeli, aksi halde block-card RED alır.
 */
object AttestationBinding {

    private const val SEPARATOR = "\n"

    /** requestHash = Base64( SHA256( UTF8( fresh + "\n" + cardId ) ) ) */
    fun requestHash(fresh: String, cardId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((fresh + SEPARATOR + cardId).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}
