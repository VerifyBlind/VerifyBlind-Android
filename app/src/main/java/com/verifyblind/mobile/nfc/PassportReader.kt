package com.verifyblind.mobile.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.verifyblind.mobile.util.AppLog
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File

object PassportReader {

    private const val TAG = "PassportReader"

    // NOT: VerifyBlind yalnızca Türk kimlik kartı ve pasaportunu destekler; ikisi de MRZ-BAC/PACE ile
    // okunur. PACE-CAN (kart ön yüzündeki 6 haneli numara — Alman nPA/Hollanda gibi yabancı kartlar)
    // desteklenmez ve bilinçli olarak KALDIRILDI.

    data class PassportData(
        val dg1: DG1File,
        val dg1Raw: ByteArray, // Capture exact bytes for hash verification
        val sod: SODFile,
        val faceImage: ByteArray?,
        val dg2Raw: ByteArray?, // RAW DG2 EF bytes — needed for SOD hash verification (faceImage is re-encoded and won't match)
        val dg15Bytes: ByteArray?,
        val activeAuthSignature: ByteArray,
        val challenge: ByteArray
    )

    fun readPassport(
        tag: Tag,
        docNoRaw: String,
        dobRaw: String,
        doeRaw: String,
        challenge: ByteArray
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
            try {
                val cardAccessFile = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
                val paceInfo = cardAccessFile.securityInfos
                    .filterIsInstance<PACEInfo>()
                    .firstOrNull()
                if (paceInfo != null) {
                    service.doPACE(
                        BACKey(docNo, dob, doe),
                        paceInfo.objectIdentifier,
                        PACEInfo.toParameterSpec(paceInfo.parameterId),
                        null
                    )
                    paceSucceeded = true
                    Log.d(TAG, "PACE başarılı (MRZ): ${paceInfo.objectIdentifier}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "PACE başarısız (MRZ): ${e.message}")
            }

            service.sendSelectApplet(paceSucceeded)
            if (!paceSucceeded) {
                // PACE-MRZ olmadıysa BAC dene; o da başarısızsa kart okunamadı → hata yukarı akar
                // (nfcRetryCount ile yeniden denenir). Türk kimlik/pasaport MRZ-BAC/PACE ile okunur.
                val bacKey = BACKey(docNo, dob, doe)
                service.doBAC(bacKey)
            }

            // Read DG1 RAW (Crucial for Hash Verification)
            val dg1Stream = service.getInputStream(PassportService.EF_DG1)
            val dg1Raw = dg1Stream.readBytes()
            val dg1 = DG1File(java.io.ByteArrayInputStream(dg1Raw))

            // Read DG2 RAW (Crucial for SOD Hash Verification — the extracted face image is
            // re-encoded and won't match the SOD hash). Mirrors the DG1 raw-capture pattern.
            var dg2Raw: ByteArray? = null
            var faceImageBytes: ByteArray? = null
            try {
                val dg2Stream = service.getInputStream(PassportService.EF_DG2)
                dg2Raw = dg2Stream.readBytes()
                val dg2File = org.jmrtd.lds.icao.DG2File(java.io.ByteArrayInputStream(dg2Raw))
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
                    AppLog.warning("DG15 mevcut ancak AA başarısız: ${e.message}", TAG, e)
                    throw e
                }
            } else {
                Log.d(TAG, "DG15 yok — AA atlandı (CA-only veya chip auth desteksiz kart)")
            }

            // Sentry breadcrumb (iOS paritesi): AA hatası araştırılırken kartı tekrar isteyemeyeceğimiz
            // için okumanın YAPISAL özeti şart. Yalnız uzunluklar ve varlık bilgisi — kimlik verisi YOK.
            AppLog.info(
                "NFC okuma tamam: DG1=${dg1Raw.size}B DG2=${faceImageBytes?.size ?: 0}B " +
                    "DG15=${dg15Bytes?.size ?: 0}B AAsupported=${dg15Bytes != null} " +
                    "AAsig=${aaSignature.size}B challenge=${challenge.size}B",
                TAG
            )

            return PassportData(dg1, dg1Raw, sod, faceImageBytes, dg2Raw, dg15Bytes, aaSignature, challenge)

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
