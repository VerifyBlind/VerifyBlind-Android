package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * DURUŞ + OLAY DİZİSİ — sunucunun istediği mesafelerde duruş, iki durakta olay.
 *
 * ## Neden
 *
 * Parallaks düz yüzeyi eliyor ama iki açığı tek başına kapatmıyor:
 *  1. **Kaynak ayrımı** — derinliği saldırganın kendi kafası, benzerliği kart sahibinin
 *     fotoğrafı sağlayabiliyordu. Parallaks karelerinde kimin yüzü olduğuna bakılmıyordu.
 *  2. **Sarma** — yaklaş/uzaklaş hareketi kayıttan sarılarak üretilebilir; "yarı mesafede göz
 *     kırp" talebi ise kayıttan üretilemez: kaydın TAM O ÖLÇEKTE bir kırpma içermesi gerekir.
 *
 * Sunucu diziyi nonce'tan türetir (4-5 durak, ilki yakın çıpa, her mesafe en az bir kez, iki
 * farklı olay iki farklı durakta). Enclave aynı nonce'tan diziyi yeniden türetip gönderilen
 * kareleri ona göre ÖLÇER: parallaks duruş karelerinden, kimlik AYNI karelerde.
 *
 * ## Bu sınıf ne yapar, ne yapmaz
 *
 * Yalnız **kare toplar ve kullanıcıyı yönlendirir**. Buradaki kontroller (bantta mı, olay oldu
 * mu, yanlış hareket mi) kullanıcı deneyimi içindir — yamalanmış bir istemci hepsini atlayabilir,
 * asıl sınama enclave'de. Bu yüzden eşikler burada cömert, cezalar yerel.
 *
 * ## Kurallar
 *
 * | olay | tepki |
 * |---|---|
 * | henüz yapmadı | bekle, komutu göstermeye devam et |
 * | yanlışını yaptı | yanlış sayacı +1, AYNI durak devam |
 * | duruşta telefon kaydı | **YALNIZ O DURAK** tekrar (≤ [MAX_REDOS_PER_STOP]) |
 * | yüz kadrajdan çıktı / takip numarası değişti | **dizi baştan** (≤ [MAX_RESETS]) |
 * | süre doldu | akış biter |
 *
 * **Kayma kuralı değişti (2026-09-24, kullanıcı onayı).** İlk sürümde kayma tüm diziyi
 * sıfırlıyordu ve sahada şu oldu: duruş, yüz banda GİRDİKTEN 350 ms sonra başlıyordu — kullanıcı
 * hâlâ hareket ederken. Banda girip biraz daha ilerlemek %15 kaymaya yetiyordu (yakın bant
 * kendi başına %17 genişliğinde) ve dizi 1/5'e dönüyordu. Tek koşuda 3 sıfırlama, 80 saniye;
 * bir koşu 19 saniyede "çok fazla hata" ile bitti. Artık duruş ancak yüz bantta HAREKETSİZ
 * kalınca başlıyor ([STILL_WINDOW_MS]) ve kayma yalnız o durağı tekrarlatıyor. Güvenlik kaybı
 * yok: bu kontrol telefonda, yamalanmış istemci zaten atlar.
 *
 * "İlk hareketin doğru olması yeterli." İstemsiz göz kırpma ASLA cezalandırılmaz (dakikada
 * 15-20 kez olur); istenmişse sayılır. Durakta yapılabilecek tekrar sayısı SINIRLI: sınırsız
 * tekrar, saldırgana doğru klibi bulmak için sınırsız deneme demek.
 *
 * ## İz kaydı
 *
 * Her karar ([trace]) kısa bir zaman çizelgesine yazılır: banda giriş/çıkış, duruş, tekrar ve
 * sıfırlama sebepleri, olay sırasında göz/gülümseme/ağız değerleri, yanlış hareketler. Başarıda
 * kanıtla enclave'e (oradan ölçüm tablosuna), başarısızlıkta Sentry'ye gider. İlk saha testinde
 * "neden baştan başladı" sorusunun cevabı hiçbir yerde yoktu — bu kayıt onun için.
 */
class StanceCollector(
    private val cacheDir: File,
    private val stops: List<Stop>,
    private val onGuidance: (Guidance) -> Unit,
    private val onTimeLeft: (Float) -> Unit,
    private val onFailed: (Failure) -> Unit,
    private val onComplete: (Result) -> Unit,
) {

    /**
     * Durağın mesafesi — yüz kutusunun kadraj genişliğindeki oranı.
     *
     * Hedefler parallaks adımıyla aynı: uzak 0,31, yakın 0,62 (açıklık 2,0×), orta geometrik
     * orta 0,44. Bant, kullanıcının tam hedefi tutturmasını beklememek için: enclave mutlak konuma
     * değil duraklar arası ölçek DEĞİŞİMİNE bakıyor. Yakın bandın tavanı çerçeveleme sınırı —
     * kutu 0,70'i aşınca yüzün etrafında ölçülecek arka plan kalmıyor.
     *
     * 🔴 Bant kenarları enclave eşiklerine BAĞLI (PlanarityMeasurementService): en kötü durumda
     * uzak↔yakın 0,60/0,32 = 1,875 (enclave en az 1,5 ister), komşu duraklar 0,41/0,32 ve
     * 0,60/0,47 = 1,28 (enclave 1,25'in altındaki çifti saymaz). Bantları genişletmek meşru
     * kullanıcıyı "ölçülemedi" reddine iter — önce oradaki sabitlere bak.
     */
    enum class Position(val code: Int, val target: Float, val min: Float, val max: Float) {
        FAR(1, 0.31f, 0.22f, 0.32f),
        MID(2, 0.438f, 0.41f, 0.47f),
        NEAR(3, 0.62f, 0.60f, 0.70f);

        companion object {
            fun of(code: Int) = values().firstOrNull { it.code == code }
        }
    }

    enum class Event(val code: Int) {
        NONE(0), BLINK(1), SMILE(2), MOUTH_OPEN(3), DOUBLE_BLINK(4);

        companion object {
            fun of(code: Int) = values().firstOrNull { it.code == code }
        }
    }

    data class Stop(val position: Position, val event: Event)

    enum class Phase {
        /** Hedef mesafeye git ve orada dur. */
        MOVE,

        /** Olaysız durakta duruş — iki kare arası bekleme. */
        HOLD,

        /** İstenen olay bekleniyor. */
        EVENT,

        /** Olay yapıldı, ikinci duruş karesi için kısa bekleme. */
        AFTER_EVENT,

        /** Yakın çıpada arka plan desensiz — kullanıcı yer değiştirmeli. */
        BACKGROUND_POOR,

        DONE,
    }

    data class Guidance(
        val phase: Phase,
        val stopIndex: Int,
        val stopCount: Int,
        val stop: Stop,
        /** +1 = yaklaştır (yüz büyümeli), −1 = uzaklaştır, 0 = bantta. */
        val direction: Int = 0,
        /** Şu anki yüz/kadraj oranı — mesafe göstergesinin işareti. Bilinmiyorsa −1. */
        val fraction: Float = -1f,
        /** Bantta ama henüz sabit değil — "sabit tutun" demenin zamanı. */
        val settling: Boolean = false,
        /** Olaydan önce yüz gevşemeli (gülümseme/ağız açık başlanmış ya da yanlış olay sonrası). */
        val needsRelax: Boolean = false,
        /** Az önce yapılan YANLIŞ olay — bir kez gösterilir. */
        val wrong: Event? = null,
        /** Dizi az önce baştan başladıysa sebebi — bir kez gösterilir. */
        val resetReason: String? = null,
        /** Durak az önce tekrara alındıysa (telefon kaydı) — bir kez gösterilir. */
        val redo: Boolean = false,
        /** Çift kırpmada kaçıncı kırpma tuttu. */
        val eventCount: Int = 0,
        /** Durak ya da olay tamamlandı — onay sesi için. */
        val stepDone: Boolean = false,
    )

    /** Başarısızlık → sunucunun sabit sebep kümesindeki karşılığı. */
    enum class Failure(val flowReason: String) {
        TIMEOUT_MOVE("timeout_gesture"),
        TIMEOUT_EVENT("timeout_gesture"),
        TOO_MANY_WRONG("too_many_errors"),
        TOO_MANY_RESETS("too_many_errors"),
        TOO_MANY_REDOS("too_many_errors"),
    }

    data class StopResult(
        val holdPaths: List<String>,
        val eventPaths: List<String>,
        val faceFraction: Float,
        val attempts: Int,
    )

    data class Result(
        val stops: List<StopResult>,
        /** İlk UZAK durakta, eski parallaks ölçüsüyle (kalibre edilmiş). */
        val bgTexture: Float?,
        /** Yakın çıpada — erken uyarının baktığı sayı, eşiği henüz kalibre değil. */
        val bgTextureNear: Float?,
        val elapsedMs: Int,
        val resets: Int,
        /** Durak tekrarı sayısı (telefon kaydı). */
        val redos: Int,
        val wrongEvents: Int,
        /** Yüz kaybolmadan değişen takip numarası sayısı — sıfırlama kuralı bu dağılımla kalibre edilecek. */
        val trackingChanges: Int,
        /** Karar zaman çizelgesi — bkz. sınıf belgesi. */
        val trace: String,
    )

    companion object {
        private const val TAG = "Stance"

        /**
         * 🔴 Duruş ancak yüz bantta BU KADAR süre HAREKETSİZ kalınca başlar.
         *
         * İlk sürümde banda girmek yetiyordu (350 ms) ve kullanıcı hâlâ hareket ederken ilk
         * kare alınıyordu; sonraki her küçük ilerleme "kayma" sayıldı.
         */
        private const val STILL_WINDOW_MS = 500L

        /** Hareketsizlik penceresinde yüz genişliğinin oynayabileceği pay (el titremesi + ML Kit gürültüsü). */
        private const val STILL_TOLERANCE = 0.06f

        /** Olaysız durakta iki duruş karesi arası. Parallaks için 900 ms yetiyordu. */
        private const val HOLD_MS = 900L

        /** Olaydan sonra ikinci duruş karesine kadar. */
        private const val AFTER_EVENT_MS = 600L

        /**
         * Duruşta yüz genişliğinin oynayabileceği pay — ilk duruş karesine göre. Duruş artık
         * ancak hareketsizken başladığı için %15 gerçek bir kayma demek.
         */
        private const val HOLD_TOLERANCE = 0.15f

        /** Bir mesafeye ulaşmak için süre. */
        const val MOVE_TIMEOUT_MS = 15_000L

        /** İstenen olayı yapmak için süre (yanlış hareketten sonra yeniden başlar). */
        const val EVENT_TIMEOUT_MS = 12_000L

        /** Yüz bu kadar kayıp kalırsa dizi baştan. Mesafe değişirken kısa kayıplar olabiliyor. */
        private const val FACE_LOST_MS = 1_200L

        /** Takip numarası değişiminin sıfırlaması için yüzün en az bu kadar kaybolmuş olması. */
        private const val TRACKING_GAP_MS = 300L

        /** Dizinin kaç kez baştan başlayabileceği (yüz kaybı). */
        private const val MAX_RESETS = 3

        /** Tek durağın kaç kez tekrarlanabileceği (telefon kaydı). */
        private const val MAX_REDOS_PER_STOP = 4

        /** Toplam yanlış olay bütçesi — eski jest akışıyla aynı. */
        private const val MAX_WRONG = 5

        /** Tek durakta yanlış olay sınırı — tekrar sınırı UX değil GÜVENLİK parametresi. */
        private const val MAX_WRONG_PER_STOP = 3

        /**
         * Kapalı göz eşiği. 0,15 → 0,20 (2026-09-24): analiz ~7 kare/sn çalışıyor ve 100-150 ms'lik
         * bir kırpma çoğu zaman tam kapanma anında değil, yarı kapalıyken örnekleniyor.
         */
        private const val EYE_CLOSED = 0.20f
        private const val EYE_OPEN = 0.5f
        private const val SMILE_ON = 0.8f
        private const val SMILE_NEUTRAL = 0.4f

        /**
         * AĞIZ AÇIKLIĞI — ML Kit dudak KONTURUNDAN: iç dudak kenarları arası / iç ağız genişliği
         * (LivenessAnalyzer.innerLipOpen). Kapalı ağızda ~0.
         *
         * **İki başarısız sürümden sonra (2026-09-24 saha testleri):**
         *  1. Burun tabanı → alt dudak NOKTASI, oran olarak, nötr = en küçük değer: göz kırparken
         *     "ağzınızı açtınız" geldi; "açıldı" diye alınan karede enclave ağzı kapalı ölçtü.
         *  2. Alt dudak noktasının ağız köşeleri hattına uzaklığı, nötr = ortanca: ağız GERÇEKTEN
         *     açılınca değer −0,10'a DÜŞTÜ ve ML Kit gülümseme olasılığı 0,82'ye çıktı. Komut yalnız
         *     somurtarak geçilebildi. ML Kit'in yüz NOKTALARI çene açılmasını izlemiyor.
         *
         * Ağız açık = en az [MOUTH_OPEN_MIN] VE nötrden en az [MOUTH_OPEN_DELTA] fazla. Nötr değer
         * bantta durulurken toplanan ölçümlerin ORTANCASI.
         *
         * ⚠️ Eşikler KALİBRE DEĞİL; olay sırasındaki değerler iz kaydına yazılıyor ("lip=").
         */
        private const val MOUTH_OPEN_MIN = 0.20f
        private const val MOUTH_OPEN_DELTA = 0.15f
        private const val MOUTH_RELAX_DELTA = 0.08f

        /** Nötr ağız ölçüsü için bantta tutulan en fazla örnek. */
        private const val MOUTH_SAMPLES = 25

        /** Çift kırpmada iki kırpma arası en fazla. Tek kırpma ardından sessizlik = istemsiz. */
        private const val DOUBLE_BLINK_WINDOW_MS = 2_000L

        /** Yakın çıpada desensizlik uyarısında kullanıcıya tanınan düzeltme süresi. */
        private const val BG_POOR_GRACE_MS = 8_000L
        private const val BG_RECOVERY_SAMPLES = 3
        private const val BG_STABLE_WIDTH_TOLERANCE = 0.08f

        /** Uzak karede kalibrasyon ölçüsü; yakında yüz kadrajı doldurduğu için daha dar dışlama. */
        private const val TEXTURE_GROW_FAR = 1.6f
        private const val TEXTURE_GROW_NEAR = 1.15f

        private const val OUTPUT_LONG_EDGE = 480
        private const val JPEG_QUALITY = 85
        private const val MIN_FACE_PX = 30f

        /** İz kaydının tavanı — yük ve ölçüm satırı şişmesin. */
        const val MAX_TRACE_CHARS = 3_500

        /** Olay sırasında sinyal örneği sıklığı (iz kaydı için). */
        private const val EVENT_SAMPLE_MS = 300L
    }

    private class StopCapture {
        val hold = mutableListOf<String>()
        val events = mutableListOf<String>()
        var faceFraction = 0f
        var wrong = 0
        var redos = 0
    }

    private val lock = Any()

    private var phase = Phase.MOVE
    private var index = 0
    private var captured = List(stops.size) { StopCapture() }

    private var startedAt = 0L
    private var stopStartedAt = 0L
    private var inBand = false
    private var holdWidth = 0f
    private var holdStartedAt = 0L
    private var eventStartedAt = 0L
    private var afterEventAt = 0L
    private var lastFaceAt = 0L
    private var trackingId: Int? = null
    private var seq = 0

    /** Bantta görülen (zaman, yüz genişliği) örnekleri — hareketsizlik penceresi. */
    private val stillSamples = ArrayDeque<Pair<Long, Float>>()

    private var resets = 0
    private var redos = 0
    private var wrongEvents = 0

    /** Kesintisiz algılamada takip numarası kaç kez değişti — YALNIZ ÖLÇÜM. */
    private var trackingChanges = 0

    // Olay algılama
    private val mouthSamples = ArrayList<Float>()
    private var mouthNeutral: Float? = null
    private var smileArmed = false
    private var rearmRequired = false
    private var eyesClosed = false
    private var blinkCount = 0
    private var firstBlinkAt = 0L
    private var lastSampleAt = 0L

    /** Olay sırasında işlenen kare sayısı — kare hızını iz kaydına yazmak için. */
    private var eventFrames = 0

    // Arka plan
    private var bgTexture: Float? = null
    private var bgTextureNear: Float? = null
    private var bgWaived = false
    private var bgPoorSince = 0L
    private var bgLastSampleAt = 0L
    private var bgGoodStreak = 0
    private var bgLastWidth = 0f

    private val trace = StringBuilder()

    val isActive: Boolean get() = synchronized(lock) { phase != Phase.DONE }

    /** Ekranda gösterilecek tamamlanan durak sayısı. */
    val completedStops: Int get() = synchronized(lock) { index }

    /**
     * Bu karede dudak konturu ölçülsün mü — yalnız ağız açma durağında (bantta nötr toplanırken
     * ve olay sırasında). İkinci dedektör kare hızını düşürür; diğer olaylarda çalışmamalı.
     */
    val wantsContour: Boolean
        get() = synchronized(lock) {
            (phase == Phase.MOVE || phase == Phase.EVENT) &&
                index < stops.size && stops[index].event == Event.MOUTH_OPEN
        }

    /**
     * Olay bekleniyor — çağıran bu sırada ağır işleri (selfie adayı, ArcFace) ERTELEMELİ.
     *
     * ML Kit sonucu ana iş parçacığında işleniyor ve sonraki kare ancak bu kare kapanınca geliyor;
     * her ağır iş kare hızını düşürür. Sahada çift kırpmanın ikincisi kaçtı: ilk kırpmanın karesi
     * yazılırken ve selfie adayı hesaplanırken 100-150 ms'lik ikinci kırpma arada kalıyordu.
     */
    val quietPhase: Boolean get() = synchronized(lock) { phase == Phase.EVENT }

    /** Şu ana kadarki iz kaydı — başarısızlıkta Sentry'ye gider. */
    val traceText: String get() = synchronized(lock) { trace.toString() }

    fun start() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        startedAt = now
        stopStartedAt = now
        phase = Phase.MOVE
        index = 0
        trace("start ${describe()}")
        guide(Phase.MOVE)
    }

    /**
     * Kare akışı. Çağıran [ImageProxy]'yi kapatmaya devam eder — bu sınıf yalnız okur.
     *
     * @param lipOpen bu karenin iç dudak açıklığı ([wantsContour] true iken ölçülür), yoksa null.
     * @return bu karede olay onaylandıysa onaylanan olay (gülümseme karesi ölçümü için).
     */
    fun offer(imageProxy: ImageProxy, face: Face, lipOpen: Float? = null): Event? = synchronized(lock) {
        if (phase == Phase.DONE) return null
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastFaceAt > 0) now - lastFaceAt else 0L
        lastFaceAt = now

        val w = face.boundingBox.width().toFloat()
        if (w < MIN_FACE_PX) return null

        // ML Kit kutusu DÖNDÜRÜLMÜŞ görüntü uzayında; kadraj genişliği de aynı uzayda alınmalı.
        val rot = imageProxy.imageInfo.rotationDegrees
        val frameW = (if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width).toFloat()
        if (frameW <= 0f) return null
        val fraction = w / frameW

        // 🔴 Takip numarası değişti ve yüz GERÇEKTEN kaybolmuştu → aynı kişi olduğu
        // kanıtlanamaz, dizi baştan. Kesintisiz algılamada değişen numara SIFIRLAMAZ, yalnız
        // sayılır: ML Kit'in numarayı kendiliğinden yenileme sıklığı ölçülmedi. Güvenlik kaybı
        // yok — kaynak değişimini enclave'in kimlik kapısı yakalıyor.
        face.trackingId?.let { id ->
            val previous = trackingId
            trackingId = id
            if (previous != null && previous != id) {
                if (gap >= TRACKING_GAP_MS && hasCaptures()) {
                    reset("tracking", now)
                    return null
                }
                trackingChanges++
                trace("track+ gap=${gap}ms")
            }
        }

        return when (phase) {
            Phase.MOVE -> { handleMove(imageProxy, face, w, fraction, lipOpen, now); null }
            Phase.HOLD -> { handleHold(imageProxy, face, w, fraction, now); null }
            Phase.EVENT -> handleEvent(imageProxy, face, w, fraction, lipOpen, now)
            Phase.AFTER_EVENT -> { handleAfterEvent(imageProxy, face, w, fraction, now); null }
            Phase.BACKGROUND_POOR -> { handleBackgroundPoor(imageProxy, face, w, now); null }
            Phase.DONE -> null
        }
    }

    /**
     * Kare akışından BAĞIMSIZ saat — çağıran düzenli aralıkla çağırır.
     *
     * [offer] yalnız yüz bulunan karede çalışır; yüz kaybolursa süre de, kayıp tespiti de
     * işlemez ve kullanıcı ekranda kilitli kalırdı (parallaks adımında sahada yaşandı).
     */
    fun tick() = synchronized(lock) {
        if (phase == Phase.DONE) return
        val now = SystemClock.elapsedRealtime()

        if (phase == Phase.BACKGROUND_POOR) {
            if (now - bgPoorSince >= BG_POOR_GRACE_MS) waiveBackground(now)
            return
        }

        if (lastFaceAt > 0 && now - lastFaceAt > FACE_LOST_MS && hasCaptures()) {
            reset("face_lost", now)
            return
        }

        val (limit, since) = when (phase) {
            Phase.EVENT -> EVENT_TIMEOUT_MS to eventStartedAt
            else -> MOVE_TIMEOUT_MS to stopStartedAt
        }
        val left = 1f - (now - since).toFloat() / limit
        onTimeLeft(left.coerceIn(0f, 1f))
        if (left <= 0f) fail(if (phase == Phase.EVENT) Failure.TIMEOUT_EVENT else Failure.TIMEOUT_MOVE)
    }

    fun abandon() = synchronized(lock) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        trace("abandon")
        deleteAll()
    }

    // ── Aşamalar ─────────────────────────────────────────────────────────────

    private fun handleMove(imageProxy: ImageProxy, face: Face, w: Float, fraction: Float, lipOpen: Float?, now: Long) {
        val stop = stops[index]
        val pos = stop.position
        val direction = when {
            fraction < pos.min -> +1
            fraction > pos.max -> -1
            else -> 0
        }

        if (direction != 0) {
            if (inBand) trace("s$index out f=${f2(fraction)}")
            inBand = false
            stillSamples.clear()
            mouthSamples.clear()
            guide(Phase.MOVE, direction = direction, fraction = fraction)
            return
        }

        if (!inBand) {
            inBand = true
            trace("s$index in f=${f2(fraction)}")
        }

        // Hareketsizlik penceresi: son STILL_WINDOW_MS içindeki genişlikler.
        stillSamples.addLast(now to w)
        while (stillSamples.isNotEmpty() && stillSamples.first().first < now - STILL_WINDOW_MS)
            stillSamples.removeFirst()
        lipOpen?.let {
            mouthSamples += it
            if (mouthSamples.size > MOUTH_SAMPLES) mouthSamples.removeAt(0)
        }

        val windowCovered = stillSamples.size >= 3 &&
            now - stillSamples.first().first >= STILL_WINDOW_MS * 0.8
        val widths = stillSamples.map { it.second }
        val spread = if (widths.isEmpty()) 1f else (widths.max() - widths.min()) / widths.min()
        val still = windowCovered && spread <= STILL_TOLERANCE

        guide(Phase.MOVE, direction = 0, fraction = fraction, settling = !still)
        if (!still) return

        // İlk duruş karesi. Yakın çıpada doku da ölçülür; ilk uzak durakta kalibrasyon ölçüsü.
        val grow = when {
            index == 0 && bgTextureNear == null -> TEXTURE_GROW_NEAR
            pos == Position.FAR && bgTexture == null -> TEXTURE_GROW_FAR
            else -> null
        }
        val (path, texture) = capture(imageProxy, face, "h${index}a", grow) ?: return
        captured[index].hold += path
        captured[index].faceFraction = fraction
        holdWidth = w
        holdStartedAt = now
        mouthNeutral = median(mouthSamples)
        trace("s$index holdA f=${f2(fraction)} spread=${f2(spread)} mouth0=${mouthNeutral?.let { f2(it) } ?: "-"}")

        if (texture != null) {
            if (grow == TEXTURE_GROW_NEAR) bgTextureNear = texture else bgTexture = texture
            trace("bg ${if (grow == TEXTURE_GROW_NEAR) "near" else "far"}=${"%.1f".format(Locale.US, texture)}")
        }

        // 🔴 Desensiz arka plan YAKIN ÇIPADA yakalanır — kullanıcı diziyi bitirip sonunda
        // reddedilmesin. Sunucu artık ölçülemeyen akışı reddediyor; erken söylemek şart.
        if (index == 0 && grow == TEXTURE_GROW_NEAR && !bgWaived &&
            texture != null && texture < ParallaxCollector.MIN_BACKGROUND_TEXTURE) {
            enterBackgroundPoor(now)
            return
        }

        if (stop.event == Event.NONE) {
            phase = Phase.HOLD
            guide(Phase.HOLD, fraction = fraction)
        } else {
            beginEvent(face, fraction, now)
        }
    }

    private fun handleHold(imageProxy: ImageProxy, face: Face, w: Float, fraction: Float, now: Long) {
        if (drifted(w)) { redoStop(w, now); return }
        if (now - holdStartedAt < HOLD_MS) return
        val (path, _) = capture(imageProxy, face, "h${index}b", null) ?: return
        captured[index].hold += path
        nextStop(now)
    }

    private fun beginEvent(face: Face, fraction: Float, now: Long) {
        phase = Phase.EVENT
        eventStartedAt = now
        lastSampleAt = 0L
        eventFrames = 0
        blinkCount = 0
        eyesClosed = eyesClosedNow(face)
        val smile = face.smilingProbability ?: 0f
        smileArmed = smile < SMILE_NEUTRAL
        // Gülümseyerek başlanan gülümseme "yeni" bir hareket değildir.
        rearmRequired = !smileArmed && stops[index].event == Event.SMILE
        trace("s$index ev ${stops[index].event.name.lowercase(Locale.US)} sm=${f2(smile)}")
        guide(Phase.EVENT, fraction = fraction, needsRelax = rearmRequired)
    }

    private fun handleEvent(imageProxy: ImageProxy, face: Face, w: Float, fraction: Float, lipOpen: Float?, now: Long): Event? {
        if (drifted(w)) { redoStop(w, now); return null }
        eventFrames++

        val demanded = stops[index].event
        val closed = eyesClosedNow(face)
        val open = eyesOpenNow(face)
        val smile = face.smilingProbability ?: 0f
        // Ağız yalnız ağız açma durağında ölçülüyor (kontur); diğer olaylarda null.
        if (mouthNeutral == null && lipOpen != null) mouthNeutral = lipOpen
        val neutral = mouthNeutral
        val mouthOpen = lipOpen != null && lipOpen >= MOUTH_OPEN_MIN &&
            (neutral == null || lipOpen - neutral >= MOUTH_OPEN_DELTA)
        val mouthRelaxed = lipOpen == null || neutral == null || lipOpen - neutral <= MOUTH_RELAX_DELTA
        if (smile < SMILE_NEUTRAL) smileArmed = true
        val smileRise = smileArmed && smile > SMILE_ON

        // Olay sırasında sinyal örneği — eşikleri kalibre etmenin tek yolu.
        if (now - lastSampleAt >= EVENT_SAMPLE_MS) {
            lastSampleAt = now
            trace("e ${f2(face.leftEyeOpenProbability)}/${f2(face.rightEyeOpenProbability)} " +
                "sm=${f2(smile)} lip=${f2(lipOpen)}")
        }

        // Kapanış kenarı: yeni bir kırpma, açık → kapalı geçişidir.
        val closing = closed && !eyesClosed
        eyesClosed = when {
            closed -> true
            open -> false
            else -> eyesClosed
        }

        if (rearmRequired) {
            if (smile < SMILE_NEUTRAL && mouthRelaxed) {
                rearmRequired = false
                smileArmed = true
                trace("s$index rearmed")
                guide(Phase.EVENT, fraction = fraction)
            }
            return null
        }

        // Çift kırpmada ilk kırpmanın ardından sessizlik: istemsiz kırpmaydı, sessizce sıfırla.
        if (demanded == Event.DOUBLE_BLINK && blinkCount == 1 && now - firstBlinkAt > DOUBLE_BLINK_WINDOW_MS) {
            dropEventFrames()
            blinkCount = 0
            trace("s$index dbl timeout")
            guide(Phase.EVENT, fraction = fraction)
        }

        // 1) İstenen olay önce: aynı karede başka bir şey de olsa istenen yapıldıysa GEÇER.
        val satisfied = when (demanded) {
            Event.BLINK -> closing
            Event.DOUBLE_BLINK -> closing
            Event.SMILE -> smileRise
            Event.MOUTH_OPEN -> mouthOpen
            Event.NONE -> false
        }
        if (satisfied) {
            val (path, _) = capture(imageProxy, face, "e${index}_${captured[index].events.size}", null)
                ?: return null
            captured[index].events += path
            if (demanded == Event.DOUBLE_BLINK) {
                blinkCount++
                if (blinkCount == 1) {
                    firstBlinkAt = now
                    trace("s$index dbl 1/2")
                    guide(Phase.EVENT, fraction = fraction, eventCount = 1)
                    return null
                }
            }
            val secs = (now - eventStartedAt).coerceAtLeast(1L) / 1000f
            trace("s$index ok ${demanded.name.lowercase(Locale.US)} sm=${f2(smile)} lip=${f2(lipOpen)} " +
                "fps=${String.format(Locale.US, "%.1f", eventFrames / secs)}")
            phase = Phase.AFTER_EVENT
            afterEventAt = now
            guide(Phase.AFTER_EVENT, fraction = fraction, stepDone = true)
            return demanded
        }

        // 2) Yanlış olay — yalnız GÜVENİLİR biçimde ayırt edilebilen KASITLI hareketler.
        //
        //  - Göz kırpma asla yanlış sayılmaz (istemsiz).
        //  - Ağız açma istenirken gülümseme yanlış SAYILMAZ: ML Kit açık ağzı gülümseme sanıyor
        //    (sahada 0,82) ve doğru hareketi yapan kullanıcı "yanlış hareket" alıyordu.
        //  - Ağız açma yalnız ağız açma durağında ölçülüyor (kontur dedektörü kare hızını
        //    düşürür); diğer duraklarda yanlış hareket olarak da aranmıyor.
        val wrong = when (demanded) {
            Event.BLINK, Event.DOUBLE_BLINK -> if (smileRise) Event.SMILE else null
            Event.SMILE, Event.MOUTH_OPEN, Event.NONE -> null
        }
        if (wrong != null) {
            trace("s$index wrong ${wrong.name.lowercase(Locale.US)} sm=${f2(smile)}")
            onWrong(wrong, fraction, now)
        }
        return null
    }

    private fun onWrong(event: Event, fraction: Float, now: Long) {
        wrongEvents++
        captured[index].wrong++
        dropEventFrames()
        blinkCount = 0
        rearmRequired = true
        eventStartedAt = now   // yeni deneme için tam süre
        if (wrongEvents >= MAX_WRONG || captured[index].wrong >= MAX_WRONG_PER_STOP) {
            fail(Failure.TOO_MANY_WRONG)
            return
        }
        guide(Phase.EVENT, fraction = fraction, wrong = event, needsRelax = true)
    }

    private fun handleAfterEvent(imageProxy: ImageProxy, face: Face, w: Float, fraction: Float, now: Long) {
        if (drifted(w)) { redoStop(w, now); return }
        if (now - afterEventAt < AFTER_EVENT_MS) return
        val (path, _) = capture(imageProxy, face, "h${index}b", null) ?: return
        captured[index].hold += path
        nextStop(now)
    }

    /**
     * Telefon duruşta kaydı → YALNIZ BU DURAK baştan. Durağın yanlış olay sayısı korunur
     * (tekrar, yanlış hareket bütçesini sıfırlamanın yolu olmasın).
     */
    private fun redoStop(w: Float, now: Long) {
        redos++
        val stop = captured[index]
        stop.redos++
        trace("s$index redo drift=${f2(abs(w / holdWidth - 1f))}")
        deleteStop(index)
        if (stop.redos > MAX_REDOS_PER_STOP) {
            fail(Failure.TOO_MANY_REDOS)
            return
        }
        phase = Phase.MOVE
        stopStartedAt = now
        inBand = false
        holdWidth = 0f
        stillSamples.clear()
        mouthSamples.clear()
        guide(Phase.MOVE, redo = true)
    }

    private fun enterBackgroundPoor(now: Long) {
        trace("bg poor")
        deleteStop(0)
        phase = Phase.BACKGROUND_POOR
        bgPoorSince = now
        bgGoodStreak = 0
        bgLastWidth = 0f
        guide(Phase.BACKGROUND_POOR)
    }

    /**
     * Kullanıcı yer değiştirdiyse ve doku ÜST ÜSTE ve KARARLI kadrajla eşiği geçtiyse çıpaya
     * dönülür. Tek ölçümle çıkılmaz: telefonu ileri geri oynatmak uyarıyı geçmenin yoluydu
     * (2026-09-17).
     */
    private fun handleBackgroundPoor(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (now - bgPoorSince >= BG_POOR_GRACE_MS) { waiveBackground(now); return }
        if (now - bgLastSampleAt < 500L) return
        bgLastSampleAt = now

        val t = measureTexture(imageProxy, face, TEXTURE_GROW_NEAR) ?: return
        val steady = bgLastWidth > 0f && abs(w - bgLastWidth) <= bgLastWidth * BG_STABLE_WIDTH_TOLERANCE
        bgLastWidth = w
        bgGoodStreak = if (t >= ParallaxCollector.MIN_BACKGROUND_TEXTURE && steady) bgGoodStreak + 1 else 0
        if (bgGoodStreak >= BG_RECOVERY_SAMPLES) {
            trace("bg ok ${"%.1f".format(Locale.US, t)}")
            bgTextureNear = null   // yeni yerde yeniden ölçülsün
            resumeAnchor(now)
        }
    }

    /**
     * Düzeltme süresi doldu — akış DEVAM EDER. Sunucu ölçülemeyen akışı zaten reddediyor;
     * burada tutmanın karşılığı yok, ama kullanıcı uyarıyı gördü. Doku 14'ün altında olsa
     * da ORB eşleşebiliyor (duvar dibi koşusu: doku 12, 23 uyum).
     */
    private fun waiveBackground(now: Long) {
        trace("bg waived")
        bgWaived = true
        resumeAnchor(now)
    }

    private fun resumeAnchor(now: Long) {
        deleteStop(0)
        phase = Phase.MOVE
        index = 0
        stopStartedAt = now
        inBand = false
        stillSamples.clear()
        mouthSamples.clear()
        guide(Phase.MOVE)
    }

    private fun nextStop(now: Long) {
        trace("s$index done")
        index++
        if (index >= stops.size) { finish(now); return }
        phase = Phase.MOVE
        stopStartedAt = now
        inBand = false
        holdWidth = 0f
        stillSamples.clear()
        mouthSamples.clear()
        mouthNeutral = null
        guide(Phase.MOVE, stepDone = true)
    }

    private fun reset(reason: String, now: Long) {
        resets++
        trace("reset $reason")
        deleteAll()
        captured = List(stops.size) { StopCapture() }
        if (resets > MAX_RESETS) {
            fail(Failure.TOO_MANY_RESETS)
            return
        }
        phase = Phase.MOVE
        index = 0
        stopStartedAt = now
        inBand = false
        holdWidth = 0f
        stillSamples.clear()
        mouthSamples.clear()
        mouthNeutral = null
        guide(Phase.MOVE, resetReason = reason)
    }

    private fun fail(failure: Failure) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        trace("fail ${failure.name} s$index")
        Log.i(TAG, "Başarısız: $failure (durak ${index + 1}/${stops.size})")
        deleteAll()
        onFailed(failure)
    }

    private fun finish(now: Long) {
        phase = Phase.DONE
        trace("finish")
        onComplete(
            Result(
                stops = captured.map {
                    StopResult(it.hold.toList(), it.events.toList(), it.faceFraction, it.wrong + 1)
                },
                bgTexture = bgTexture,
                bgTextureNear = bgTextureNear,
                elapsedMs = (now - startedAt).toInt(),
                resets = resets,
                redos = redos,
                wrongEvents = wrongEvents,
                trackingChanges = trackingChanges,
                trace = trace.toString(),
            )
        )
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private fun drifted(w: Float) = holdWidth > 0f && abs(w / holdWidth - 1f) > HOLD_TOLERANCE

    private fun hasCaptures() = captured.any { it.hold.isNotEmpty() || it.events.isNotEmpty() }

    private fun guide(
        phase: Phase,
        direction: Int = 0,
        fraction: Float = -1f,
        settling: Boolean = false,
        needsRelax: Boolean = false,
        wrong: Event? = null,
        resetReason: String? = null,
        redo: Boolean = false,
        eventCount: Int = 0,
        stepDone: Boolean = false,
    ) {
        val i = index.coerceIn(0, stops.size - 1)
        onGuidance(
            Guidance(phase, i, stops.size, stops[i], direction, fraction, settling, needsRelax,
                wrong, resetReason, redo, eventCount, stepDone)
        )
    }

    /** İz kaydına bir satır: "zaman(s) mesaj;". Tavanı aşarsa bir kez "…" konur. ASCII tutulur. */
    private fun trace(message: String) {
        if (trace.length >= MAX_TRACE_CHARS) return
        val t = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt) / 100 else 0
        val line = "${t / 10}.${t % 10} $message;"
        if (trace.length + line.length > MAX_TRACE_CHARS) { trace.append("..."); return }
        trace.append(line)
        Log.d(TAG, line)
    }

    private fun describe(): String = stops.joinToString(",") { s ->
        val p = when (s.position) { Position.FAR -> "F"; Position.MID -> "M"; Position.NEAR -> "N" }
        if (s.event == Event.NONE) p else "$p+${s.event.name.lowercase(Locale.US)}"
    }

    private fun f2(v: Float?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun eyesClosedNow(face: Face): Boolean {
        val l = face.leftEyeOpenProbability ?: return false
        val r = face.rightEyeOpenProbability ?: return false
        return l < EYE_CLOSED && r < EYE_CLOSED
    }

    private fun eyesOpenNow(face: Face): Boolean {
        val l = face.leftEyeOpenProbability ?: return false
        val r = face.rightEyeOpenProbability ?: return false
        return l > EYE_OPEN && r > EYE_OPEN
    }

    private fun dropEventFrames() {
        captured[index].events.forEach { runCatching { File(it).delete() } }
        captured[index].events.clear()
    }

    private fun deleteStop(i: Int) {
        captured[i].hold.forEach { runCatching { File(it).delete() } }
        captured[i].events.forEach { runCatching { File(it).delete() } }
        captured[i].hold.clear()
        captured[i].events.clear()
    }

    private fun deleteAll() {
        for (i in captured.indices) deleteStop(i)
    }

    /**
     * TAM kareyi uzun kenarı [OUTPUT_LONG_EDGE] olacak şekilde küçültüp diske yazar; istenirse
     * aynı tam çözünürlüklü karede dokuyu da ölçer.
     *
     * ⚠️ Kırpma YOK: parallaks yüz ile arka plan arasındaki farkı ölçer.
     */
    private fun capture(imageProxy: ImageProxy, face: Face, name: String, textureGrow: Float?): Pair<String, Float?>? {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        var scaledRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap() ?: return null
            srcRef = bmp
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val ratio = OUTPUT_LONG_EDGE.toFloat() / max(bmp.width, bmp.height)

            val texture: Float?
            val scaled: Bitmap
            if (textureGrow != null) {
                // Doku yüz kutusu koordinatlarında (döndürülmüş, tam çözünürlük) ölçülür.
                val m = Matrix().apply { postRotate(rotation) }
                val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                fullRef = full
                texture = BackgroundTexture.measure(full, face.boundingBox, 1f, textureGrow)
                val tw = (full.width * ratio).toInt().coerceAtLeast(1)
                val th = (full.height * ratio).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(full, tw, th, true)
            } else {
                // 🔴 Hızlı yol: küçültme ve döndürme TEK adımda, tam çözünürlüklü ara bitmap YOK.
                // Olay karesi ana iş parçacığında yazılıyor; tam çözünürlükte döndürmek (1080p,
                // 8 MB) sonraki kareleri geciktiriyor ve çift kırpmanın ikincisini kaçırtıyordu.
                val m = Matrix().apply { postScale(ratio, ratio); postRotate(rotation) }
                scaled = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                texture = null
            }
            scaledRef = scaled

            val f = File(cacheDir, "stance_${name}_${seq++}.jpg")
            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            f.absolutePath to texture
        } catch (e: Exception) {
            Log.w(TAG, "Kare yazılamadı: ${e.message}")
            trace("capture fail")
            null
        } finally {
            scaledRef?.let { if (it !== fullRef) it.recycle() }
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    private fun measureTexture(imageProxy: ImageProxy, face: Face, grow: Float): Float? {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap() ?: return null
            srcRef = bmp
            val m = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            fullRef = full
            BackgroundTexture.measure(full, face.boundingBox, 1f, grow)
        } catch (e: Exception) {
            null
        } finally {
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }
}
