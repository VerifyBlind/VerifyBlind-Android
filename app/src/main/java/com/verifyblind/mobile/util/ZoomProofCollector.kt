package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max

/**
 * YAKINLAŞTIRMA KANITI TOPLAYICISI — "kameranın önündeki yüz üç boyutlu mu, düz bir yüzey mi?"
 *
 * ## Neden var
 *
 * Doku tabanlı anti-spoof (MiniFASNetV2) monitör hilesini kurucunun kendi denemelerinde 14'te 6
 * kaçırdı ve eşik oynatmak çözmüyor: meşru girişlerin en düşük P(live) değeri 0,817, geçen
 * saldırılar 0,822-0,977 — dağılımlar çakışıyor. Doku bir MODEL hükmüdür ve yanılır.
 *
 * Bu adım farklı bir sinyal toplar: **geometri**. Gerçek yüzde burun ucu, gözler ve ağız
 * köşelerinin düzleminin ~20 mm önündedir; kamera yaklaştıkça burun daha çok büyür, yüzün
 * izdüşüm şekli değişir. Ekrandaki yüz düzlemseldir; kamera hareketi tüm noktalara aynı
 * homografiyi uygular ve şekil hiç değişmez. Saldırgan monitörü eğse, kaydırsa, döndürse de
 * bunu üretemez — hepsi homografidir (enclave tarafında sentetik testle doğrulandı).
 *
 * ## Neden görüntü gönderiyoruz, ölçüm değil
 *
 * Nokta çıkarımını burada yapıp sunucuya sayı göndermek, tüm sınamayı **istemciye emanet
 * ederdi** — yamalanabilir bir boolean'dan farkı kalmazdı. Bu sınıf yalnız KARE taşır;
 * 5 noktayı ve sinyali enclave kendi YuNet'iyle üretir.
 *
 * ## Buradaki kontrol neden güvenliği zayıflatmıyor
 *
 * Bu sınıf yalnız **hareketin gerçekleştiğini** doğrular, yüzün gerçek olduğunu DEĞİL.
 * Yamalanmış bir istemci bu kontrolü atlarsa sunucuya hareketsiz kareler gider ve sinyal
 * çıkmaz — yani atlamak saldırgana bir şey kazandırmaz. Kazandırdığı tek şey ACEMİ KULLANICININ
 * yeterince yaklaşmadan geçmesini engellemektir; o da bir güvenlik kontrolü değil, veri
 * kalitesi meselesidir.
 *
 * ## Neden yakın pencere hedefe ulaşmadan AÇILMIYOR
 *
 * Standart kullanıcı ne yapması gerektiğini bilmez; 2-3 cm yaklaşıp bırakabilir. Yarı yolda
 * toplanan kareler "ölçtük" görüntüsü verir ama sinyal mesafe değişiminden doğduğu için
 * anlamsızdır ve eşik çalışmasını KİRLETİR. Bu yüzden yakın pencere ancak yüz, uzak medyanın
 * [ZOOM_TARGET_RATIO] katına ulaşınca açılır: veri ya iyidir ya hiç yoktur, yarım olmaz.
 *
 * ## Akış
 *
 * 1. **UZAK** — olduğu yerde [FRAMES_PER_WINDOW] kare.
 * 2. **YAKLAŞMA** — canlı ilerleme geri bildirimi; takılırsa dürtme, hâlâ takılırsa
 *    **GERİ ÇEKİL** adımıyla taban yeniden alınır (kullanıcı en baştan çok yakın durmuş olabilir;
 *    o zaman %60 daha yaklaşmak fiziksel olarak mümkün değildir).
 * 3. **YAKIN** — aynı sayıda kare, yalnız hedef korunurken.
 *
 * ⚠️ Adım BAŞARISIZ OLAMAZ: süre dolarsa elde ne varsa onunla biter ve kayıt normal devam eder.
 * Hedefe hiç ulaşılmadıysa [Result.reachedTarget] false gider ve sunucu bunu "yaklaşmadı" diye
 * kaydeder — "ölçemedik" ile "sahte" ayrımı sunucuda da korunur.
 */
class ZoomProofCollector(
    private val cacheDir: File,
    private val onGuidance: (Phase, progress: Float) -> Unit,
    private val onProgress: (collected: Int, target: Int) -> Unit,
    private val onComplete: (Result) -> Unit,
) {

    enum class Phase {
        /** Olduğu yerde kalsın; uzak pencere toplanıyor. */
        FAR,

        /** "Yaklaştırın" — ilerleme canlı bildiriliyor. */
        APPROACH,

        /** "Önce biraz geri çekilin" — kullanıcı en baştan çok yakındı, taban yeniden alınacak. */
        MOVE_BACK,

        /** Hedefe ulaşıldı; yakın pencere toplanıyor. */
        NEAR,

        DONE,
    }

    data class Result(
        val farPaths: List<String>,
        val nearPaths: List<String>,
        /** İstemcinin kendi ölçtüğü medyan gözler-arası mesafe (px) — DOĞRULANMAZ, kıyas için. */
        val farIed: Double?,
        val nearIed: Double?,
        val elapsedMs: Int,
        /**
         * Yaklaşma hedefine gerçekten ulaşıldı mı.
         *
         * false ise yakın pencere BOŞTUR (bilerek) ve sunucu satırı "yaklaşmadı" olarak
         * işaretlenir. Yarım ölçüm kaydetmek, dağılımı sahte veriyle doldururdu.
         */
        val reachedTarget: Boolean,
    )

    companion object {
        private const val TAG = "ZoomProof"

        /** Pencere başına kare. Enclave tavanı 12; 8 hem yeterli hem yük olarak ılımlı. */
        const val FRAMES_PER_WINDOW = 8

        /**
         * Yakın pencerenin açılması için gereken büyüme oranı.
         *
         * ⚠️ Bu bir SAHTECİLİK ölçüsü değildir — monitöre yaklaşınca ekrandaki yüz de büyür.
         * Yalnız "hareket gerçekten oldu mu" kapısıdır; ayırt eden şey enclave'deki geometrik
         * sinyaldir. Yaklaşma olmadan o sinyalin anlamı yoktur (mesafe değişiminden doğar).
         */
        const val ZOOM_TARGET_RATIO = 1.6f

        /**
         * Yakın pencere toplanırken kabul edilen alt sınır (histerezis).
         *
         * Hedefte tam 1,6'da durup titremek pencereyi açıp kapatırdı; kullanıcı elini biraz
         * oynattı diye toplanan kareler çöpe gitmemeli.
         */
        private const val NEAR_HOLD_RATIO = 1.45f

        /** Kare aralığı — arka arkaya neredeyse aynı kareyi toplamanın anlamı yok. */
        private const val FRAME_INTERVAL_MS = 90L

        /**
         * Tüm adımın tavanı. Dolarsa elde ne varsa onunla bitilir.
         *
         * ⚠️ Bu süre [offer] içinde de kontrol edilir ama ORAYA GÜVENİLEMEZ: `offer` yalnız
         * ML Kit bir YÜZ bulduğunda çağrılıyor. Kullanıcı telefonu yüzüne getirirken yüz
         * kadrajdan çıkarsa hiç kare gelmez, sayaç hiç işlemez ve ekran sonsuza kadar asılı
         * kalır. Bu yüzden çağıran AYRICA kareden bağımsız bir bekçi kurar ve [timeoutNow]
         * çağırır (bkz. LivenessActivity.startZoomPhase).
         */
        const val TOTAL_TIMEOUT_MS = 22_000L

        /** Uzak pencerenin tavanı — kare gelmiyorsa burada takılıp kalmayalım. */
        private const val FAR_WINDOW_TIMEOUT_MS = 5_000L

        /** Bu süre ilerleme olmadan geçerse kullanıcı dürtülür. */
        private const val NUDGE_AFTER_MS = 3_500L

        /**
         * Bu süre sonunda ilerleme hâlâ bu eşiğin altındaysa kullanıcı muhtemelen en baştan
         * çok yakındı → geri çekilme adımı. "Daha çok deneyin" demek burada işe yaramaz,
         * çünkü sorun çaba değil fizik: 20 cm'den %60 daha yaklaşılamaz.
         */
        private const val MOVE_BACK_AFTER_MS = 7_000L
        private const val MOVE_BACK_PROGRESS_THRESHOLD = 0.35f

        /** Geri çekilmenin tamamlandığı kabul edilen küçülme oranı. */
        private const val MOVE_BACK_RATIO = 0.75f

        /** Taban en fazla kaç kez yeniden alınır — sonsuz döngü olmasın. */
        private const val MAX_RESETS = 2

        /** Kare kenarı. Nokta hassasiyeti buna bağlı: çok küçültmek sinyali gürültüye gömer. */
        private const val OUTPUT_SIZE = 256

        /** Yüz kutusunun kaç katı alınır — burun/ağız/göz tamamı ve biraz bağlam. */
        private const val CROP_SCALE = 2.0f

        private const val JPEG_QUALITY = 85
    }

    var phase: Phase = Phase.FAR
        private set

    val isActive: Boolean get() = phase != Phase.DONE

    private val farPaths = mutableListOf<String>()
    private val nearPaths = mutableListOf<String>()
    private val farWidths = mutableListOf<Float>()
    private val farIeds = mutableListOf<Double>()
    private val nearIeds = mutableListOf<Double>()

    private var startedAt = 0L
    private var farWindowStartedAt = 0L
    private var approachStartedAt = 0L
    private var lastFrameAt = 0L
    private var farMedianWidth = 0f
    private var widthBeforeMoveBack = 0f
    private var reachedTarget = false
    private var resets = 0
    private var nudged = false
    private var lastReportedProgress = -1f

    fun start() {
        startedAt = System.currentTimeMillis()
        farWindowStartedAt = startedAt
        phase = Phase.FAR
        onGuidance(Phase.FAR, 0f)
        onProgress(0, FRAMES_PER_WINDOW)
    }

    /**
     * Bir kamera karesini değerlendirir. Çağıran [ImageProxy]'yi KAPATMAYA devam eder —
     * bu sınıf yalnız okur.
     */
    fun offer(imageProxy: ImageProxy, face: Face) {
        if (phase == Phase.DONE) return

        val now = System.currentTimeMillis()

        if (now - startedAt > TOTAL_TIMEOUT_MS) {
            Log.i(TAG, "Süre doldu (uzak=${farPaths.size} yakın=${nearPaths.size} hedef=$reachedTarget)")
            finish()
            return
        }

        val faceWidth = face.boundingBox.width().toFloat()
        if (faceWidth < 40f) return   // yüz yok sayılacak kadar küçük

        when (phase) {
            Phase.FAR -> handleFar(imageProxy, face, faceWidth, now)
            Phase.APPROACH -> handleApproach(faceWidth, now)
            Phase.MOVE_BACK -> handleMoveBack(faceWidth)
            Phase.NEAR -> handleNear(imageProxy, face, faceWidth, now)
            Phase.DONE -> return
        }
    }

    private fun handleFar(imageProxy: ImageProxy, face: Face, faceWidth: Float, now: Long) {
        if (now - lastFrameAt >= FRAME_INTERVAL_MS &&
            capture(imageProxy, face, farPaths, "zoom_far")
        ) {
            lastFrameAt = now
            farWidths += faceWidth
            interocular(face)?.let { farIeds += it }
            onProgress(farPaths.size, FRAMES_PER_WINDOW)
        }

        val windowFull = farPaths.size >= FRAMES_PER_WINDOW
        val windowTimedOut = now - farWindowStartedAt > FAR_WINDOW_TIMEOUT_MS && farPaths.isNotEmpty()
        if (windowFull || windowTimedOut) {
            farMedianWidth = median(farWidths)
            enterApproach(now)
        }
    }

    private fun enterApproach(now: Long) {
        phase = Phase.APPROACH
        approachStartedAt = now
        nudged = false
        lastReportedProgress = -1f
        // Sıra önemli: sayaç önce temizlenir, sonra ilerleme yüzdesi yazılır — ikisi de aynı
        // görünümü kullanıyor ve ters sırada yüzde anında siliniyordu.
        onProgress(0, FRAMES_PER_WINDOW)
        onGuidance(Phase.APPROACH, 0f)
    }

    private fun handleApproach(faceWidth: Float, now: Long) {
        if (farMedianWidth <= 0f) return

        val ratio = faceWidth / farMedianWidth
        // İlerleme: 1,0× başlangıç, hedef oranda 1,0. Geri gidilirse 0'a kırpılır.
        val progress = ((ratio - 1f) / (ZOOM_TARGET_RATIO - 1f)).coerceIn(0f, 1f)

        // Yalnız gözle görülür değişimde bildir — her karede UI güncellemek gereksiz.
        if (lastReportedProgress < 0f || kotlin.math.abs(progress - lastReportedProgress) >= 0.04f) {
            lastReportedProgress = progress
            onGuidance(Phase.APPROACH, progress)
        }

        if (ratio >= ZOOM_TARGET_RATIO) {
            reachedTarget = true
            phase = Phase.NEAR
            lastReportedProgress = -1f
            onGuidance(Phase.NEAR, 1f)
            onProgress(0, FRAMES_PER_WINDOW)
            return
        }

        val elapsed = now - approachStartedAt

        if (!nudged && elapsed > NUDGE_AFTER_MS) {
            nudged = true
            onGuidance(Phase.APPROACH, progress)   // çağıran "biraz daha" metnini gösterir
        }

        // Hâlâ ilerleme yoksa sorun çaba değil MESAFE: kullanıcı en baştan çok yakın durmuş.
        if (elapsed > MOVE_BACK_AFTER_MS &&
            progress < MOVE_BACK_PROGRESS_THRESHOLD &&
            resets < MAX_RESETS
        ) {
            resets++
            widthBeforeMoveBack = farMedianWidth
            phase = Phase.MOVE_BACK
            onGuidance(Phase.MOVE_BACK, 0f)
        }
    }

    private fun handleMoveBack(faceWidth: Float) {
        if (widthBeforeMoveBack <= 0f) return
        if (faceWidth > widthBeforeMoveBack * MOVE_BACK_RATIO) return

        // Yeterince geri çekildi → uzak pencereyi SIFIRDAN al. Eski kareler yanlış mesafeden,
        // taban olarak kullanılamazlar.
        farPaths.forEach { runCatching { File(it).delete() } }
        farPaths.clear()
        farWidths.clear()
        farIeds.clear()
        farMedianWidth = 0f
        farWindowStartedAt = System.currentTimeMillis()
        phase = Phase.FAR
        onGuidance(Phase.FAR, 0f)
        onProgress(0, FRAMES_PER_WINDOW)
    }

    private fun handleNear(imageProxy: ImageProxy, face: Face, faceWidth: Float, now: Long) {
        val ratio = if (farMedianWidth > 0f) faceWidth / farMedianWidth else 0f

        // Kullanıcı geri kaçtıysa toplamayı DURDUR ama toplananı atma — geri gelince devam eder.
        if (ratio < NEAR_HOLD_RATIO) {
            onGuidance(Phase.APPROACH, ((ratio - 1f) / (ZOOM_TARGET_RATIO - 1f)).coerceIn(0f, 1f))
            phase = Phase.APPROACH
            approachStartedAt = now
            return
        }

        if (now - lastFrameAt >= FRAME_INTERVAL_MS &&
            capture(imageProxy, face, nearPaths, "zoom_near")
        ) {
            lastFrameAt = now
            interocular(face)?.let { nearIeds += it }
            onProgress(nearPaths.size, FRAMES_PER_WINDOW)
        }

        if (nearPaths.size >= FRAMES_PER_WINDOW) finish()
    }

    /**
     * KARE GELMESE BİLE adımı bitirir — çağıranın kurduğu bekçi buraya düşer.
     *
     * Gerekçe: [offer] yalnız yüz bulunan karelerde çağrılıyor, dolayısıyla içindeki süre
     * kontrolü yüz kadrajdan çıktığında HİÇ ÇALIŞMAZ. Telefonu yüze yaklaştırmak tam da yüzün
     * kaybolmaya en müsait olduğu an; bekçi olmadan ekran orada asılı kalır.
     */
    fun timeoutNow() {
        if (phase == Phase.DONE) return
        Log.i(TAG, "Bekçi bitirdi (uzak=${farPaths.size} yakın=${nearPaths.size} hedef=$reachedTarget)")
        finish()
    }

    /** Kullanıcı ekrandan çıktı / akış iptal oldu — elde olanı bırakıp temizle. */
    fun abandon() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        (farPaths + nearPaths).forEach { runCatching { File(it).delete() } }
        farPaths.clear()
        nearPaths.clear()
    }

    private fun finish() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        onGuidance(Phase.DONE, 1f)

        // Hedefe ulaşılmadıysa yakın pencere zaten boştur; uzak kareler tek başına ölçüm
        // üretemez ama "yaklaşmadı" bilgisini taşımaları değerli — adımın kaç kullanıcıda
        // çalışmadığını ancak böyle öğreniriz.
        onComplete(
            Result(
                farPaths = farPaths.toList(),
                nearPaths = nearPaths.toList(),
                farIed = if (farIeds.isEmpty()) null else median(farIeds),
                nearIed = if (nearIeds.isEmpty()) null else median(nearIeds),
                elapsedMs = (System.currentTimeMillis() - startedAt).toInt(),
                reachedTarget = reachedTarget,
            )
        )
    }

    /**
     * Kareyi yüz çevresinden KARE biçiminde kırpıp [OUTPUT_SIZE] boyutuna indirir ve diske yazar.
     *
     * ⚠️ Kırpma ve ölçekleme geometriyi BOZMAZ: ikisi de afin dönüşümdür ve enclave'deki
     * dört-nokta homografisi tarafından tamamen soğurulur. Ölçülen şey homografinin
     * AÇIKLAYAMADIĞI artıktır — yani burnun düzlem dışılığı.
     */
    private fun capture(imageProxy: ImageProxy, face: Face, into: MutableList<String>, prefix: String): Boolean {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        var cropRef: Bitmap? = null
        var scaledRef: Bitmap? = null

        return try {
            val bitmap = imageProxy.toBitmap() ?: return false
            srcRef = bitmap

            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            fullRef = full

            val box = face.boundingBox
            val side = max(box.width(), box.height()) * CROP_SCALE
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()

            val left = (cx - side / 2f).coerceAtLeast(0f).toInt()
            val top = (cy - side / 2f).coerceAtLeast(0f).toInt()
            val right = (cx + side / 2f).coerceAtMost(full.width.toFloat()).toInt()
            val bottom = (cy + side / 2f).coerceAtMost(full.height.toFloat()).toInt()

            val w = (right - left).coerceAtLeast(1)
            val h = (bottom - top).coerceAtLeast(1)
            if (w < 60 || h < 60) return false

            val crop = Bitmap.createBitmap(full, left, top, w, h)
            cropRef = crop

            val scaled = Bitmap.createScaledBitmap(crop, OUTPUT_SIZE, OUTPUT_SIZE, true)
            scaledRef = scaled

            val file = File(cacheDir, "${prefix}_${into.size}.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            into += file.absolutePath
            true
        } catch (e: Exception) {
            // Tek karenin düşmesi adımı bozmaz: pencere diğer karelerle dolar.
            Log.w(TAG, "Kare yazılamadı (atlanıyor): ${e.message}")
            false
        } finally {
            // Zincirin tersinden; türetilmiş bitmap kaynağın KENDİSİ olabilir (dönüşüm gereksizse
            // createBitmap kaynağı döndürür) — çift recycle olmasın diye kimlik kontrolü şart.
            scaledRef?.let { if (it !== cropRef) it.recycle() }
            cropRef?.let { if (it !== fullRef) it.recycle() }
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    private fun interocular(face: Face): Double? {
        val l = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val r = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val d = hypot((l.x - r.x).toDouble(), (l.y - r.y).toDouble())
        return if (d > 1.0) d else null
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    @JvmName("medianDouble")
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
