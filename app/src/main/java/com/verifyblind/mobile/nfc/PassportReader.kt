package com.verifyblind.mobile.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import net.sf.scuba.smartcards.CardService
import org.jmrtd.AccessKeySpec
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File

/**
 * PACE-MRZ ve BAC ikisi de başarısız olduğunda, kartın PACE-CAN gerektirdiğini
 * bildirmek için fırlatılır. MainActivity dialog açar, kullanıcı CAN girer ve
 * kartı tekrar okutur.
 */
class PaceCanRequiredException : Exception("Bu kimlik kartı PACE-CAN gerektiriyor")

object PassportReader {

    private const val TAG = "PassportReader"

    /**
     * CAN (Card Access Number) anahtarı — kartın ön yüzünde basılı 6 haneli numara.
     * JMRTD'nin doPACE() MRZ yerine CAN byte'larını verir.
     *
     * Not: JMRTD içinde non-BACKey için Key.getEncoded() kullanıldığı varsayımına dayanır.
     * Gerçek PACE-CAN kartıyla (Alman nPA, Hollanda kimlik vb.) doğrulanması gerekir.
     */
    private class CanKey(private val can: String) : AccessKeySpec {
        override fun getAlgorithm(): String = "CAN"
        override fun getKey(): ByteArray = can.toByteArray(Charsets.UTF_8)
    }

    data class PassportData(
        val dg1: DG1File,
        val dg1Raw: ByteArray, // Capture exact bytes for hash verification
        val sod: SODFile,
        val faceImage: ByteArray?,
        val dg15Bytes: ByteArray?,
        val activeAuthSignature: ByteArray,
        val challenge: ByteArray
    )

    fun readPassport(
        tag: Tag,
        docNoRaw: String,
        dobRaw: String,
        doeRaw: String,
        challenge: ByteArray,
        can: String? = null  // PACE-CAN için — null ise önce MRZ denenir, başarısız olursa BAC
    ): PassportData {
        val isoDep = IsoDep.get(tag)
        isoDep.timeout = 20000
        val docNo = cleanDocNo(docNoRaw)
        val dob = correctDateInput(dobRaw)
        val doe = correctDateInput(doeRaw)

        Log.d(TAG, "Temizlenmiş Giriş Verileri -> Belge: $docNo, DoğumT: $dob, SonGec: $doe")

        val cardService = CardService.getInstance(isoDep)
        cardService.open()

        try {
            val service = PassportService(cardService, 256, 224, false, false)
            service.open()

            var paceSucceeded = false
            var cardHasPace = false
            try {
                val cardAccessFile = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
                val paceInfo = cardAccessFile.securityInfos
                    .filterIsInstance<PACEInfo>()
                    .firstOrNull()
                if (paceInfo != null) {
                    cardHasPace = true
                    val paceKey: AccessKeySpec = if (can != null) {
                        CanKey(can)
                    } else {
                        BACKey(docNo, dob, doe)
                    }
                    service.doPACE(
                        paceKey,
                        paceInfo.objectIdentifier,
                        PACEInfo.toParameterSpec(paceInfo.parameterId),
                        null
                    )
                    paceSucceeded = true
                    Log.d(TAG, "PACE başarılı (${if (can != null) "CAN" else "MRZ"}): ${paceInfo.objectIdentifier}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "PACE başarısız (${if (can != null) "CAN" else "MRZ"}): ${e.message}")
            }

            service.sendSelectApplet(paceSucceeded)
            if (!paceSucceeded) {
                try {
                    val bacKey = BACKey(docNo, dob, doe)
                    service.doBAC(bacKey)
                } catch (bacEx: Exception) {
                    // PACE ve BAC ikisi de başarısız — kart büyük ihtimalle PACE-CAN gerektiriyor
                    if (cardHasPace && can == null) {
                        Log.w(TAG, "PACE-MRZ ve BAC başarısız, CAN gerekebilir: ${bacEx.message}")
                        throw PaceCanRequiredException()
                    }
                    throw bacEx
                }
            }

            // Read DG1 RAW (Crucial for Hash Verification)
            val dg1Stream = service.getInputStream(PassportService.EF_DG1)
            val dg1Raw = dg1Stream.readBytes()
            val dg1 = DG1File(java.io.ByteArrayInputStream(dg1Raw))

            // Read DG2
            var faceImageBytes: ByteArray? = null
            try {
                val dg2File = org.jmrtd.lds.icao.DG2File(service.getInputStream(PassportService.EF_DG2))
                if (dg2File.faceInfos.isNotEmpty()) {
                    val imageInfo = dg2File.faceInfos[0].faceImageInfos.firstOrNull()
                    faceImageBytes = imageInfo?.imageInputStream?.readBytes()
                }
            } catch (e: Exception) { Log.w(TAG, "DG2 okuma hatası: ${e.message}") }

            // Read DG15 (AA public key — CA-only veya eski kartlarda bulunmayabilir)
            var dg15Bytes: ByteArray? = null
            try {
                val dg15Stream = service.getInputStream(PassportService.EF_DG15)
                dg15Bytes = dg15Stream.readBytes()
                Log.d(TAG, "DG15 okundu — AA destekli (${dg15Bytes.size} bayt)")
            } catch (e: Exception) {
                Log.w(TAG, "DG15 yok veya okunamadı — AA atlanacak: ${e.message}")
            }

            // Read SOD
            val sod = SODFile(service.getInputStream(PassportService.EF_SOD))

            // Active Authentication — yalnızca DG15 mevcutsa dene
            // CA-only kartlar DG15 içermez; sunucu tarafı boş imzayı kabul eder
            var aaSignature = ByteArray(0)
            if (dg15Bytes != null && dg15Bytes.isNotEmpty()) {
                try {
                    val aaResult = service.doAA(null, null, null, challenge)
                    aaSignature = aaResult.response
                    Log.d(TAG, "Active Authentication başarılı ✓")
                } catch (e: Exception) {
                    // DG15 var ama AA başarısız → sunucu anti-downgrade korumasıyla reddeder
                    Log.e(TAG, "DG15 mevcut ancak AA başarısız: ${e.message}")
                    throw e
                }
            } else {
                Log.d(TAG, "DG15 yok — AA atlandı (CA-only veya chip auth desteksiz kart)")
            }

            return PassportData(dg1, dg1Raw, sod, faceImageBytes, dg15Bytes, aaSignature, challenge)

        } finally {
            try { cardService.close() } catch(e: Exception) { Log.w(TAG, "Kapat hatası: ${e.message}") }
        }
    }

    fun cleanDocNo(input: String): String {
        // Just minimal cleanup. Variations handle the rest.
        return input.replace(" ", "").uppercase()
    }

    fun correctDateInput(input: String): String {
        // 1. Clean Separators
        var s = input.replace("/", "").replace(".", "").replace("-", "").replace(" ", "")

        // 2. Map OCR Alpha errors to Digits (Restored)
        s = s.replace("O", "0").replace("o", "0")
             .replace("Q", "0").replace("D", "0")
             .replace("I", "1").replace("l", "1").replace("L", "1")
             .replace("Z", "2").replace("z", "2")
             .replace("S", "5").replace("s", "5")
             .replace("B", "8").replace("b", "8")
             .replace("G", "6")

        // 3. Strict Numeric Only (Safety)
        s = s.replace(Regex("[^0-9]"), "")

        // 4. Smart Format Detection
        if (s.length == 8) {
            // Assume DDMMYYYY -> YYMMDD
            val day = s.substring(0, 2)
            val month = s.substring(2, 4)
            val yearFull = s.substring(4, 8)
            val yearShort = yearFull.substring(2, 4)

            return "$yearShort$month$day"
        }
        else if (s.length > 6) {
             return s.substring(0, 6)
        }

        return s
    }

    // Variations functions removed - Strict Mode Enforced
}
