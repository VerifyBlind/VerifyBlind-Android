package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * PARALLAKS KANITI — "kameranın önünde bir SAHNE mi var, yoksa bir YÜZEY mi?"
 *
 * ## Fikir
 *
 * Telefon uzaklaşıp yaklaşırken **yüz ve arka plan farklı derinlikte** olduğu için farklı
 * oranda büyür. Düz bir yüzeyde (TV, monitör, baskı) yüz ve arka plan **aynı düzlemdedir**
 * ve aynı oranda büyür.
 *
 * ```
 * B = yüz ölçeği / arka plan ölçeği
 *   düz yüzey  → 1,00    ölçülen bant 0,997-1,025 (iki farklı cihazda)
 *   gerçek yüz → 1,38-1,59
 * ```
 *
 * 1,00 bir ölçüm değil **fizik sabitidir**: tek düzlemde iki bölge de aynı dönüşüme uğrar.
 * Saldırgan ekranı eğse, kaydırsa, büyütse de bunu değiştiremez.
 *
 * ## Bu sınıf ne yapar, ne yapmaz
 *
 * Yalnız **kare toplar**. Ölçümü enclave yapar — noktaları burada çıkarıp sunucuya sayı
 * göndermek, tüm sınamayı yamalanabilir bir istemci hesabına emanet ederdi.
 *
 * ⚠️ **Kareler TAM KARE olmak zorunda**, yüz kırpması değil: ölçülen şey yüz ile ARKA PLAN
 * arasındaki fark. Eski [ZoomProofCollector] yüz kırpması gönderiyordu ve arka planı hiç
 * taşımıyordu.
 *
 * ## Neden önce UZAKLAŞ
 *
 * Kullanıcı kameraya yakın başlarsa yaklaşabileceği aralık daralır ve sinyal zayıflar.
 * Önce uzaklaştırmak en uzak/en yakın oranını büyütür — meşru bandı ekran bandından
 * uzaklaştıran tek şey bu oran.
 *
 * ## Neden DÖRT mesafe
 *
 * Ardışık kareler arası ölçek ~1,25× kalır; ORB'un ölçek takibi 2,3× üstünde bozuluyor,
 * tek sıçrama bu sınırı aşardı. Ayrıca tek sayı yerine bir **eğri** elde edilir: düz yüzeyde
 * HER çiftte 1,00, gerçek yüzde her çiftte >1. Saldırganın tek bir değeri değil, tutarlı bir
 * ilerlemeyi taklit etmesi gerekir.
 *
 * ## 🔴 Arka plan dokusu ZORUNLU — ve bu güvenliğin direği
 *
 * Dokusuz arka planda ölçüm imkânsız (düz duvar: iki cihazda da 0/8). Saldırganın düz
 * arka planlı fotoğrafı da aynı sonucu verir; ikisi ayırt edilemez.
 *
 * Çözüm zinciri: doku ZORUNLU tutulursa saldırgan da dokulu bir arka plan sunmak zorunda
 * kalır — ve sunduğu anda o arka plan ekranla aynı düzlemde olur, B ≈ 1,00 çıkar, yakalanır.
 *
 * Kontrol **en başta**, uzak karede yapılır: kullanıcı bütün hareketi yapmadan önce uyarılır
 * ki ortamı düzeltebilsin.
 */
class ParallaxCollector(
    private val cacheDir: File,
    private val onGuidance: (Phase, progress: Float) -> Unit,
    private val onBackgroundPoor: () -> Unit,
    private val onComplete: (Result) -> Unit,
) {

    enum class Phase {
        /** "Telefonu uzaklaştırın" — en uzak referans kare aranıyor. */
        RETREAT,

        /** Arka planda yeterli doku yok; kullanıcı yer değiştirmeli. */
        BACKGROUND_POOR,

        /** "Yavaşça yaklaştırın" — ara mesafelerde kare toplanıyor. */
        APPROACH,

        DONE,
    }

    data class Result(
        /** En uzaktan en yakına, mesafeye göre SIRALI tam kareler. */
        val framePaths: List<String>,
        /** Her karenin yüz genişliği (px) — enclave'in ölçtüğüyle kıyas için, DOĞRULANMAZ. */
        val faceWidths: List<Float>,
        /** Uzak karede ölçülen arka plan doku enerjisi — eşik kalibrasyonu için. */
        val backgroundTexture: Float,
        /** En yakın/en uzak yüz genişliği oranı. Küçükse sinyalin anlamı zayıftır. */
        val spanRatio: Float,
        val elapsedMs: Int,
        /** Dört mesafenin dördü de toplanabildi mi. */
        val complete: Boolean,
    )

    companion object {
        private const val TAG = "Parallax"

        /** Kaç mesafede kare toplanır (en uzak dahil). */
        const val FRAME_COUNT = 4

        /**
         * Hedef açıklık: en yakın kare, en uzak karenin bu katı olmalı.
         *
         * Ölçüm: arka plan 83 cm'deyken 2,1× yaklaşmada oran 1,26 (güvenli), 1,3× yaklaşmada
         * 1,08 (ekran bandında). Yani açıklık sinyalin kendisi — dayatılmazsa ölçüm anlamsız.
         */
        const val TARGET_SPAN = 2.0f

        /**
         * Uzaklaşmanın KABUL ölçüsü: yüz, başlangıç genişliğinin bu katı kadar küçülmeli.
         *
         * 🔴 Neden şart: ilk sürüm yalnız "genişlik düşüyor mu" diye bakıyordu. Kullanıcı
         * komutu yanlış anlayıp YAKLAŞIRSA genişlik hiç düşmez, bekleme sayacı tazelenmez ve
         * adım 1,2 saniye sonra "durulmuş" sayılıp referans kareyi kullanıcının BAŞLADIĞI
         * yakın mesafeden alır. Sahada tam bu oldu.
         */
        const val MIN_RETREAT_FACTOR = 1.22f

        /** Kare, kullanıcının en uzak noktasında mı alınıyor (geri dönerken değil). */
        private const val AT_MINIMUM_TOLERANCE = 1.08f

        /** En uzak noktada bu kadar durulmalı — hareket bulanıklığı olmasın. */
        private const val RETREAT_SETTLE_MS = 900L

        /** Yumuşak tavan: yeterince uzaklaşamadı ama en uzak noktasındaysa kabul edilir. */
        private const val RETREAT_TIMEOUT_MS = 8_000L

        /** Sert tavan: komut hiç uygulanmadı; eldekiyle devam edilir. */
        private const val RETREAT_HARD_MS = 13_000L

        /**
         * Dokusuz arka planda kullanıcıya tanınan düzeltme süresi.
         *
         * Dolduğunda akış ENGELLENMEZ, devam eder — bugün parallaks hiçbir şeyi reddetmiyor
         * (sinyal henüz hesaplanmıyor), dolayısıyla kullanıcıyı burada tutmanın güvenlik
         * karşılığı YOK. Sahada tersi oldu: ortamını düzeltmeye çalışan kullanıcı bütün
         * süreyi harcadı ve akış SIFIR kareyle "başarılı" göründü.
         */
        private const val BG_POOR_GRACE_MS = 6_000L

        /** Tüm adımın tavanı — arka plan düzeltmede geçen süre DIŞINDA. */
        private const val TOTAL_TIMEOUT_MS = 26_000L

        /**
         * Çağıranın kare akışından bağımsız bekçisi için pencere.
         *
         * Toplayıcının kendi süre kontrolü [offer] içindedir ve [offer] yalnız ML Kit bir yüz
         * bulduğunda çalışır; yüz kadrajdan çıkarsa hiç işlemez. Bekçi her AŞAMA DEĞİŞİMİNDE
         * yeniden kurulur — yoksa arka plan düzeltmeye harcanan süre tüm bütçeyi yer.
         */
        const val WATCHDOG_MS = 16_000L

        /** Kare aralığı — arka arkaya neredeyse aynı kareyi almanın anlamı yok. */
        private const val FRAME_INTERVAL_MS = 80L

        /** Gönderilen karenin uzun kenarı. Nokta hassasiyeti buna bağlı. */
        private const val OUTPUT_LONG_EDGE = 480

        private const val JPEG_QUALITY = 85

        /**
         * Arka plan doku eşiği (gradyan enerjisi).
         *
         * ⚠️ İlk değer TEMKİNLİ ve kalibre EDİLMEDİ. Ölçümde çalışan setlerin Laplacian
         * varyansı 125-420, düşenler ≤75 idi — ama o OpenCV formülü, buradaki değil.
         * Bu yüzden değer HER AKIŞTA loglanıyor; gerçek eşik veriden konacak.
         *
         * Yanlış tarafa hata yapmak: eşik düşükse ölçüm başarısız olur (zararsız), yüksekse
         * meşru kullanıcı gereksiz yere uyarılır (can sıkıcı). Düşük başlıyoruz.
         */
        const val MIN_BACKGROUND_TEXTURE = 18f
    }

    var phase: Phase = Phase.RETREAT
        private set

    val isActive: Boolean get() = phase != Phase.DONE

    private val paths = mutableListOf<String>()
    private val widths = mutableListOf<Float>()

    private var startedAt = 0L

    /** Arka plan düzeltmede harcanan ve toplam bütçeden SAYILMAYAN süre. */
    private var pausedMs = 0L

    /** Uzaklaşma adımının kendi saati — arka plan uyarısından sonra yeniden başlar. */
    private var retreatStartedAt = 0L
    private var bgPoorSince = 0L
    private var lastFrameAt = 0L

    /** Uzaklaşmanın başladığı yüz genişliği — kabul ölçüsü buna göre. */
    private var startWidth = 0f
    private var minFaceWidth = Float.MAX_VALUE
    private var minSeenAt = 0L
    private var farWidth = 0f
    private var bgTexture = 0f

    /** Doku kapısı bir kez esnetildiyse tekrar tetiklenmez — yoksa sonsuz döngü. */
    private var bgGateWaived = false
    private var nextTargetIndex = 1          // 0 = uzak referans, zaten alındı

    fun start() {
        startedAt = System.currentTimeMillis()
        retreatStartedAt = startedAt
        minSeenAt = startedAt
        phase = Phase.RETREAT
        onGuidance(Phase.RETREAT, 0f)
    }

    /** Çağıran [ImageProxy]'yi kapatmaya devam eder — bu sınıf yalnız okur. */
    fun offer(imageProxy: ImageProxy, face: Face) {
        if (phase == Phase.DONE) return
        val now = System.currentTimeMillis()

        if (now - startedAt - pausedMs > TOTAL_TIMEOUT_MS) {
            Log.i(TAG, "Süre doldu — ${paths.size}/$FRAME_COUNT kare ile bitiliyor")
            finish()
            return
        }

        val w = face.boundingBox.width().toFloat()
        if (w < 30f) return

        when (phase) {
            Phase.RETREAT -> handleRetreat(imageProxy, face, w, now)
            Phase.BACKGROUND_POOR -> handleBackgroundPoor(imageProxy, face, w, now)
            Phase.APPROACH -> handleApproach(imageProxy, face, w, now)
            Phase.DONE -> return
        }
    }

    /**
     * En uzak referans kareyi arar — ve bunun için GERÇEKTEN uzaklaşılmasını şart koşar.
     *
     * 🔴 Sahada çıkan arıza: kullanıcı "uzaklaştırın" komutuna yaklaşarak karşılık verdi.
     * Eski kod yalnız genişliğin düşüşünü izlediği için hiçbir şey fark etmedi, 1,2 saniye
     * sonra referansı o YAKIN mesafeden aldı ve hedefe ulaşmak telefonu burna dayamayı
     * gerektirdi (ölçülen açıklık 2,47 — hedef 2,00). Ölçüm çıktı ama dayanıksız: aynı hatayı
     * yapıp yaklaşamayan kullanıcıda hiç sinyal oluşmaz.
     *
     * Üç koşul BİRLİKTE aranır:
     *  1. yüz başlangıca göre gerçekten küçüldü ([MIN_RETREAT_FACTOR] kat),
     *  2. kare kullanıcının EN UZAK olduğu anda alınıyor (geri dönerken değil),
     *  3. o noktada bir an duruldu.
     */
    private fun handleRetreat(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (startWidth <= 0f) {
            startWidth = w
            minFaceWidth = w
            minSeenAt = now
        }
        if (w < minFaceWidth - 1f) {          // hâlâ uzaklaşıyor
            minFaceWidth = w
            minSeenAt = now
        }

        val retreated = minFaceWidth <= startWidth / MIN_RETREAT_FACTOR
        val atMinimum = w <= minFaceWidth * AT_MINIMUM_TOLERANCE
        val settled = now - minSeenAt >= RETREAT_SETTLE_MS
        val elapsed = now - retreatStartedAt

        val accept = when {
            retreated && atMinimum && settled -> true
            // Yumuşak tavan: yeterince uzaklaşamadı (kısa kol, dar oda) ama hiç değilse en
            // uzak noktasında. Açıklık küçük kalırsa enclave "not_approached" yazar.
            elapsed >= RETREAT_TIMEOUT_MS && atMinimum -> true
            // Sert tavan: komut hiç uygulanmadı. Bu adım BAŞARISIZ OLAMAZ, eldekiyle devam.
            elapsed >= RETREAT_HARD_MS -> true
            else -> false
        }

        if (!accept) {
            // 🔴 Yanlış yöne gidene SÖYLENİR. Sahada kullanıcı yaklaştı, ekran hiçbir şey
            // değiştirmedi ve hata sürdü. İlerleme negatifse çağıran "ters yön" gösterir.
            //
            // "Ters yön" = şu an ulaştığın en uzak noktadan DAHA YAKINSIN. Tek kural iki
            // durumu da kapsıyor: hiç uzaklaşmayan da, uzaklaşıp geri gelen de burada yakalanır.
            val progress =
                if (!atMinimum) -1f
                else ((startWidth / minFaceWidth - 1f) / (MIN_RETREAT_FACTOR - 1f)).coerceIn(0f, 1f)
            onGuidance(Phase.RETREAT, progress)
            return
        }

        // En uzak referans kare + arka plan doku kontrolü BURADA — kullanıcı bütün hareketi
        // yapmadan önce, ki ortamı düzeltebilsin.
        val captured = capture(imageProxy, face, w, checkTexture = true)
        if (!captured) return

        if (!bgGateWaived && bgTexture < MIN_BACKGROUND_TEXTURE) {
            Log.i(TAG, "Arka plan dokusu yetersiz: $bgTexture < $MIN_BACKGROUND_TEXTURE")
            paths.removeLastOrNull()?.let { runCatching { File(it).delete() } }
            widths.removeLastOrNull()
            phase = Phase.BACKGROUND_POOR
            bgPoorSince = now
            onGuidance(Phase.BACKGROUND_POOR, 0f)
            onBackgroundPoor()
            // Yeniden denemeye açık: kullanıcı yer değiştirirse uzaklaşma baştan başlar.
            // ⚠️ startedAt SIFIRLANMAZ: toplam süre tek ve dürüst kalsın. Düzeltmede geçen
            // süre pausedMs'e yazılıp bütçeden düşülür.
            startWidth = 0f
            minFaceWidth = Float.MAX_VALUE
            minSeenAt = now
            return
        }

        farWidth = w
        nextTargetIndex = 1
        phase = Phase.APPROACH
        onGuidance(Phase.APPROACH, 0f)
    }

    /**
     * Kullanıcı yer değiştirdikten sonra doku yeterli hale geldiyse akış yeniden başlar —
     * gelmediyse de [BG_POOR_GRACE_MS] sonunda YİNE devam eder.
     *
     * 🔴 Neden engellemiyor: sahada kullanıcı uyarıyı aldı, kalkıp iki ayrı yere geçti ve
     * bütün bütçeyi burada harcadı; bekçi devreye girip akışı SIFIR kareyle bitirdi, ekran
     * "✅" gösterdi. Ne ölçüm ne de kalibrasyon verisi kaldı — üstelik bugün parallaks hiçbir
     * şeyi reddetmediği için o engellemenin güvenlik karşılığı da yoktu. Ölçüm kapı olduğunda
     * bu dal REDDE dönecek; bugünkü görevi dokusuz arka planın maliyetini KAYDETMEK.
     */
    private fun handleBackgroundPoor(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        val waited = now - bgPoorSince

        if (waited >= BG_POOR_GRACE_MS) {
            Log.i(TAG, "Arka plan düzelmedi ($bgTexture) — doku kapısı esnetiliyor, devam")
            bgGateWaived = true
            resumeRetreat(now, waited)
            return
        }

        if (now - lastFrameAt < 500L) return
        lastFrameAt = now
        val t = measureBackgroundTexture(imageProxy, face) ?: return
        bgTexture = t
        if (t >= MIN_BACKGROUND_TEXTURE) {
            Log.i(TAG, "Arka plan düzeldi ($t) — uzaklaşmaya dönülüyor")
            resumeRetreat(now, waited)
        }
    }

    /** Arka plan molasından uzaklaşma adımına dön; molada geçen süre bütçeden düşülür. */
    private fun resumeRetreat(now: Long, waited: Long) {
        pausedMs += waited
        phase = Phase.RETREAT
        retreatStartedAt = now
        startWidth = 0f
        minFaceWidth = Float.MAX_VALUE
        minSeenAt = now
        onGuidance(Phase.RETREAT, 0f)
    }

    private fun handleApproach(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (farWidth <= 0f) return

        val span = w / farWidth
        val progress = ((span - 1f) / (TARGET_SPAN - 1f)).coerceIn(0f, 1f)
        onGuidance(Phase.APPROACH, progress)

        // Hedef mesafeler eşit aralıklı: 1,00 → TARGET_SPAN arası FRAME_COUNT-1 adım.
        val step = (TARGET_SPAN - 1f) / (FRAME_COUNT - 1)
        val needed = 1f + step * nextTargetIndex
        if (span < needed) return
        if (now - lastFrameAt < FRAME_INTERVAL_MS) return

        if (capture(imageProxy, face, w, checkTexture = false)) {
            nextTargetIndex++
            if (paths.size >= FRAME_COUNT) finish()
        }
    }

    fun abandon() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        paths.forEach { runCatching { File(it).delete() } }
        paths.clear()
    }

    /**
     * Kare akışından BAĞIMSIZ bekçinin penceresi doldu — [offer] hiç çağrılmamış olabilir.
     *
     * 🔴 Arka plan molasındayken akış BİTİRİLMEZ. Sahada kullanıcı uyarıyı alıp ortamını
     * düzeltmeye kalktı; yürürken yüz kadrajdan çıktığı için [offer] hiç çalışmadı, mola
     * süresi hiç işlemedi ve bekçi akışı SIFIR kareyle kapattı. Kullanıcının yaptığı iş
     * cezalandırılmış oldu. Bu durumda doku kapısı esnetilir ve ölçüme devam edilir.
     *
     * @return true → toplayıcı hâlâ çalışıyor, bekçi yeniden kurulmalı.
     */
    fun timeoutNow(): Boolean {
        if (phase == Phase.DONE) return false

        if (phase == Phase.BACKGROUND_POOR && !bgGateWaived) {
            Log.i(TAG, "Bekçi arka plan molasında yakaladı — doku kapısı esnetiliyor, devam")
            bgGateWaived = true
            val now = System.currentTimeMillis()
            // Molaya en fazla tanınan süre kadar kredi verilir; gerisi bütçeden düşer ki
            // toplam süre sınırsız büyümesin.
            resumeRetreat(now, minOf(now - bgPoorSince, BG_POOR_GRACE_MS))
            return true
        }

        Log.i(TAG, "Bekçi bitirdi — ${paths.size}/$FRAME_COUNT kare")
        finish()
        return false
    }

    private fun finish() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        // DONE'daki ilerleme = toplanan kare oranı. Çağıran buna bakarak "✅" gösterip
        // göstermeyeceğine karar verir: sıfır kareyle biten bir akışı başarı diye sunmak
        // sahada tam olarak yanlış anlaşıldı.
        onGuidance(Phase.DONE, paths.size.toFloat() / FRAME_COUNT)
        val span = if (widths.size >= 2 && widths.first() > 0f)
            widths.last() / widths.first() else 0f
        onComplete(
            Result(
                framePaths = paths.toList(),
                faceWidths = widths.toList(),
                backgroundTexture = bgTexture,
                spanRatio = span,
                elapsedMs = (System.currentTimeMillis() - startedAt).toInt(),
                complete = paths.size >= FRAME_COUNT,
            )
        )
    }

    /**
     * TAM kareyi uzun kenarı [OUTPUT_LONG_EDGE] olacak şekilde küçültüp diske yazar.
     *
     * ⚠️ Kırpma YOK: ölçülen şey yüz ile arka plan arasındaki fark, arka plan kesilirse
     * ölçülecek bir şey kalmaz.
     */
    private fun capture(imageProxy: ImageProxy, face: Face, faceW: Float, checkTexture: Boolean): Boolean {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        var scaledRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap() ?: return false
            srcRef = bmp
            val m = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            fullRef = full

            if (checkTexture) {
                bgTexture = textureOf(full, face.boundingBox)
                Log.d(TAG, "Arka plan doku enerjisi: $bgTexture")
            }

            val ratio = OUTPUT_LONG_EDGE.toFloat() / max(full.width, full.height)
            val tw = (full.width * ratio).toInt().coerceAtLeast(1)
            val th = (full.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(full, tw, th, true)
            scaledRef = scaled

            val f = File(cacheDir, "parallax_${paths.size}.jpg")
            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            paths += f.absolutePath
            widths += faceW
            lastFrameAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Kare yazılamadı: ${e.message}")
            false
        } finally {
            scaledRef?.let { if (it !== fullRef) it.recycle() }
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    private fun measureBackgroundTexture(imageProxy: ImageProxy, face: Face): Float? {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap() ?: return null
            srcRef = bmp
            val m = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            fullRef = full
            textureOf(full, face.boundingBox)
        } catch (e: Exception) {
            null
        } finally {
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    /**
     * Yüz DIŞINDAKİ bölgenin gradyan enerjisi — "eşleştirilecek desen var mı".
     *
     * Seyrek örnekleme: tam çözünürlükte her pikseli okumak kare başına milyonlarca işlem
     * demek; desen ölçmek için gerek yok.
     */
    private fun textureOf(full: Bitmap, faceBox: Rect): Float {
        val w = full.width
        val h = full.height
        val step = max(2, max(w, h) / 160)
        val grow = 1.6f
        val cx = faceBox.exactCenterX()
        val cy = faceBox.exactCenterY()
        val hw = faceBox.width() * grow / 2f
        val hh = faceBox.height() * grow / 2f

        var sum = 0.0
        var n = 0
        var y = step
        while (y < h - step) {
            var x = step
            while (x < w - step) {
                if (!(x > cx - hw && x < cx + hw && y > cy - hh && y < cy + hh)) {
                    val c = lum(full.getPixel(x, y))
                    val gx = lum(full.getPixel(x + step, y)) - c
                    val gy = lum(full.getPixel(x, y + step)) - c
                    sum += (abs(gx) + abs(gy)).toDouble()
                    n++
                }
                x += step
            }
            y += step
        }
        return if (n < 50) 0f else (sum / n).toFloat()
    }

    private fun lum(p: Int): Int =
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
}
