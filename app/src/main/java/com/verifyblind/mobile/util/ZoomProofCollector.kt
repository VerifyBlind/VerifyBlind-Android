package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
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
 * 5 noktayı ve sinyali enclave kendi YuNet'iyle üretir. Buradaki [farIed]/[nearIed] yalnız
 * kıyas içindir, hiçbir karara girmez.
 *
 * ## Neden çok kare
 *
 * Sinyal gözler-arası mesafenin ~%3'ü ve nokta titremesiyle aynı mertebede. Sentetik ölçümde
 * tek kare çifti 1,5 px titremede yalnız %60 ayırıyor, pencere başına 9 kare ile %98.
 * Bu yüzden iki PENCERE toplanır, tek çift değil.
 *
 * ## Akış
 *
 * 1. **UZAK** — kullanıcı olduğu yerdeyken [FRAMES_PER_WINDOW] kare.
 * 2. **YAKLAŞMA** — "telefonu yaklaştır"; yüz genişliği uzak medyanın [ZOOM_TARGET_RATIO] katına
 *    çıkana kadar beklenir.
 * 3. **YAKIN** — aynı sayıda kare.
 *
 * ⚠️ Adım BAŞARISIZ OLAMAZ: süre dolarsa elde ne varsa onunla biter ve kayıt normal devam eder.
 * Ölçüm şimdilik bir kapı değil; eşik canlı dağılım görüldükten sonra konacak.
 */
class ZoomProofCollector(
    private val cacheDir: File,
    private val onPhaseChanged: (Phase) -> Unit,
    private val onProgress: (collected: Int, target: Int) -> Unit,
    private val onComplete: (Result) -> Unit,
) {

    enum class Phase { FAR, APPROACH, NEAR, DONE }

    data class Result(
        val farPaths: List<String>,
        val nearPaths: List<String>,
        /** İstemcinin kendi ölçtüğü medyan gözler-arası mesafe (px) — DOĞRULANMAZ, kıyas için. */
        val farIed: Double?,
        val nearIed: Double?,
        val elapsedMs: Int,
        /** Yaklaşma hedefine ulaşıldı mı — ulaşılmadıysa sinyalin anlamı zayıftır. */
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
        const val ZOOM_TARGET_RATIO = 1.6

        /** Kare aralığı — arka arkaya neredeyse aynı kareyi toplamanın anlamı yok. */
        private const val FRAME_INTERVAL_MS = 90L

        /** Tüm adımın tavanı. Dolarsa elde ne varsa onunla bitilir. */
        private const val TOTAL_TIMEOUT_MS = 12_000L

        /** Uzak pencerenin tavanı — kullanıcı zaten yakınsa burada takılıp kalmayalım. */
        private const val FAR_WINDOW_TIMEOUT_MS = 4_000L

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
    private val nearWidths = mutableListOf<Float>()
    private val farIeds = mutableListOf<Double>()
    private val nearIeds = mutableListOf<Double>()

    private var startedAt = 0L
    private var farWindowStartedAt = 0L
    private var lastFrameAt = 0L
    private var farMedianWidth = 0f
    private var reachedTarget = false

    fun start() {
        startedAt = System.currentTimeMillis()
        farWindowStartedAt = startedAt
        phase = Phase.FAR
        onPhaseChanged(Phase.FAR)
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
            // Süre doldu: elde ne varsa onunla bitir. Kullanıcı CEZALANDIRILMAZ — bu bir
            // ölçüm adımı, bir sınav değil.
            Log.i(TAG, "Süre doldu (uzak=${farPaths.size} yakın=${nearPaths.size}) — elde olanla bitiliyor")
            finish()
            return
        }

        val faceWidth = face.boundingBox.width().toFloat()
        if (faceWidth < 40f) return   // yüz yok sayılacak kadar küçük

        when (phase) {
            Phase.FAR -> {
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return
                if (capture(imageProxy, face, farPaths, "zoom_far")) {
                    lastFrameAt = now
                    farWidths += faceWidth
                    interocular(face)?.let { farIeds += it }
                    onProgress(farPaths.size, FRAMES_PER_WINDOW)
                }

                val windowFull = farPaths.size >= FRAMES_PER_WINDOW
                val windowTimedOut = now - farWindowStartedAt > FAR_WINDOW_TIMEOUT_MS && farPaths.isNotEmpty()
                if (windowFull || windowTimedOut) {
                    farMedianWidth = median(farWidths)
                    phase = Phase.APPROACH
                    onPhaseChanged(Phase.APPROACH)
                    onProgress(0, FRAMES_PER_WINDOW)
                }
            }

            Phase.APPROACH -> {
                if (farMedianWidth > 0f && faceWidth >= farMedianWidth * ZOOM_TARGET_RATIO) {
                    reachedTarget = true
                    phase = Phase.NEAR
                    onPhaseChanged(Phase.NEAR)
                }
            }

            Phase.NEAR -> {
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return
                if (capture(imageProxy, face, nearPaths, "zoom_near")) {
                    lastFrameAt = now
                    nearWidths += faceWidth
                    interocular(face)?.let { nearIeds += it }
                    onProgress(nearPaths.size, FRAMES_PER_WINDOW)
                }
                if (nearPaths.size >= FRAMES_PER_WINDOW) finish()
            }

            Phase.DONE -> return
        }
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
        onPhaseChanged(Phase.DONE)
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
