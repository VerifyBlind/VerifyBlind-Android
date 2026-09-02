package com.verifyblind.mobile.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // V3: PKCS1 → OAEP migration. V4: Fixed MGF1 hash for Android Keystore TEE compatibility.
    private const val HISTORY_KEY_ALIAS = "VerifyBlind_HistoryKey_V3"
    // V6: biyometri VEYA cihaz kilidi (passcode/PIN/desen) kabul edilir — iOS `.userPresence` paritesi.
    // V5 yalnız AUTH_BIOMETRIC_STRONG istiyordu; kayıtlı biyometrisi olmayan cihazda anahtar HİÇ
    // üretilemiyor ve kullanıcı uygulamayı kullanamıyordu (Sentry'de 81 InvalidAlgorithmParameterException).
    //
    // Katılığın karşılığı yoktu: anahtar pozitif geçerlilik süresiyle (AUTH_VALIDITY_SECONDS) üretildiği
    // için setInvalidatedByBiometricEnrollment UYGULANMAZ → telefonu alıp kendi parmağını kaydeden
    // saldırgan zaten açabiliyordu. Yani "biyometri şart" kısıtı o senaryoda passcode'a denkti.
    // Kimliğe bürünmenin asıl bariyeri yerel anahtar değil, enclave'deki canlılık + DG2 yüz eşleşmesi.
    //
    // Anahtar spesifikasyonu değiştiği için V5 anahtarları UYUMSUZ → yeni alias; kullanıcılar kartı
    // yeniden ekler (V4→V5 geçişindeki gibi).
    private const val USER_KEY_ALIAS = "VerifyBlind_UserKey_V6"
    private const val LEGACY_USER_KEY_ALIAS_V5 = "VerifyBlind_UserKey_V5"
    private const val LEGACY_USER_KEY_ALIAS_V4 = "VerifyBlind_UserKey_V4"
    // Tek biyometrik auth sonrası anahtarın kullanılabilir kaldığı pencere (sn). decrypt+sign aynı
    // onSuccess callback'inde SENKRON, arka arkaya (toplam birkaç ms) yapılır; bu pencere yalnızca nadir
    // gecikmelere (soğuk KeyStore ilk erişimi, GC, anlık meşgul main thread) karşı küçük bir tampon —
    // güvenliği sıkı tutmak için minimumda. iOS LAContext yeniden-kullanımının eşi.
    private const val AUTH_VALIDITY_SECONDS = 5
    private const val TRANSFORMATION_RSA = "RSA/ECB/OAEPPadding"
    private const val TRANSFORMATION_AES = "AES/GCM/NoPadding"
    private const val TAG = "CryptoUtils"

    // OAEP-SHA256 with MGF1-SHA256 — matches .NET RSAEncryptionPadding.OaepSHA256
    // Use for: rsaEncrypt() targeting Enclave public key (server-side .NET)
    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    // OAEP-SHA1 with MGF1-SHA1 — universally supported by Android Keystore TEE on all API levels.
    // Android Keystore hardware (TEE) does not support MGF1-SHA256 on many devices (pre-API 30).
    // OAEP-SHA1 is still secure against Bleichenbacher attacks on RSA-2048.
    // Use for: all Keystore-backed key operations (getCipherForDecrypt, rsaDecryptHistory)
    // Corresponding encrypt side must use .NET RsaEncryptOaepSha1 or rsaEncryptForKeystore.
    private fun keystoreOaepSpec() = OAEPParameterSpec(
        "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
    )

    // --- RSA (Keystore) ---

    fun ensureHistoryKeyExists(): String {
        val existing = getHistoryPublicKeyStr()
        if (existing != null) return existing

        val kpg = java.security.KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            HISTORY_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).run {
            setDigests(KeyProperties.DIGEST_SHA1)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            setKeySize(2048)
            setUserAuthenticationRequired(false) // NO BIOMETRIC for History Key
            build()
        }
        kpg.initialize(parameterSpec)
        val kp = kpg.generateKeyPair()
        return base64Encode(kp.public.encoded)
    }

    private fun getHistoryPublicKeyStr(): String? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(HISTORY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        return entry?.certificate?.publicKey?.encoded?.let { base64Encode(it) }
    }

    // User Key (For Ticket - Biometric Required)
    fun ensureKeyExists(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(USER_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return base64Encode(entry.certificate.publicKey.encoded)

        // Create new
        val kpg = java.security.KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            USER_KEY_ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_SIGN
        ).run {
            setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            setKeySize(2048)
            setUserAuthenticationRequired(true)
            // Holder-of-key (Y-4): time-bound auth. TEK biyometrik prompt sonrası AUTH_VALIDITY_SECONDS
            // boyunca anahtar CryptoObject olmadan kullanılabilir → aynı promptla hem ticket decrypt hem
            // holder-of-key sign. auth-per-use (eski V4) olsaydı her işlem ayrı prompt isterdi.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Biyometri VEYA cihaz kilidi. DEVICE_CREDENTIAL olmadan kayıtlı biyometrisi
                // olmayan kullanıcı anahtarı hiç üretemiyordu. BiometricPrompt tarafı da aynı
                // authenticator kümesini kullanmak ZORUNDA (bkz. BiometricHelper) — aksi halde
                // anahtar üretilir ama açılamaz.
                setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            } else {
                // API < 30: pozitif geçerlilik süresi zaten "biyometri VEYA cihaz kilidi" semantiği
                // verir → yeni davranışla uyumlu, ek bir şey gerekmez.
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
            build()
        }
        kpg.initialize(parameterSpec)
        val kp = kpg.generateKeyPair()
        return base64Encode(kp.public.encoded)
    }

    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        keyStore.deleteEntry(USER_KEY_ALIAS)
        // Eski sürüm anahtarlarını da temizle (V5: biyometri-şart, V4: auth-per-use) — geçiş
        // artığı Keystore'da kalmasın.
        try { keyStore.deleteEntry(LEGACY_USER_KEY_ALIAS_V5) } catch (_: Exception) {}
        try { keyStore.deleteEntry(LEGACY_USER_KEY_ALIAS_V4) } catch (_: Exception) {}
    }

    // --- RSA Core ---

    // Software RSA encrypt with OAEP-SHA256/MGF1-SHA256.
    // Use for: encrypting data for the Enclave public key (.NET server).
    fun rsaEncrypt(plainText: String, pubKeyBase64: String): String {
        val pubKeyBytes = Base64.decode(pubKeyBase64.trim(), Base64.DEFAULT)
        val keySpec = java.security.spec.X509EncodedKeySpec(pubKeyBytes)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val pubKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
        cipher.init(Cipher.ENCRYPT_MODE, pubKey, oaepSpec())
        return Base64.encodeToString(cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    // Software RSA encrypt with OAEP-SHA1/MGF1-SHA1.
    // Use for: encrypting data whose corresponding private key is a Keystore-backed key
    // (User key, History key). Must match keystoreOaepSpec() used in getCipherForDecrypt
    // and rsaDecryptHistory. Corresponds to .NET CryptoUtils.RsaEncryptOaepSha1.
    fun rsaEncryptForKeystore(plainText: String, pubKeyBase64: String): String {
        val pubKeyBytes = Base64.decode(pubKeyBase64.trim(), Base64.DEFAULT)
        val keySpec = java.security.spec.X509EncodedKeySpec(pubKeyBytes)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val pubKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
        cipher.init(Cipher.ENCRYPT_MODE, pubKey, keystoreOaepSpec())
        return Base64.encodeToString(cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    fun rsaDecryptHistory(cipherTextBase64: String): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(HISTORY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw Exception("History Key not found")

        val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
        cipher.init(Cipher.DECRYPT_MODE, entry.privateKey, keystoreOaepSpec())

        val decryptedBytes = cipher.doFinal(Base64.decode(cipherTextBase64, Base64.DEFAULT))
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    // --- AES (Random Key for Local DB) ---

    // Returns (Blob, Key, IV) all Base64.
    // NOTE: Current HistoryRepository discards the returned IV, so we embed it in Blob!
    // Blob = IV (12 bytes) + CipherText
    fun aesEncrypt(plainText: String): Triple<String, String, String> {
        // android.util.Log.d(TAG, "aesEncrypt: Encrypting data with AES (random key)")
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey: SecretKey = keyGen.generateKey()

        val cipher = Cipher.getInstance(TRANSFORMATION_AES)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        val result = Triple(
            base64Encode(combined),
            base64Encode(secretKey.encoded),
            base64Encode(iv) // Returned for reference, but Blob has it too
        )
        // android.util.Log.d(TAG, "aesEncrypt: Encryption successful. Ciphertext length: ${result.first.length}")
        return result
    }

    // Valid for Local DB (Blob has embedded IV) and Ticket (Blob might have embedded IV?)
    // MainActivity.saveTicket logic relies on this.
    fun aesDecrypt(cipherTextBase64: String, keyBase64: String): String {
        // android.util.Log.d(TAG, "aesDecrypt: Decrypting data with AES (random key)")
        val combined = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // Extract IV (12 bytes for GCM)
        val iv = ByteArray(12)
        val ciphertext = ByteArray(combined.size - 12)
        System.arraycopy(combined, 0, iv, 0, 12)
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION_AES)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    // --- AES-GCM (Explicit Key for Cloud Sync) ---

    fun deriveKeyFromPersonId(personId: String): SecretKeySpec {
        return SecretKeySpec(sha256Bytes(personId), "AES")
    }

    fun aesGcmEncrypt(data: String, personId: String): Pair<String, String> {
        val key = deriveKeyFromPersonId(personId)
        val cipher = Cipher.getInstance(TRANSFORMATION_AES)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

        return Pair(base64Encode(ciphertext), base64Encode(iv))
    }

    fun aesGcmDecrypt(cipherTextBase64: String, ivBase64: String, personId: String): String {
        val key = deriveKeyFromPersonId(personId)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val cipherSchema = Cipher.getInstance(TRANSFORMATION_AES)
        val spec = GCMParameterSpec(128, iv)
        cipherSchema.init(Cipher.DECRYPT_MODE, key, spec)

        val decoded = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        val plaintext = cipherSchema.doFinal(decoded)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    // --- AES-GCM (HAM anahtar — DEK/KEK sarma) ---
    //
    // Yukarıdaki aesGcmEncrypt/aesGcmDecrypt anahtarı personId'den TÜRETİR (SHA256) ve v1 bulut
    // yedek formatı için AYNEN korunur. Aşağıdakiler ham 32 baytlık anahtar alır.
    //
    // Neden iki katman: yedek artık rastgele bir DEK ile şifrelenir; DEK ise personId'den türeyen
    // KEK ile sarılıp yedek dosyasında tutulur. Aynı DEK birden çok KEK ile sarılabildiği için
    // kimlik tabanı değişince (TCKN → PIN) yalnız yeni bir wrap eklenir; geçmiş ASLA yeniden
    // şifrelenmez. DEK rastgeledir → türetilemez → ham anahtar alan varyant şart.

    /**
     * personId'den KEK baytları. deriveKeyFromPersonId ile BİREBİR aynı (SHA256(personId)) —
     * v1→v2 geçişinde mevcut personId'ler wrap'leri açabilsin diye kasıtlı olarak aynı.
     */
    fun kekFromPersonId(personId: String): ByteArray = sha256Bytes(personId)

    /** Yeni rastgele DEK (32 bayt). Kimlikten bağımsız — asla türetilmez. */
    fun generateDek(): ByteArray {
        val dek = ByteArray(32)
        java.security.SecureRandom().nextBytes(dek)
        return dek
    }

    fun aesGcmEncryptRaw(data: String, keyBytes: ByteArray): Pair<String, String> {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION_AES)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

        return Pair(base64Encode(ciphertext), base64Encode(iv))
    }

    fun aesGcmDecryptRaw(cipherTextBase64: String, ivBase64: String, keyBytes: ByteArray): String {
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val cipher = Cipher.getInstance(TRANSFORMATION_AES)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

        val decoded = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        return String(cipher.doFinal(decoded), StandardCharsets.UTF_8)
    }

    // --- Hashing ---

    fun sha256(input: String): String {
        val bytes = sha256Bytes(input)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(input: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(StandardCharsets.UTF_8))
    }

    fun sha256Bytes(input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    // --- Biometric Decryption Helpers ---

    fun getCipherForDecrypt(): Cipher {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(USER_KEY_ALIAS, null) as java.security.PrivateKey
        val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, keystoreOaepSpec())
        return cipher
    }

    fun rsaDecryptWithCipher(cipher: Cipher, encryptedBase64: String): String {
        // Trim input and use DEFAULT for legacy recovery if needed, but clean it up
        val bytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(bytes)
        return String(decryptedBytes, StandardCharsets.UTF_8).trim()
    }

    // --- Holder-of-Key Signing (Security review Y-4) ---
    //
    // The USER_KEY (VerifyBlind_UserKey_V4) was created with PURPOSE_SIGN + SIGNATURE_PADDING_RSA_PSS +
    // DIGEST_SHA256, so it can already sign — no key migration needed. The login holder-of-key proof
    // signs "VBLOK1|{nonce}|{pk_hash}|{ts}" with this key; the Enclave verifies against the UserPubKey
    // embedded in the (MAC-authenticated) ticket. RSA-PSS/SHA-256/MGF1-SHA256/salt=32 is the AndroidKeyStore
    // default for "SHA256withRSA/PSS" and matches .NET RSASignaturePadding.Pss (CryptoUtils.VerifySignature).
    //
    // NOTE: USER_KEY (V5) is time-bound (AUTH_VALIDITY_SECONDS window), so after ONE BiometricPrompt
    // (no CryptoObject) both getCipherForDecrypt()+doFinal and getSignatureForSign()+sign succeed within
    // the window — single prompt. See BiometricHelper.authenticateForKeyUse / MainActivity.authenticateAndUseUserKey.

    fun getSignatureForSign(): java.security.Signature {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(USER_KEY_ALIAS, null) as java.security.PrivateKey
        val signature = java.security.Signature.getInstance("SHA256withRSA/PSS")
        signature.initSign(privateKey)
        return signature
    }

    fun signWithSignature(signature: java.security.Signature, message: String): String {
        signature.update(message.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
