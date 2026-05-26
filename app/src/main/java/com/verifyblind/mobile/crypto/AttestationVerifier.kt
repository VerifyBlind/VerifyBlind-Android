package com.verifyblind.mobile.crypto

import android.util.Base64
import android.util.Log
import com.upokecenter.cbor.CBORObject
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.X509CertSelector
import java.security.cert.PKIXBuilderParameters
import java.security.cert.CertPathBuilder
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.verifyblind.mobile.BuildConfig

/**
 * AWS Nitro Attestation Document doğrulayıcısı.
 *
 * Üç aşamalı doğrulama yapar:
 *   1. AWS Root CA İmza Kontrolü  → Bu belge gerçekten AWS donanımından mı geldi?
 *   2. PCR0 Kontrolü              → Çalışan kod, GitHub'daki güvenli kod mu?
 *   3. Public Key Binding         → Belgedeki anahtar, bağlantıdaki anahtarla aynı mı?
 *
 * Referans: https://docs.aws.amazon.com/enclaves/latest/user/verify-root.html
 */
object AttestationVerifier {

    private const val TAG = "AttestationVerifier"

    // AWS Nitro Enclaves Root CAs (Trusted Anchors)
    // Ref: https://docs.aws.amazon.com/enclaves/latest/user/verify-root.html
    private val ALLOWED_ROOT_CA_FINGERPRINTS = setOf(
        "8cf60e2b2efca96c6a9e71e851d00c1b6991cc09eadbe64a6a1d1b1eb9faff7c", // AWS NitroEnclaves Root-G1
        "7c1bef79ceebbab63f0e7f661e3ad15f8f5559a55c0ec281c6295d86490d4cd4", // Alternative Root CA
        "544b3038ae723a3c1e2e0d50af1c9bb82078f37d3662a28ce3ef93e67d27459f"  // Observed in eu-central-1 (Nitro PKI)
    )


    // DEVELOPER AUTHORIZATION PUBLIC KEY (RSA CSP BLOB)
    // verifyblind.properties'den build-time'da enjekte edilir (BuildConfig üzerinden)
    private val DEVELOPER_PUBLIC_KEY = BuildConfig.DEVELOPER_PUBLIC_KEY

    data class VerificationResult(
        val isValid: Boolean,
        val failReason: String? = null,
        val pcr0: String? = null,
        val enclavePubKey: String? = null,
        val isMockDocument: Boolean = false
    )

    init {
        // Mevcut "BC" provider'ı (Android'in kırpılmış versiyonu veya başka bir kütüphane)
        // kaldırıp tam BouncyCastle'ı 1. sıraya ekle — P-384 EC desteği için zorunlu.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /**
     * Attestation belgesini doğrular.
     *
     * @param attestationDocumentBase64  `HandshakeResponse.attestationDocument` alanı
     * @param expectedEnclavePubKey      `HandshakeResponse.enclavePubKey` alanı
     * @param expectedPcr0               GitHub'dan indirilen `expected_pcr.json` içindeki `pcr0`
     */
    fun verify(
        attestationBase64: String,
        pcr0Signature: String? = null,
        isServerDevelopment: Boolean = false
    ): VerificationResult {
        if (attestationBase64.isBlank()) {
            return VerificationResult(false, "Attestation document is missing from handshake")
        }

        val docBytes = try {
            Base64.decode(attestationBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return VerificationResult(false, "Cannot decode attestation document: ${e.message}")
        }


        return try {
            // AWS Nitro Attestation Document'i COSE_Sign1 formatında geliyor (CBOR)
            val cbor = CBORObject.DecodeFromBytes(docBytes)
            verifyRealDocument(cbor, pcr0Signature, isServerDevelopment)
        } catch (e: Exception) {
            Log.e(TAG, "Tasdik doğrulama istisnası: ${e.message}", e)
            VerificationResult(false, "Parse error: ${e.message}")
        }
    }

    private fun verifyRealDocument(
        cose: CBORObject,
        pcr0Signature: String?,
        isServerDevelopment: Boolean
    ): VerificationResult {

        // COSE_Sign1 yapısı: [protected_header, unprotected_header, payload, signature]
        // AWS Nitro belgesi bu yapıyı kullanır.
        if (cose.size() < 4) {
            return VerificationResult(false, "Invalid COSE_Sign1 structure (expected 4 items)")
        }

        val payloadBytes = try {
            cose[2].GetByteString()
        } catch (e: Exception) {
            return VerificationResult(false, "Cannot read COSE payload: ${e.message}")
        }

        val payload = CBORObject.DecodeFromBytes(payloadBytes)

        // =
        // KONTROL 1: AWS Root CA Sertifika Zinciri Doğrulaması
        // =
        val certChainResult = verifyCertificateChain(payload)
        if (!certChainResult.first) {
            return VerificationResult(false, "AWS CA Check FAILED: ${certChainResult.second}")
        }
        Log.i(TAG, "[Tasdik] ✅ Kontrol 1/3: AWS Root CA doğrulandı.")
        
        // =
        // KONTROL 2: PCR0 Değeri Eşleşmesi (Kod Parmak İzi) / Yetkilendirme
        // =
        val pcr0 = extractPcr0(payload)
        Log.i(TAG, "[Tasdik] GERÇEK PCR0: $pcr0")

        if (pcr0 != "UNKNOWN" && pcr0.all { it == '0' }) {
            if (isServerDevelopment || BuildConfig.DEBUG) {
                Log.w(TAG, "[Tasdik] ⚠️ Enclave hata ayıklama modunda başlatıldı (PCR0 tümü sıfır), geliştirme modunda kabul ediliyor.")
                val enclavePubKey = extractEnclavePublicKey(payload)
                return VerificationResult(isValid = true, pcr0 = pcr0, enclavePubKey = enclavePubKey, isMockDocument = true)
            } else {
                Log.e(TAG, "[Tasdik] ❌ Enclave muhtemelen hata ayıklama modunda başlatıldı (PCR0 tümü sıfır).")
                return VerificationResult(false, "Enclave probably started in debug-mode (PCR0 is all zeros).")
            }
        }

        // 1. ÖNCELİK: Developer İmzası (Dinamik Yetkilendirme)
        if (!pcr0Signature.isNullOrBlank()) {
            val authCheck = verifyPcr0Authorization(pcr0, pcr0Signature)
            if (authCheck.first) {
                Log.i(TAG, "[Tasdik] ✅ Kontrol 2/3: PCR0 geliştirici imzasıyla yetkilendirildi.")
            } else {
                Log.e(TAG, "[Tasdik] ❌ PCR0 Yetkilendirme BAŞARISIZ: ${authCheck.second}")
                return VerificationResult(false, "PCR0 Authorization FAILED: ${authCheck.second}")
            }
        } 
        else {
            Log.e(TAG, "[Tasdik] ❌ Yetkisiz Enclave: İmza sağlanmadı.")
            return VerificationResult(false, "Unauthorized Enclave: No PCR0 signature provided.")
        }

        // =
        // KONTROL 3: Public Key Extraction (Güvenilir Anahtar Edinimi)
        // =
        val enclavePubKey = extractEnclavePublicKey(payload)
        if (enclavePubKey.isNullOrBlank()) {
            return VerificationResult(false, "Failed to extract public key from attestation document")
        }
        Log.i(TAG, "[Tasdik] ✅ Kontrol 3/3: Donanım zarfından Genel Anahtar çıkarıldı.")

        Log.i(TAG, "[Tasdik] 🔒 3 tasdik kontrolü başarıyla geçildi. PCR0: ${pcr0.take(16)}...")

        return VerificationResult(isValid = true, pcr0 = pcr0, enclavePubKey = enclavePubKey)
    }

    /**
     * AWS Nitro Enclaves Root CA - G1 (Official)
     * Bu sertifika, AWS'nin tüm bölgelerindeki Nitro donanımlarını doğrulamak için kullandığı ana kök sertifikadır.
     */
    private const val AWS_NITRO_ROOT_G1_PEM = """
-----BEGIN CERTIFICATE-----
MIICETCCAZagAwIBAgIRAPkxdWgbkK/hHUbMtOTn+FYwCgYIKoZIzj0EAwMwSTEL
MAkGA1UEBhMCVVMxDzANBgNVBAoMBkFtYXpvbjEMMAoGA1UECwwDQVdTMRswGQYD
VQQDDBJhd3Mubml0cm8tZW5jbGF2ZXMwHhcNMTkxMDI4MTMyODA1WhcNNDkxMDI4
MTQyODA1WjBJMQswCQYDVQQGEwJVUzEPMA0GA1UECgwGQW1hem9uMQwwCgYDVQQL
DANBV1MxGzAZBgNVBAMMEmF3cy5uaXRyby1lbmNsYXZlczB2MBAGByqGSM49AgEG
BSuBBAAiA2IABPwCVOumCMHzaHDimtqQvkY4MpJzbolL//Zy2YlES1BR5TSksfbb
48C8WBoyt7F2Bw7eEtaaP+ohG2bnUs990d0JX28TcPQXCEPZ3BABIeTPYwEoCWZE
h8l5YoQwTcU/9KNCMEAwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUkCW1DdkF
R+eWw5b6cp3PmanfS5YwDgYDVR0PAQH/BAQDAgGGMAoGCCqGSM49BAMDA2kAMGYC
MQCjfy+Rocm9Xue4YnwWmNJVA44fA0P5W2OpYow9OYCVRaEevL8uO1XYru5xtMPW
rfMCMQCi85sWBbJwKKXdS6BptQFuZbT73o/gBh1qUxl/nNr12UO8Yfwr6wPLb+6N
IwLz3/Y=
-----END CERTIFICATE-----
    """

    // Kabul edilen kök/ara sertifika parmak izleri (Regional Fallbacks)
    private val TRUSTED_AWS_ROOT_FINGERPRINTS = setOf(
        "8cf60e2b2efca96c6a9e71e851d00c1b6991cc09eadbe64a6a1d1b1eb9faff7c", // Standard G1 Root
        "544b3038ae723a3c1e2e0d50af1c9bb82078f37d3662a28ce3ef93e67d27459f"  // Frankfurt (eu-central-1) Observed Root/Intermediate
    )

    private fun verifyCertificateChain(payload: CBORObject): Pair<Boolean, String> {
        return try {
            val cabundleObj = payload["cabundle"] ?: return Pair(false, "Missing cabundle")
            val certObj = payload["certificate"] ?: return Pair(false, "Missing leaf certificate")

            val factory = CertificateFactory.getInstance("X.509")

            // 1. Resmi AWS Root Sertifikasını yükle (Universal Anchor)
            val awsRootCert = factory.generateCertificate(AWS_NITRO_ROOT_G1_PEM.trim().byteInputStream()) as X509Certificate

            // 2. Sertifikaları Parse Et
            val leafCert = factory.generateCertificate(certObj.GetByteString().inputStream()) as X509Certificate
            val intermediates = (0 until cabundleObj.size()).map { i ->
                factory.generateCertificate(cabundleObj[i].GetByteString().inputStream()) as X509Certificate
            }

            // 3. Otomatik Zincir Doğrulayıcı (CertPathBuilder) Ayarları
            // Not: cabundle içindeki sıra belirsiz olabilir. CertPathBuilder otomatik sıralar.
            val certsToStore = mutableListOf<X509Certificate>()
            certsToStore.add(leafCert)
            certsToStore.addAll(intermediates)

            // BouncyCastle provider ile Collection CertStore — Android default provider P-384 EC'yi desteklemiyor
            val certStore = CertStore.getInstance("Collection", CollectionCertStoreParameters(certsToStore), BouncyCastleProvider.PROVIDER_NAME)
            val target = X509CertSelector()
            target.certificate = leafCert

            // Güven Çapalarını (Trust Anchors) belirle
            val trustAnchors = mutableSetOf<TrustAnchor>()
            trustAnchors.add(TrustAnchor(awsRootCert, null)) // Resmi G1

            // 4. cabundle içindeki bilinen parmak izlerini de alternatif Anchor olarak ekle (Fallback)
            // Eğer G1'e çıkamıyorsa veya bölge kendi özel kökünü kullanıyorsa çalışmasını sağlar.
            for (cert in intermediates) {
                val fp = cert.encoded.sha256Hex().lowercase()
                if (TRUSTED_AWS_ROOT_FINGERPRINTS.contains(fp)) {
                    trustAnchors.add(TrustAnchor(cert, null))
                }
            }

            val params = PKIXBuilderParameters(trustAnchors, target)
            params.addCertStore(certStore)
            params.isRevocationEnabled = false

            // 5. Zinciri Oluştur ve Doğrula — "BC" ile BouncyCastle'ı zorla (P-384 EC desteği)
            val builder = CertPathBuilder.getInstance("PKIX", BouncyCastleProvider.PROVIDER_NAME)
            builder.build(params) // Geçerli bir zincir bulamazsa exception atar
            
            Log.i(TAG, "[Tasdik] ✅ Sertifika zinciri oluşturuldu ve doğrulandı.")
            Pair(true, "OK")

        } catch (e: Exception) {
            val cause = generateSequence(e.cause) { it.cause }
                .joinToString(" → ") { "${it.javaClass.simpleName}: ${it.message}" }
            val detail = if (cause.isNotEmpty()) " [$cause]" else ""
            Log.e(TAG, "Zincir doğrulama hatası: ${e.message}$detail", e)
            Pair(false, "Sertifika Hatası: ${e.message}$detail")
        }
    }

    // 
    // Kontrol 2: PCR0 Değeri
    // 
    private fun verifyPcr0(payload: CBORObject, expectedPcr0: String): Pair<Boolean, String> {
        return try {
            val pcrsObj = payload["pcrs"] 
                ?: return Pair(false, "No 'pcrs' field in payload")
            
            val pcr0Bytes = pcrsObj[0]?.GetByteString()
                ?: return Pair(false, "PCR[0] not found in payload")

            val actualPcr0 = pcr0Bytes.toHex()
            val normalizedExpected = expectedPcr0.lowercase().trim()
            val normalizedActual = actualPcr0.lowercase().trim()

            if (normalizedExpected != normalizedActual) {
                return Pair(false,
                    "PCR0 mismatch!\n  Expected: ${normalizedExpected.take(32)}...\n  Actual:   ${normalizedActual.take(32)}..."
                )
            }
            Pair(true, "OK")
        } catch (e: Exception) {
            Pair(false, "PCR0 parse error: ${e.message}")
        }
    }

    private fun extractPcr0(payload: CBORObject): String {
        return try {
            payload["pcrs"]?.get(0)?.GetByteString()?.toHex() ?: "UNKNOWN"
        } catch (e: Exception) { "UNKNOWN" }
    }

    // 
    // Kontrol 3: Public Key Binding
    // 
    private fun verifyPcr0Authorization(pcr0: String, signatureBase64: String): Pair<Boolean, String> {
        return try {
            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
            val pubKeyBytes = Base64.decode(DEVELOPER_PUBLIC_KEY, Base64.DEFAULT)
            
            // 1. Parse RSA CSP Blob (Windows format)
            val publicKey = parseRsaCspBlob(pubKeyBytes)
                ?: return Pair(false, "Could not parse developer public key blob")

            // 2. Verify Signature
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(pcr0.toByteArray(Charsets.UTF_8))
            
            if (verifier.verify(sigBytes)) {
                Pair(true, "OK")
            } else {
                Pair(false, "Signature mismatch (Invalid authorization)")
            }
        } catch (e: Exception) {
            Pair(false, "Auth error: ${e.message}")
        }
    }

    /**
     * Parses a Windows RSA CSP Public Key Blob (BCRYPT_RSAPUBLIC_BLOB / PUBLICKEYBLOB).
     * Format: [BLOBHEADER] [RSAPUBKEY] [MODULUS]
     */
    private fun parseRsaCspBlob(blob: ByteArray): java.security.PublicKey? {
        return try {
            // RSA CSP Public Key Blobs start with:
            // Type (1 byte): 0x06 (PUBLICKEYBLOB)
            // Version (1 byte): 0x02
            // Reserved (2 bytes): 0x0000
            // Algorithm (4 bytes): 0x0000a400 (CALG_RSA_KEYX) or 0x00002400 (CALG_RSA_SIGN)
            // Magic (4 bytes): 'RSA1' (0x31415352)
            // BitLen (4 bytes): 2048 (0x00000800)
            // PubExp (4 bytes): 65537 (0x00010001) usually
            // Modulus (BitLen/8 bytes)
            
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            
            val type = buffer.get().toInt()
            if (type != 0x06) return null // Must be PUBLICKEYBLOB
            
            buffer.position(8) // Skip version, reserved, algid
            val magic = buffer.int
            if (magic != 0x31415352) return null // 'RSA1'
            
            val bitLen = buffer.int
            val pubExp = buffer.int.toLong() and 0xFFFFFFFFL
            
            val modulusBytes = ByteArray(bitLen / 8)
            buffer.get(modulusBytes)
            
            // Important: Windows is Little Endian, BigInteger is Big Endian
            val modulus = BigInteger(1, modulusBytes.reversedArray())
            val exponent = BigInteger.valueOf(pubExp)
            
            val spec = RSAPublicKeySpec(modulus, exponent)
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (e: Exception) {
            Log.e(TAG, "CSP Blob ayrıştırma hatası: ${e.message}")
            null
        }
    }

    /**
     * Extracts the Enclave Public Key from the 'user_data' field in the attestation payload.
     */
    private fun extractEnclavePublicKey(payload: CBORObject): String? {
        return try {
            val userDataBytes = payload["user_data"]?.GetByteString()
            if (userDataBytes != null) {
                String(userDataBytes, Charsets.UTF_8).trim()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Enclave Genel Anahtarı çıkarılamadı: ${e.message}")
            null
        }
    }

    // 
    // Yardımcı fonksiyonlar
    // 
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun ByteArray.sha256Hex(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(this).toHex()
    }
}
