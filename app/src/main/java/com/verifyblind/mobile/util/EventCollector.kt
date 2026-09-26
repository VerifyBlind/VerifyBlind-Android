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
import kotlin.math.max

/**
 * OLAY DİZİSİ — sunucunun istediği hareketler, tek mesafede, istenen sırayla.
 *
 * ## Neden
 *
 * Sunucu diziyi nonce'tan türetir (dört hareketten üçü, hepsi farklı, rastgele sıra). Her hareketten
 * ÖNCE bir nötr kare, hareket ANINDA olay karesi toplanır; enclave aynı nonce'tan diziyi yeniden
 * türetip kareleri ona göre ölçer ve HER karede kart sahibinin yüzünü arar. Kapattığı açık kaynak
 * ayrımı: benzerliği kart sahibinin fotoğrafı, hareketi başka bir yüz sağlayamaz — fotoğraf ise
 * hareket yapamaz.
 *
 * 🔴 Mesafe YOK (2026-09-25). Önceki duruş dizisi uzak/orta/yakın duraklar istiyordu; iki mesafede
 * görüntüden 3B çıkarmak FaceTec patent istemlerine düşüyor. Yüz boyutu kontrolü yalnız KALİTE
 * içindir (çok küçük yüzde göz/ağız okunmuyor) — tek bir aralık, farklı mesafe hedefleri değil.
 *
 * ## Bu sınıf ne yapar, ne yapmaz
 *
 * Yalnız **kare toplar ve kullanıcıyı yönlendirir**. Buradaki kontroller (hareket oldu mu, yanlış
 * hareket mi) kullanıcı deneyimi içindir; asıl sınama enclave'de. Bu yüzden eşikler cömert.
 *
 * ## Kurallar
 *
 * | olay | tepki |
 * |---|---|
 * | yüz çerçevede değil / çok küçük / çok büyük | hareket istenmez, ne yapılacağı söylenir |
 * | yüz gergin (gülümsüyor, gözler kapalı, ağız açık) | "yüzünüzü gevşetin" — nötr kare ancak sonra |
 * | henüz yapmadı | bekle, komutu göstermeye devam et |
 * | yanlışını yaptı | yanlış sayacı +1, AYNI adım devam |
 * | yüz kadrajdan çıktı / takip numarası değişti | **yalnız o adım** baştan (≤ [MAX_RESETS] toplam) |
 * | süre doldu | akış biter |
 *
 * Yüz kaybında bütün dizi değil yalnız o adım yeniden alınır: kaynak değişimini enclave'in
 * her karedeki kimlik kontrolü yakalıyor; diziyi baştan başlatmak meşru kullanıcıyı cezalandırıp
 * güvenliğe bir şey katmıyordu. Toplam sınır yine var: sınırsız tekrar, saldırgana doğru klibi
 * bulmak için sınırsız deneme demek.
 *
 * "İlk hareketin doğru olması yeterli." İstemsiz göz kırpma ASLA cezalandırılmaz (dakikada
 * 15-20 kez olur); istenmişse sayılır.
 *
 * ## İz kaydı
 *
 * Her karar ([trace]) kısa bir zaman çizelgesine yazılır. Başarıda kanıtla enclave'e (oradan
 * ölçüm tablosuna), başarısızlıkta Sentry'ye gider.
 */
class EventCollector(
    private val cacheDir: File,
    private val events: List<Event>,
    private val onGuidance: (Guidance) -> Unit,
    private val onTimeLeft: (Float) -> Unit,
    private val onFailed: (Failure) -> Unit,
    private val onComplete: (Result) -> Unit,
    /** Bir hareket çözüldü (ya da süresi doldu) — huni telemetrisi için. */
    private val onEventResolved: ((event: Event, durationMs: Long, wrongCount: Int, timedOut: Boolean) -> Unit)? = null,
) {

    enum class Event(val code: Int, val telemetryStep: String) {
        BLINK(1, "gesture_blink"),
        SMILE(2, "gesture_smile"),
        MOUTH_OPEN(3, "gesture_mouth_open"),
        DOUBLE_BLINK(4, "gesture_double_blink");

        /** Olayın kaç kare istediği — enclave'in yapı kuralıyla AYNI. */
        val frames: Int get() = if (this == DOUBLE_BLINK) 2 else 1

        companion object {
            fun of(code: Int) = values().firstOrNull { it.code == code }
        }
    }

    enum class Phase {
        /** Yüzü yerleştir, gevşet, sabit dur — nötr kare bundan sonra. */
        SETTLE,

        /** İstenen hareket bekleniyor. */
        EVENT,

        /** Hareket tuttu — kısa onay, sonra sıradaki adım. */
        AFTER_EVENT,

        DONE,
    }

    /** Yüzün kadrajdaki durumu — yalnız KALİTE için (tek aralık). */
    enum class Framing { OK, TOO_SMALL, TOO_LARGE, OFF_FRAME }

    data class Guidance(
        val phase: Phase,
        val stepIndex: Int,
        val stepCount: Int,
        val event: Event,
        val framing: Framing = Framing.OK,
        /** Yüz yerinde ama henüz sabit değil. */
        val settling: Boolean = false,
        /** Hareketten önce yüz gevşemeli (gülümsüyor, gözler kapalı, ağız açık). */
        val needsRelax: Boolean = false,
        /** Az önce yapılan YANLIŞ olay — bir kez gösterilir. */
        val wrong: Event? = null,
        /** Adım az önce yeniden başladıysa sebebi — bir kez gösterilir. */
        val resetReason: String? = null,
        /** Çift kırpmada kaçıncı kırpma tuttu. */
        val eventCount: Int = 0,
        /** Adım tamamlandı — onay sesi için. */
        val stepDone: Boolean = false,
    )

    /** Başarısızlık → sunucunun sabit sebep kümesindeki karşılığı. */
    enum class Failure(val flowReason: String) {
        TIMEOUT_SETTLE("timeout_gesture"),
        TIMEOUT_EVENT("timeout_gesture"),
        TOO_MANY_WRONG("too_many_errors"),
        TOO_MANY_RESETS("too_many_errors"),
    }

    data class StepResult(
        val neutralPath: String,
        val eventPaths: List<String>,
        val attempts: Int,
    )

    data class Result(
        val steps: List<StepResult>,
        val elapsedMs: Int,
        val resets: Int,
        val wrongEvents: Int,
        /** Yüz kaybolmadan değişen takip numarası sayısı — yalnız ölçüm. */
        val trackingChanges: Int,
        /** Karar zaman çizelgesi — bkz. sınıf belgesi. */
        val trace: String,
    )

    companion object {
        private const val TAG = "Events"

        /** Nötr kare ancak yüz BU KADAR süre yerinde ve hareketsiz kalınca alınır. */
        private const val STILL_WINDOW_MS = 500L

        /** Hareketsizlik penceresinde yüz genişliğinin oynayabileceği pay (el titremesi + ML Kit gürültüsü). */
        private const val STILL_TOLERANCE = 0.08f

        /** Hareketten sonra sıradaki adıma geçmeden önceki onay süresi. */
        private const val AFTER_EVENT_MS = 700L

        /**
         * Yüz genişliği / kadraj genişliği — KALİTE aralığı. Altında gözler ve ağız ML Kit'e
         * yeterince büyük değil; üstünde yüz kadrajdan taşmaya başlıyor. Tek aralık: mesafe
         * DEĞİŞİMİ istenmiyor.
         */
        const val MIN_FACE_FRACTION = 0.28f
        const val MAX_FACE_FRACTION = 0.80f

        /** Yüz kutusunun kadraj dışına taşabileceği pay (kutu kenarı ML Kit'te biraz geniş). */
        private const val EDGE_TOLERANCE = 0.04f

        /** Yüzü yerleştirip gevşetmek için süre. */
        const val SETTLE_TIMEOUT_MS = 20_000L

        /** İstenen olayı yapmak için süre (yanlış hareketten sonra yeniden başlar). */
        const val EVENT_TIMEOUT_MS = 12_000L

        /** Yüz bu kadar kayıp kalırsa adım baştan. */
        private const val FACE_LOST_MS = 1_200L

        /** Takip numarası değişiminin adımı sıfırlaması için yüzün en az bu kadar kaybolmuş olması. */
        private const val TRACKING_GAP_MS = 300L

        /** Adımların toplam kaç kez baştan alınabileceği. */
        private const val MAX_RESETS = 3

        /** Toplam yanlış olay bütçesi — eski jest akışıyla aynı. */
        private const val MAX_WRONG = 5

        /** Tek adımda yanlış olay sınırı — tekrar sınırı UX değil GÜVENLİK parametresi. */
        private const val MAX_WRONG_PER_STEP = 3

        /**
         * Kapalı göz eşiği. 0,15 → 0,20 (2026-09-24): 100-150 ms'lik bir kırpma çoğu zaman tam
         * kapanma anında değil, yarı kapalıyken örnekleniyor.
         */
        private const val EYE_CLOSED = 0.20f
        private const val EYE_OPEN = 0.5f
        private const val SMILE_ON = 0.8f
        private const val SMILE_NEUTRAL = 0.4f

        /**
         * Kırpma sayılan kapanış: gözlerden BİRİNİN kapanması yeter (2026-09-25, kullanıcı kararı).
         *
         * Komut emojisi (😉) tek göz kırpmayı gösteriyor ve sahada tek gözle kırpan algılanmadı.
         * Güvenlik kaybı yok: fotoğraf tek gözünü de kırpamaz; istenen, komuta canlı bir tepki.
         * İki gözle kırpan da, tek gözle kırpan da geçer. Açılış (kenarın sıfırlanması) İKİ gözün
         * de açılmasını ister — tek göz kırpmanın ortasında sayaç ikinci kez tetiklenmesin.
         */
        internal fun isClosing(left: Float, right: Float): Boolean = minOf(left, right) < EYE_CLOSED

        /** Olay sırasında ağır işin ertelenmesi gereken hareketler — kısa olanlar (bkz. [quietPhase]). */
        internal fun quietFor(event: Event): Boolean = event == Event.BLINK || event == Event.DOUBLE_BLINK

        /**
         * AĞIZ AÇIKLIĞI — ML Kit dudak KONTURUNDAN: iç dudak kenarları arası / iç ağız genişliği
         * (LivenessAnalyzer.innerLipOpen). Kapalı ağızda ~0.
         *
         * ML Kit'in yüz NOKTALARI çene açılmasını izlemiyor (2026-09-24: açık ağızda alt dudak
         * noktası ters yöne gitti, gülümseme olasılığı 0,82'ye çıktı); kontur izliyor (sahada
         * kapalı 0,02-0,03, açık 0,39).
         *
         * Ağız açık = en az [MOUTH_OPEN_MIN] VE nötrden en az [MOUTH_OPEN_DELTA] fazla. Nötr değer
         * yerleşme sırasında toplanan ölçümlerin ORTANCASI.
         */
        private const val MOUTH_OPEN_MIN = 0.20f
        private const val MOUTH_OPEN_DELTA = 0.15f
        private const val MOUTH_RELAX_DELTA = 0.08f

        /** Nötr ağız ölçüsü için tutulan en fazla örnek. */
        private const val MOUTH_SAMPLES = 25

        /** Çift kırpmada iki kırpma arası en fazla. Tek kırpma ardından sessizlik = istemsiz. */
        private const val DOUBLE_BLINK_WINDOW_MS = 2_000L

        /**
         * Çift kırpmada iki kapanma arası EN AZ. Tek bir kırpmada göz olasılığı bir kare için
         * 0,5'in üstüne titrerse aynı kırpma iki kez sayılmasın; gerçek çift kırpmada kapanmalar
         * 250-500 ms arayla geliyor.
         */
        private const val DOUBLE_BLINK_MIN_GAP_MS = 150L

        private const val OUTPUT_LONG_EDGE = 480
        private const val JPEG_QUALITY = 88
        private const val MIN_FACE_PX = 30f

        /** İz kaydının tavanı — yük ve ölçüm satırı şişmesin. */
        const val MAX_TRACE_CHARS = 3_500

        /** Olay sırasında sinyal örneği sıklığı (iz kaydı için). */
        private const val EVENT_SAMPLE_MS = 300L
    }

    private class StepCapture {
        var neutral: String? = null
        val events = mutableListOf<String>()
        var wrong = 0
    }

    private val lock = Any()

    private var phase = Phase.SETTLE
    private var index = 0
    private var captured = List(events.size) { StepCapture() }

    private var startedAt = 0L
    private var phaseStartedAt = 0L
    private var eventStartedAt = 0L
    private var afterEventAt = 0L
    private var lastFaceAt = 0L
    private var trackingId: Int? = null
    private var seq = 0

    /** Yerleşme penceresi: (zaman, yüz genişliği). */
    private val stillSamples = ArrayDeque<Pair<Long, Float>>()
    private var lastFraming: Framing? = null

    private var resets = 0
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

    /** Hareket komutunun EKRANA GELDİĞİ an — huni telemetrisinin süresi buradan ölçülür. */
    private var commandShownAt = 0L

    /** Olay sırasında işlenen kare sayısı — kare hızını iz kaydına yazmak için. */
    private var eventFrames = 0

    private val trace = StringBuilder()

    val isActive: Boolean get() = synchronized(lock) { phase != Phase.DONE }

    /** Tamamlanan adım sayısı. */
    val completedSteps: Int get() = synchronized(lock) { index }

    val wrongCount: Int get() = synchronized(lock) { wrongEvents }

    /**
     * Bu karede dudak konturu ölçülsün mü — yalnız ağız açma adımında (yerleşirken nötr toplanır,
     * olay sırasında açıklık okunur). İkinci dedektör kare hızını düşürür; diğer olaylarda çalışmamalı.
     */
    val wantsContour: Boolean
        get() = synchronized(lock) {
            (phase == Phase.SETTLE || phase == Phase.EVENT) &&
                index < events.size && events[index] == Event.MOUTH_OPEN
        }

    /**
     * Göz kırpma bekleniyor — çağıran bu sırada ağır işleri (selfie adayı, ArcFace) ERTELEMELİ.
     *
     * ML Kit sonucu ana iş parçacığında işleniyor ve sonraki kare ancak bu kare kapanınca geliyor;
     * her ağır iş kare hızını düşürür. Sahada çift kırpmanın ikincisi kaçtı: ilk kırpmanın karesi
     * yazılırken ve selfie adayı hesaplanırken 100-150 ms'lik ikinci kırpma arada kalıyordu.
     *
     * Yalnız kırpmada ([quietFor]): gülümseme ve ağız açma yüzlerce milisaniyede açılıp TUTULUYOR,
     * bir karelik gecikme onları kaçırtmıyor. O adımlarda selfie adayı toplanmaya devam eder ki
     * ekrandaki benzerlik yüzdesi hareket boyunca da güncellensin — hareketin tamamı sessizken
     * yüzde eskisi kadar sık güncellenmiyor ve yükselmiyordu (kullanıcı, 2026-09-27).
     */
    val quietPhase: Boolean get() = synchronized(lock) {
        phase == Phase.EVENT && index < events.size && quietFor(events[index])
    }

    /** Şu ana kadarki iz kaydı — başarısızlıkta Sentry'ye gider. */
    val traceText: String get() = synchronized(lock) { trace.toString() }

    fun start() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        startedAt = now
        phaseStartedAt = now
        phase = Phase.SETTLE
        index = 0
        trace("start ${events.joinToString(",") { it.name.lowercase(Locale.US) }}")
        guide(Phase.SETTLE, framing = Framing.OFF_FRAME)
    }

    /**
     * Kare akışı. Çağıran [ImageProxy]'yi kapatmaya devam eder — bu sınıf yalnız okur.
     *
     * @param lipOpen bu karenin iç dudak açıklığı ([wantsContour] true iken ölçülür), yoksa null.
     */
    fun offer(imageProxy: ImageProxy, face: Face, lipOpen: Float? = null) = synchronized(lock) {
        if (phase == Phase.DONE) return
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastFaceAt > 0) now - lastFaceAt else 0L
        lastFaceAt = now

        val w = face.boundingBox.width().toFloat()
        if (w < MIN_FACE_PX) return

        // 🔴 Takip numarası değişti ve yüz GERÇEKTEN kaybolmuştu → aynı kişi olduğu kanıtlanamaz,
        // adım baştan. Kesintisiz algılamada değişen numara SIFIRLAMAZ, yalnız sayılır: ML Kit'in
        // numarayı kendiliğinden yenileme sıklığı ölçülmedi. Kaynak değişimini asıl yakalayan
        // enclave'in her karedeki kimlik kontrolü.
        face.trackingId?.let { id ->
            val previous = trackingId
            trackingId = id
            if (previous != null && previous != id) {
                if (gap >= TRACKING_GAP_MS && phase != Phase.AFTER_EVENT && hasCaptureInStep()) {
                    resetStep("tracking", now)
                    return
                }
                trackingChanges++
                trace("track+ gap=${gap}ms")
            }
        }

        when (phase) {
            Phase.SETTLE -> handleSettle(imageProxy, face, w, lipOpen, now)
            Phase.EVENT -> handleEvent(imageProxy, face, lipOpen, now)
            Phase.AFTER_EVENT -> if (now - afterEventAt >= AFTER_EVENT_MS) nextStep(now)
            Phase.DONE -> Unit
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

        // Onay anında adım zaten tamam — yüz kaybı onu silmemeli.
        if (phase == Phase.AFTER_EVENT) {
            if (now - afterEventAt >= AFTER_EVENT_MS) nextStep(now)
            return
        }

        if (lastFaceAt > 0 && now - lastFaceAt > FACE_LOST_MS && hasCaptureInStep()) {
            resetStep("face_lost", now)
            return
        }

        val (limit, since) = when (phase) {
            Phase.EVENT -> EVENT_TIMEOUT_MS to eventStartedAt
            else -> SETTLE_TIMEOUT_MS to phaseStartedAt
        }
        val left = 1f - (now - since).toFloat() / limit
        onTimeLeft(left.coerceIn(0f, 1f))
        if (left <= 0f) {
            if (phase == Phase.EVENT) {
                onEventResolved?.invoke(events[index], now - commandShownAt, captured[index].wrong, true)
                fail(Failure.TIMEOUT_EVENT)
            } else {
                fail(Failure.TIMEOUT_SETTLE)
            }
        }
    }

    fun abandon() = synchronized(lock) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        trace("abandon")
        deleteAll()
    }

    // ── Aşamalar ─────────────────────────────────────────────────────────────

    private fun handleSettle(imageProxy: ImageProxy, face: Face, w: Float, lipOpen: Float?, now: Long) {
        val framing = framingOf(imageProxy, face)
        if (framing != lastFraming) {
            trace("e$index frame ${framing.name.lowercase(Locale.US)}")
            lastFraming = framing
        }
        if (framing != Framing.OK) {
            stillSamples.clear()
            guide(Phase.SETTLE, framing = framing)
            return
        }

        lipOpen?.let {
            mouthSamples += it
            if (mouthSamples.size > MOUTH_SAMPLES) mouthSamples.removeAt(0)
        }

        // Nötr yüz: gözler açık, gülümsemiyor, ağız kapalı (ağız yalnız ağız açma adımında ölçülüyor).
        val smile = face.smilingProbability ?: 0f
        val relaxed = eyesOpenNow(face) && smile < SMILE_NEUTRAL &&
            (lipOpen == null || lipOpen < MOUTH_OPEN_MIN)

        stillSamples.addLast(now to w)
        while (stillSamples.isNotEmpty() && stillSamples.first().first < now - STILL_WINDOW_MS)
            stillSamples.removeFirst()
        val windowCovered = stillSamples.size >= 3 &&
            now - stillSamples.first().first >= STILL_WINDOW_MS * 0.8
        val widths = stillSamples.map { it.second }
        val spread = if (widths.isEmpty()) 1f else (widths.max() - widths.min()) / widths.min()
        val still = windowCovered && spread <= STILL_TOLERANCE

        if (!relaxed) {
            guide(Phase.SETTLE, needsRelax = true)
            return
        }
        guide(Phase.SETTLE, settling = !still)
        if (!still) return

        val path = capture(imageProxy, face, "n$index") ?: return
        captured[index].neutral = path
        mouthNeutral = median(mouthSamples)
        trace("e$index neutral sm=${f2(smile)} mouth0=${f2(mouthNeutral)}")
        beginEvent(face, now)
    }

    private fun beginEvent(face: Face, now: Long) {
        phase = Phase.EVENT
        eventStartedAt = now
        if (commandShownAt == 0L) commandShownAt = now
        lastSampleAt = 0L
        eventFrames = 0
        blinkCount = 0
        eyesClosed = eyesClosedNow(face)
        val smile = face.smilingProbability ?: 0f
        smileArmed = smile < SMILE_NEUTRAL
        rearmRequired = false
        trace("e$index ev ${events[index].name.lowercase(Locale.US)}")
        guide(Phase.EVENT)
    }

    private fun handleEvent(imageProxy: ImageProxy, face: Face, lipOpen: Float?, now: Long) {
        eventFrames++

        val demanded = events[index]
        val closed = eyesClosedNow(face)
        val open = eyesOpenNow(face)
        val smile = face.smilingProbability ?: 0f
        // Ağız yalnız ağız açma adımında ölçülüyor (kontur); diğer olaylarda null.
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
            trace("s ${f2(face.leftEyeOpenProbability)}/${f2(face.rightEyeOpenProbability)} " +
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
                trace("e$index rearmed")
                guide(Phase.EVENT)
            }
            return
        }

        // Çift kırpmada ilk kırpmanın ardından sessizlik: istemsiz kırpmaydı, sessizce sıfırla.
        if (demanded == Event.DOUBLE_BLINK && blinkCount == 1 && now - firstBlinkAt > DOUBLE_BLINK_WINDOW_MS) {
            dropEventFrames()
            blinkCount = 0
            trace("e$index dbl timeout")
            guide(Phase.EVENT)
        }

        // 1) İstenen olay önce: aynı karede başka bir şey de olsa istenen yapıldıysa GEÇER.
        val satisfied = when (demanded) {
            Event.BLINK -> closing
            Event.DOUBLE_BLINK -> closing && (blinkCount == 0 || now - firstBlinkAt >= DOUBLE_BLINK_MIN_GAP_MS)
            Event.SMILE -> smileRise
            Event.MOUTH_OPEN -> mouthOpen
        }
        if (satisfied) {
            val path = capture(imageProxy, face, "e${index}_${captured[index].events.size}") ?: return
            captured[index].events += path
            if (demanded == Event.DOUBLE_BLINK) {
                blinkCount++
                if (blinkCount == 1) {
                    firstBlinkAt = now
                    trace("e$index dbl 1/2")
                    guide(Phase.EVENT, eventCount = 1)
                    return
                }
            }
            val secs = (now - eventStartedAt).coerceAtLeast(1L) / 1000f
            trace("e$index ok sm=${f2(smile)} lip=${f2(lipOpen)} " +
                "fps=${String.format(Locale.US, "%.1f", eventFrames / secs)}")
            onEventResolved?.invoke(demanded, now - commandShownAt, captured[index].wrong, false)
            phase = Phase.AFTER_EVENT
            afterEventAt = now
            guide(Phase.AFTER_EVENT, stepDone = true)
            return
        }

        // 2) Yanlış olay — yalnız GÜVENİLİR biçimde ayırt edilebilen KASITLI hareketler.
        //
        //  - Göz kırpma asla yanlış sayılmaz (istemsiz).
        //  - Ağız açma istenirken gülümseme yanlış SAYILMAZ: ML Kit açık ağzı gülümseme sanıyor
        //    (sahada 0,82) ve doğru hareketi yapan kullanıcı "yanlış hareket" alıyordu.
        //  - Ağız açma yalnız ağız açma adımında ölçülüyor (kontur dedektörü kare hızını
        //    düşürür); diğer adımlarda yanlış hareket olarak da aranmıyor.
        val wrong = when (demanded) {
            Event.BLINK, Event.DOUBLE_BLINK -> if (smileRise) Event.SMILE else null
            Event.SMILE, Event.MOUTH_OPEN -> null
        }
        if (wrong != null) {
            trace("e$index wrong ${wrong.name.lowercase(Locale.US)} sm=${f2(smile)}")
            onWrong(wrong, now)
        }
    }

    private fun onWrong(event: Event, now: Long) {
        wrongEvents++
        captured[index].wrong++
        dropEventFrames()
        blinkCount = 0
        rearmRequired = true
        eventStartedAt = now   // yeni deneme için tam süre
        if (wrongEvents >= MAX_WRONG || captured[index].wrong >= MAX_WRONG_PER_STEP) {
            fail(Failure.TOO_MANY_WRONG)
            return
        }
        guide(Phase.EVENT, wrong = event, needsRelax = true)
    }

    private fun nextStep(now: Long) {
        trace("e$index done")
        index++
        if (index >= events.size) { finish(now); return }
        enterSettle(now)
        guide(Phase.SETTLE)
    }

    /**
     * Yüz kayboldu ya da başka bir yüz geldi → YALNIZ BU ADIM baştan. Tamamlanan adımlar korunur:
     * her karede kimliği enclave doğruluyor. Adımın yanlış hareket sayısı korunur — tekrar, yanlış
     * hareket bütçesini sıfırlamanın yolu olmasın.
     */
    private fun resetStep(reason: String, now: Long) {
        resets++
        trace("reset e$index $reason")
        deleteStep(index)
        if (resets > MAX_RESETS) {
            fail(Failure.TOO_MANY_RESETS)
            return
        }
        enterSettle(now)
        guide(Phase.SETTLE, resetReason = reason, framing = Framing.OFF_FRAME)
    }

    private fun enterSettle(now: Long) {
        phase = Phase.SETTLE
        phaseStartedAt = now
        commandShownAt = 0L
        stillSamples.clear()
        mouthSamples.clear()
        mouthNeutral = null
        lastFraming = null
    }

    private fun fail(failure: Failure) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        trace("fail ${failure.name} e$index")
        Log.i(TAG, "Başarısız: $failure (adım ${index + 1}/${events.size})")
        deleteAll()
        onFailed(failure)
    }

    private fun finish(now: Long) {
        phase = Phase.DONE
        trace("finish")
        onComplete(
            Result(
                steps = captured.map { StepResult(it.neutral!!, it.events.toList(), it.wrong + 1) },
                elapsedMs = (now - startedAt).toInt(),
                resets = resets,
                wrongEvents = wrongEvents,
                trackingChanges = trackingChanges,
                trace = trace.toString(),
            )
        )
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** Yüzün kadrajdaki durumu. ML Kit kutusu DÖNDÜRÜLMÜŞ görüntü uzayında; kadraj da o uzayda alınır. */
    private fun framingOf(imageProxy: ImageProxy, face: Face): Framing {
        val rot = imageProxy.imageInfo.rotationDegrees
        val frameW = (if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width).toFloat()
        val frameH = (if (rot == 90 || rot == 270) imageProxy.width else imageProxy.height).toFloat()
        if (frameW <= 0f || frameH <= 0f) return Framing.OFF_FRAME
        val box = face.boundingBox
        val tolX = frameW * EDGE_TOLERANCE
        val tolY = frameH * EDGE_TOLERANCE
        if (box.left < -tolX || box.top < -tolY || box.right > frameW + tolX || box.bottom > frameH + tolY)
            return Framing.OFF_FRAME
        val fraction = box.width() / frameW
        return when {
            fraction < MIN_FACE_FRACTION -> Framing.TOO_SMALL
            fraction > MAX_FACE_FRACTION -> Framing.TOO_LARGE
            else -> Framing.OK
        }
    }

    private fun hasCaptureInStep() = index < captured.size &&
        (captured[index].neutral != null || captured[index].events.isNotEmpty())

    private fun guide(
        phase: Phase,
        framing: Framing = Framing.OK,
        settling: Boolean = false,
        needsRelax: Boolean = false,
        wrong: Event? = null,
        resetReason: String? = null,
        eventCount: Int = 0,
        stepDone: Boolean = false,
    ) {
        val i = index.coerceIn(0, events.size - 1)
        onGuidance(
            Guidance(phase, i, events.size, events[i], framing, settling, needsRelax,
                wrong, resetReason, eventCount, stepDone)
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

    private fun f2(v: Float?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun eyesClosedNow(face: Face): Boolean {
        val l = face.leftEyeOpenProbability ?: return false
        val r = face.rightEyeOpenProbability ?: return false
        return isClosing(l, r)
    }

    private fun eyesOpenNow(face: Face): Boolean {
        val l = face.leftEyeOpenProbability ?: return false
        val r = face.rightEyeOpenProbability ?: return false
        return l > EYE_OPEN && r > EYE_OPEN
    }

    private fun dropEventFrames(i: Int = index) {
        captured[i].events.forEach { runCatching { File(it).delete() } }
        captured[i].events.clear()
    }

    private fun deleteStep(i: Int) {
        if (i !in captured.indices) return
        captured[i].neutral?.let { runCatching { File(it).delete() } }
        captured[i].neutral = null
        dropEventFrames(i)
    }

    private fun deleteAll() {
        for (i in captured.indices) deleteStep(i)
    }

    /**
     * Yüzün çevresinden kare bir kırpma alır, uzun kenarı en fazla [OUTPUT_LONG_EDGE] olacak
     * şekilde küçültüp diske yazar. Kırpma, küçültme ve döndürme TEK adımda (bkz. [FaceCrop]).
     */
    private fun capture(imageProxy: ImageProxy, face: Face, name: String): String? {
        var srcRef: Bitmap? = null
        var outRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap()
            srcRef = bmp
            val rotation = imageProxy.imageInfo.rotationDegrees
            val uprightW = if (rotation == 90 || rotation == 270) bmp.height else bmp.width
            val uprightH = if (rotation == 90 || rotation == 270) bmp.width else bmp.height
            val b = face.boundingBox
            val crop = FaceCrop.squareAround(b.left, b.top, b.right, b.bottom, uprightW, uprightH)
            val src = FaceCrop.toSensor(crop, rotation, bmp.width, bmp.height)
            val ratio = minOf(1f, OUTPUT_LONG_EDGE.toFloat() / max(src.width, src.height))
            val m = Matrix().apply { postScale(ratio, ratio); postRotate(rotation.toFloat()) }
            val out = Bitmap.createBitmap(bmp, src.left, src.top, src.width, src.height, m, true)
            outRef = out

            val f = File(cacheDir, "event_${name}_${seq++}.jpg")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            f.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Kare yazılamadı: ${e.message}")
            trace("capture fail")
            null
        } finally {
            outRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }
}
