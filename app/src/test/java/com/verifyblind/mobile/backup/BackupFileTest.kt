package com.verifyblind.mobile.backup

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `.vfbackup` zarf/format testleri — saf JVM (Gson + java.util.Base64, Android framework yok).
 */
class BackupFileTest {

    private fun sampleRecords() = listOf(
        BackupRecord(
            nonce = "nonce-1", personId = "person-A", cardId = "card-1", partnerId = "partner-x",
            title = "Acme'ye kimlik kanıtlandı", description = "Giriş doğrulaması",
            actionType = 0, status = 1, timestamp = 1_690_000_000_000, transactionId = "tx-1",
            deviceName = "Pixel 7"
        ),
        BackupRecord(
            nonce = "nonce-2", personId = "person-B", cardId = "card-2", partnerId = null,
            title = "Kart eklendi", description = "", actionType = 2, status = 1,
            timestamp = 1_690_000_100_000, transactionId = null, deviceName = ""
        )
    )

    // ── Şifresiz ──

    @Test
    fun writeUnencrypted_thenRead_roundTrips() {
        val json = BackupFile.write(sampleRecords(), password = null)
        val back = BackupFile.read(json, password = null)
        assertEquals(sampleRecords(), back)
    }

    @Test
    fun writeUnencrypted_jsonExposesRecordsAndNullEncryption() {
        val json = BackupFile.write(sampleRecords(), password = null)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue("encryption null olmalı", obj.get("encryption").isJsonNull)
        assertTrue("records dizisi bulunmalı", obj.has("records"))
        assertEquals(2, obj.getAsJsonArray("records").size())
        assertEquals(BackupFile.SCHEMA_VERSION, obj.get("schemaVersion").asInt)
    }

    @Test
    fun inspect_plaintext_reportsCountWithoutPassword() {
        val json = BackupFile.write(sampleRecords(), password = null)
        val info = BackupFile.inspect(json)
        assertFalse(info.encrypted)
        assertEquals(2, info.recordCount)
        assertEquals(BackupFile.SCHEMA_VERSION, info.schemaVersion)
    }

    // ── Şifreli ──

    @Test
    fun writeEncrypted_thenReadWithPassword_roundTrips() {
        val json = BackupFile.write(sampleRecords(), password = "correct horse battery")
        val back = BackupFile.read(json, password = "correct horse battery")
        assertEquals(sampleRecords(), back)
    }

    @Test
    fun writeEncrypted_jsonHidesRecordContent() {
        val json = BackupFile.write(sampleRecords(), password = "s3cret-passphrase")
        // Düz metin kayıt içeriği (nonce, personId, başlık) JSON'da GÖRÜNMEMELİ — payload şifreli.
        assertFalse("nonce sızmamalı", json.contains("nonce-1"))
        assertFalse("personId sızmamalı", json.contains("person-A"))
        assertFalse("başlık sızmamalı", json.contains("Acme"))
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("payload"))
        assertNull("records şifreli dosyada olmamalı", obj.get("records"))
    }

    @Test
    fun writeEncrypted_jsonHasKdfMetadata() {
        val json = BackupFile.write(sampleRecords(), password = "pw")
        val enc = JsonParser.parseString(json).asJsonObject.getAsJsonObject("encryption")
        assertEquals("PBKDF2-HMAC-SHA256", enc.get("kdf").asString)
        assertEquals("AES-256-GCM", enc.get("cipher").asString)
        assertEquals(BackupFile.ITERATIONS, enc.get("iterations").asInt)
        assertTrue("salt bulunmalı", enc.has("salt"))
        assertTrue("iv bulunmalı", enc.has("iv"))
    }

    @Test
    fun inspect_encrypted_reportsEncryptedAndNullCount() {
        val json = BackupFile.write(sampleRecords(), password = "pw")
        val info = BackupFile.inspect(json)
        assertTrue(info.encrypted)
        assertNull("şifreli dosyada sayı paroladan önce bilinemez", info.recordCount)
    }

    @Test
    fun readEncrypted_wrongPassword_throwsBackupPasswordException() {
        val json = BackupFile.write(sampleRecords(), password = "right")
        try {
            BackupFile.read(json, password = "wrong")
            fail("Yanlış parola BackupPasswordException fırlatmalıydı")
        } catch (e: BackupPasswordException) {
            // beklenen
        }
    }
}
