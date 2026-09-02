package com.verifyblind.mobile.backup

/**
 * `.vfbackup` dosyasındaki tek bir işlem kaydı — kendi içinde bağımsız (aynı dosyada birden çok
 * kişinin birden çok kartına ait kayıtlar yan yana durabilir).
 *
 * `personId` YALNIZ görüntü filtresi içindir (kripto rolü yok): geçmiş ekranında cüzdandaki
 * kartların sahiplerine ait kayıtlar gösterilir. `title`/`description`/`deviceName` burada düz
 * metindir (yerel DB'den çözülmüş); dosya şifreliyse tüm dosyayla birlikte korunur.
 */
data class BackupRecord(
    val nonce: String,
    val personId: String,
    val cardId: String,
    val partnerId: String?,
    val title: String,
    val description: String,
    val actionType: Int,
    val status: Int,
    val timestamp: Long,
    val transactionId: String?,
    val deviceName: String
)

/** `BackupFile.inspect` çıktısı — dosyayı DB'ye eklemeden önce liste/onay ekranında gösterilir. */
data class BackupInfo(
    val schemaVersion: Int,
    val fileId: String,
    val createdAt: String,
    val encrypted: Boolean,
    /** Şifresiz dosyada işlem sayısı; şifreli dosyada paroladan önce bilinemez → null. */
    val recordCount: Int?
)

/** Şifreli `.vfbackup` yanlış parolayla açılmaya çalışıldığında fırlatılır. */
class BackupPasswordException(message: String = "Yanlış parola veya bozuk dosya") : Exception(message)
