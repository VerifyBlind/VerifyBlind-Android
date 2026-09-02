package com.verifyblind.mobile.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * `.vfbackup` dosyasının serileştirilmesi/ayrıştırılması — manuel Yedekle/Geri Yükle modelinin
 * format katmanı. Zarf sözleşmesi Android ve iOS arasında ortaktır.
 *
 * İki zarf biçimi:
 *  - **Şifresiz:** `encryption` = JSON null, `records` = düz dizi.
 *  - **Şifreli:** `encryption` = {cipher,kdf,iterations,salt,iv}, `payload` = base64(ciphertext‖tag);
 *    `records` HİÇ yazılmaz (içerik sızmaz).
 *
 * İçteki düz metin, iki biçimde de AYNI `records` dizisidir. Base64 = standart alfabe, dolgulu
 * (iOS `Data.base64EncodedString()` ile aynı). `java.util.Base64` minSdk 26'da yerleşik.
 */
object BackupFile {

    const val SCHEMA_VERSION = 1
    const val ITERATIONS = 600_000
    private const val CIPHER = "AES-256-GCM"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val SALT_BYTES = 16

    private val gson = Gson()
    // Kök zarfı yazarken `encryption: null` (şifresiz dosya) AÇIKÇA çıksın diye serializeNulls şart —
    // varsayılan Gson explicit JsonNull üyeleri atlar. İç kayıt ağacı yine sade `gson` ile üretilir
    // (null partnerId/transactionId omit edilir).
    private val gsonNulls = GsonBuilder().serializeNulls().create()
    private val recordListType = object : TypeToken<List<BackupRecord>>() {}.type
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** Yeni yedek üretir. `password == null` → şifresiz dosya. */
    fun write(records: List<BackupRecord>, password: String?): String {
        val root = JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            addProperty("app", "VerifyBlind")
            addProperty("createdAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
            addProperty("fileId", UUID.randomUUID().toString())
        }

        if (password == null) {
            root.add("encryption", JsonNull.INSTANCE)
            root.add("records", gson.toJsonTree(records, recordListType))
        } else {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val key = BackupCrypto.deriveKey(password, salt, ITERATIONS)
            val innerPlain = gson.toJson(records, recordListType).toByteArray(StandardCharsets.UTF_8)
            val (iv, ct) = BackupCrypto.encrypt(innerPlain, key)

            root.add("encryption", JsonObject().apply {
                addProperty("cipher", CIPHER)
                addProperty("kdf", KDF)
                addProperty("iterations", ITERATIONS)
                addProperty("salt", b64.encodeToString(salt))
                addProperty("iv", b64.encodeToString(iv))
            })
            root.addProperty("payload", b64.encodeToString(ct))
        }
        return gsonNulls.toJson(root)
    }

    /**
     * Dosyayı ayrıştırıp kayıtları döner. Şifreli dosyada `password` gerekir; yanlış/eksik parola
     * `BackupPasswordException` fırlatır. Tekilleştirme çağıranın işidir (bkz. geri-yükleme akışı).
     */
    fun read(json: String, password: String?): List<BackupRecord> {
        val root = JsonParser.parseString(json).asJsonObject
        val encEl = root.get("encryption")

        if (encEl == null || encEl.isJsonNull) {
            return gson.fromJson(root.getAsJsonArray("records"), recordListType)
        }

        if (password == null) throw BackupPasswordException("Bu yedek şifreli — parola gerekli")
        val enc = encEl.asJsonObject
        val salt = b64d.decode(enc.get("salt").asString)
        val iv = b64d.decode(enc.get("iv").asString)
        val iterations = enc.get("iterations").asInt
        val key = BackupCrypto.deriveKey(password, salt, iterations)
        val ct = b64d.decode(root.get("payload").asString)

        val plain = try {
            BackupCrypto.decrypt(iv, ct, key)
        } catch (e: AEADBadTagException) {
            throw BackupPasswordException()
        }
        return gson.fromJson(String(plain, StandardCharsets.UTF_8), recordListType)
    }

    /**
     * Dosyayı DB'ye eklemeden meta bilgisini okur (liste/onay ekranı). Şifreli dosyada işlem sayısı
     * paroladan önce bilinemez → `recordCount = null`.
     */
    fun inspect(json: String): BackupInfo {
        val root = JsonParser.parseString(json).asJsonObject
        val encEl = root.get("encryption")
        val encrypted = encEl != null && !encEl.isJsonNull
        return BackupInfo(
            schemaVersion = root.get("schemaVersion").asInt,
            fileId = root.get("fileId").asString,
            createdAt = root.get("createdAt").asString,
            encrypted = encrypted,
            recordCount = if (encrypted) null else root.getAsJsonArray("records").size()
        )
    }
}
