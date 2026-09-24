package com.verifyblind.mobile.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
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
 * asıl sınama enclave'de. Bu yüzden eşikler burada cömert.
 *
 * ## Kurallar (kullanıcı kararları, 2026-09-24)
 *
 * | olay | tepki |
 * |---|---|
 * | henüz yapmadı | bekle, komutu göstermeye devam et |
 * | yanlışını yaptı | yanlış sayacı +1, AYNI durak devam (dizi sıfırlanmaz) |
 * | duruşta ölçek toleransı aştı | **dizi baştan** |
 * | yüz kadrajdan çıktı / takip numarası değişti | **dizi baştan** |
 * | süre doldu | akış biter |
 *
 * "İlk hareketin doğru olması yeterli." İstemsiz göz kırpma ASLA cezalandırılmaz (dakikada
 * 15-20 kez olur); istenmişse sayılır. Durakta yapılabilecek tekrar sayısı SINIRLI: sınırsız
 * tekrar, saldırgana doğru klibi bulmak için sınırsız deneme demek.
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
        /** Hedef mesafeye git. */
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
        /** Hedef banda yakınlık (0-1) — yalnız [Phase.MOVE]'da anlamlı. */
        val progress: Float = 0f,
        /** Olaydan önce yüz gevşemeli (gülümseme/ağız açık başlanmış ya da yanlış olay sonrası). */
        val needsRelax: Boolean = false,
        /** Az önce yapılan YANLIŞ olay — bir kez gösterilir. */
        val wrong: Event? = null,
        /** Dizi az önce baştan başladıysa sebebi — bir kez gösterilir. */
        val resetReason: String? = null,
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
        val wrongEvents: Int,
        /** Yüz kaybolmadan değişen takip numarası sayısı — sıfırlama kuralı bu dağılımla kalibre edilecek. */
        val trackingChanges: Int,
    )

    companion object {
        private const val TAG = "Stance"

        /** Bantta bu kadar durulunca ilk duruş karesi alınır — hareket bulanıklığı olmasın. */
        private const val SETTLE_MS = 350L

        /** Olaysız durakta iki duruş karesi arası. Parallaks için 900 ms yetiyordu. */
        private const val HOLD_MS = 900L

        /** Olaydan sonra ikinci duruş karesine kadar. */
        private const val AFTER_EVENT_MS = 600L

        /**
         * Duruşta yüz genişliğinin oynayabileceği pay. Gevşek başlandı (%15): kol mesafesinde
         * doğal titreme %5-10'u aşabiliyor. Asıl ölçü enclave'de (duruş kayması); dağılım
         * görülünce sıkılaştırılır.
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

        /** Dizinin kaç kez baştan başlayabileceği. */
        private const val MAX_RESETS = 3

        /** Toplam yanlış olay bütçesi — eski jest akışıyla aynı. */
        private const val MAX_WRONG = 5

        /** Tek durakta yanlış olay sınırı — tekrar sınırı UX değil GÜVENLİK parametresi. */
        private const val MAX_WRONG_PER_STOP = 3

        private const val EYE_CLOSED = 0.15f
        private const val EYE_OPEN = 0.5f
        private const val SMILE_ON = 0.8f
        private const val SMILE_NEUTRAL = 0.4f

        /**
         * Ağız açıklığı: burun tabanı ile alt dudak arası / göz-arası, nötre göre bu katı aşarsa
         * "açık". Kapalı ağızda ~0,6-0,7; açılınca alt dudak iner ve oran %30-60 büyür.
         */
        private const val MOUTH_OPEN_RATIO = 1.30f
        private const val MOUTH_NEUTRAL_RATIO = 1.10f

        /** Çift kırpmada iki kırpma arası en fazla. Tek kırpma ardından sessizlik = istemsiz. */
        private const val DOUBLE_BLINK_WINDOW_MS = 1_500L

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
    }

    private class StopCapture {
        val hold = mutableListOf<String>()
        val events = mutableListOf<String>()
        var faceFraction = 0f
        var wrong = 0
    }

    private val lock = Any()

    private var phase = Phase.MOVE
    private var index = 0
    private var captured = List(stops.size) { StopCapture() }

    private var startedAt = 0L
    private var stopStartedAt = 0L
    private var inBandSince = 0L
    private var holdWidth = 0f
    private var holdStartedAt = 0L
    private var eventStartedAt = 0L
    private var afterEventAt = 0L
    private var lastFaceAt = 0L
    private var trackingId: Int? = null
    private var seq = 0

    private var resets = 0
    private var wrongEvents = 0

    /** Kesintisiz algılamada takip numarası kaç kez değişti — YALNIZ ÖLÇÜM. */
    private var trackingChanges = 0

    // Olay algılama
    private var mouthNeutral = Float.MAX_VALUE
    private var smileArmed = false
    private var rearmRequired = false
    private var eyesClosed = false
    private var blinkCount = 0
    private var firstBlinkAt = 0L

    // Arka plan
    private var bgTexture: Float? = null
    private var bgTextureNear: Float? = null
    private var bgWaived = false
    private var bgPoorSince = 0L
    private var bgLastSampleAt = 0L
    private var bgGoodStreak = 0
    private var bgLastWidth = 0f

    val isActive: Boolean get() = synchronized(lock) { phase != Phase.DONE }

    /** Ekranda gösterilecek tamamlanan durak sayısı. */
    val completedStops: Int get() = synchronized(lock) { index }

    fun start() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        startedAt = now
        stopStartedAt = now
        phase = Phase.MOVE
        index = 0
        guide(Phase.MOVE)
    }

    /**
     * Kare akışı. Çağıran [ImageProxy]'yi kapatmaya devam eder — bu sınıf yalnız okur.
     *
     * @return bu karede olay onaylandıysa onaylanan olay (gülümseme karesi ölçümü için).
     */
    fun offer(imageProxy: ImageProxy, face: Face): Event? = synchronized(lock) {
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
        // kanıtlanamaz, dizi baştan. Kafa çevirme jesti kalktığı için yüzün kadrajdan çıkmasının
        // meşru bir sebebi de kalmadı.
        //
        // ⚠️ Kesintisiz algılamada değişen numara SIFIRLAMAZ, yalnız sayılır: ML Kit'in numarayı
        // hızlı ölçek değişiminde kendiliğinden yenileyip yenilemediği ölçülmedi (eski sayaç hiçbir
        // yere gönderilmiyordu). Güvenlik kaybı yok — bu kontrol yamalanmış istemcide zaten
        // atlanır; kaynak değişimini enclave'in kimlik kapısı yakalıyor.
        face.trackingId?.let { id ->
            val previous = trackingId
            trackingId = id
            if (previous != null && previous != id) {
                if (gap >= TRACKING_GAP_MS && hasCaptures()) {
                    reset("tracking", now)
                    return null
                }
                trackingChanges++
            }
        }

        return when (phase) {
            Phase.MOVE -> { handleMove(imageProxy, face, w, fraction, now); null }
            Phase.HOLD -> { handleHold(imageProxy, face, w, now); null }
            Phase.EVENT -> handleEvent(imageProxy, face, w, now)
            Phase.AFTER_EVENT -> { handleAfterEvent(imageProxy, face, w, now); null }
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
        deleteAll()
    }

    // ── Aşamalar ─────────────────────────────────────────────────────────────

    private fun handleMove(imageProxy: ImageProxy, face: Face, w: Float, fraction: Float, now: Long) {
        val stop = stops[index]
        val pos = stop.position
        val direction = when {
            fraction < pos.min -> +1
            fraction > pos.max -> -1
            else -> 0
        }

        if (direction != 0) {
            inBandSince = 0L
            val progress = if (direction > 0) fraction / pos.min else pos.max / fraction
            guide(Phase.MOVE, direction = direction, progress = progress.coerceIn(0f, 1f))
            return
        }

        if (inBandSince == 0L) {
            inBandSince = now
            mouthNeutral = Float.MAX_VALUE
            guide(Phase.MOVE, direction = 0, progress = 1f)
        }
        // Bantta beklerken ağzın nötr ölçüsü toplanır: kapalı ağız en küçük değer.
        mouthRatio(face)?.let { mouthNeutral = minOf(mouthNeutral, it) }
        if (now - inBandSince < SETTLE_MS) return

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

        if (texture != null) {
            if (grow == TEXTURE_GROW_NEAR) bgTextureNear = texture else bgTexture = texture
            Log.i(TAG, "Doku: ${"%.1f".format(texture)} (${if (grow == TEXTURE_GROW_NEAR) "yakın çıpa" else "uzak"})")
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
            guide(Phase.HOLD)
        } else {
            beginEvent(face, now)
        }
    }

    private fun handleHold(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (drifted(w)) { reset("drift", now); return }
        if (now - holdStartedAt < HOLD_MS) return
        val (path, _) = capture(imageProxy, face, "h${index}b", null) ?: return
        captured[index].hold += path
        nextStop(now)
    }

    private fun beginEvent(face: Face, now: Long) {
        phase = Phase.EVENT
        eventStartedAt = now
        blinkCount = 0
        eyesClosed = eyesClosedNow(face)
        val smile = face.smilingProbability ?: 0f
        smileArmed = smile < SMILE_NEUTRAL
        // Gülümseyerek ya da ağız açık başlanan olay "yeni" bir hareket değildir.
        rearmRequired = !smileArmed && stops[index].event == Event.SMILE
        guide(Phase.EVENT, needsRelax = rearmRequired)
    }

    private fun handleEvent(imageProxy: ImageProxy, face: Face, w: Float, now: Long): Event? {
        if (drifted(w)) { reset("drift", now); return null }

        val demanded = stops[index].event
        val closed = eyesClosedNow(face)
        val open = eyesOpenNow(face)
        val smile = face.smilingProbability ?: 0f
        val mouth = mouthRatio(face)
        if (mouthNeutral == Float.MAX_VALUE && mouth != null) mouthNeutral = mouth
        val mouthOpen = mouth != null && mouthNeutral < Float.MAX_VALUE && mouth > mouthNeutral * MOUTH_OPEN_RATIO
        val mouthNeutralNow = mouth == null || mouthNeutral == Float.MAX_VALUE ||
            mouth < mouthNeutral * MOUTH_NEUTRAL_RATIO
        if (smile < SMILE_NEUTRAL) smileArmed = true
        val smileRise = smileArmed && smile > SMILE_ON

        // Kapanış kenarı: yeni bir kırpma, açık → kapalı geçişidir.
        val closing = closed && !eyesClosed
        eyesClosed = when {
            closed -> true
            open -> false
            else -> eyesClosed
        }

        if (rearmRequired) {
            if (smile < SMILE_NEUTRAL && mouthNeutralNow) {
                rearmRequired = false
                smileArmed = true
                guide(Phase.EVENT)
            }
            return null
        }

        // Çift kırpmada ilk kırpmanın ardından sessizlik: istemsiz kırpmaydı, sessizce sıfırla.
        if (demanded == Event.DOUBLE_BLINK && blinkCount == 1 && now - firstBlinkAt > DOUBLE_BLINK_WINDOW_MS) {
            dropEventFrames()
            blinkCount = 0
            guide(Phase.EVENT)
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
                    guide(Phase.EVENT, eventCount = 1)
                    return null
                }
            }
            phase = Phase.AFTER_EVENT
            afterEventAt = now
            guide(Phase.AFTER_EVENT, stepDone = true)
            return demanded
        }

        // 2) Yanlış olay — yalnız KASITLI hareketler. Göz kırpma asla yanlış sayılmaz.
        val wrong = when (demanded) {
            Event.BLINK, Event.DOUBLE_BLINK -> when {
                smileRise -> Event.SMILE
                mouthOpen -> Event.MOUTH_OPEN
                else -> null
            }
            // Geniş gülümseme ağzı da açabilir: yalnız gülümsemeden açılan ağız yanlış.
            Event.SMILE -> if (mouthOpen && smile < SMILE_NEUTRAL) Event.MOUTH_OPEN else null
            // Ağzı açarken olasılık oynayabilir: yalnız ağız KAPALIYKEN gelen gülümseme yanlış.
            Event.MOUTH_OPEN -> if (smileRise && mouthNeutralNow) Event.SMILE else null
            Event.NONE -> null
        }
        if (wrong != null) onWrong(wrong, now)
        return null
    }

    private fun onWrong(event: Event, now: Long) {
        wrongEvents++
        captured[index].wrong++
        dropEventFrames()
        blinkCount = 0
        rearmRequired = true
        eventStartedAt = now   // yeni deneme için tam süre
        Log.i(TAG, "Yanlış olay: $event (durak ${index + 1}, toplam $wrongEvents)")
        if (wrongEvents >= MAX_WRONG || captured[index].wrong >= MAX_WRONG_PER_STOP) {
            fail(Failure.TOO_MANY_WRONG)
            return
        }
        guide(Phase.EVENT, wrong = event, needsRelax = true)
    }

    private fun handleAfterEvent(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (drifted(w)) { reset("drift", now); return }
        if (now - afterEventAt < AFTER_EVENT_MS) return
        val (path, _) = capture(imageProxy, face, "h${index}b", null) ?: return
        captured[index].hold += path
        nextStop(now)
    }

    private fun enterBackgroundPoor(now: Long) {
        Log.i(TAG, "Yakın çıpada arka plan desensiz: $bgTextureNear")
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
        Log.i(TAG, "Arka plan düzelmedi — uyarı esnetiliyor, devam")
        bgWaived = true
        resumeAnchor(now)
    }

    private fun resumeAnchor(now: Long) {
        deleteStop(0)
        phase = Phase.MOVE
        index = 0
        stopStartedAt = now
        inBandSince = 0L
        guide(Phase.MOVE)
    }

    private fun nextStop(now: Long) {
        index++
        if (index >= stops.size) { finish(now); return }
        phase = Phase.MOVE
        stopStartedAt = now
        inBandSince = 0L
        mouthNeutral = Float.MAX_VALUE
        guide(Phase.MOVE, stepDone = true)
    }

    private fun reset(reason: String, now: Long) {
        resets++
        Log.i(TAG, "Dizi baştan ($reason) — $resets. kez")
        deleteAll()
        captured = List(stops.size) { StopCapture() }
        if (resets > MAX_RESETS) {
            fail(Failure.TOO_MANY_RESETS)
            return
        }
        phase = Phase.MOVE
        index = 0
        stopStartedAt = now
        inBandSince = 0L
        mouthNeutral = Float.MAX_VALUE
        guide(Phase.MOVE, resetReason = reason)
    }

    private fun fail(failure: Failure) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        Log.i(TAG, "Başarısız: $failure (durak ${index + 1}/${stops.size})")
        deleteAll()
        onFailed(failure)
    }

    private fun finish(now: Long) {
        phase = Phase.DONE
        onComplete(
            Result(
                stops = captured.map {
                    StopResult(it.hold.toList(), it.events.toList(), it.faceFraction, it.wrong + 1)
                },
                bgTexture = bgTexture,
                bgTextureNear = bgTextureNear,
                elapsedMs = (now - startedAt).toInt(),
                resets = resets,
                wrongEvents = wrongEvents,
                trackingChanges = trackingChanges,
            )
        )
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private fun drifted(w: Float) = holdWidth > 0f && abs(w / holdWidth - 1f) > HOLD_TOLERANCE

    private fun hasCaptures() = captured.any { it.hold.isNotEmpty() || it.events.isNotEmpty() }

    private fun guide(
        phase: Phase,
        direction: Int = 0,
        progress: Float = 0f,
        needsRelax: Boolean = false,
        wrong: Event? = null,
        resetReason: String? = null,
        eventCount: Int = 0,
        stepDone: Boolean = false,
    ) {
        val i = index.coerceIn(0, stops.size - 1)
        onGuidance(
            Guidance(phase, i, stops.size, stops[i], direction, progress, needsRelax, wrong,
                resetReason, eventCount, stepDone)
        )
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

    /** Burun tabanı → alt dudak / göz-arası. Nokta yoksa null. */
    private fun mouthRatio(face: Face): Float? {
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val lip = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return null
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val ied = hypot(le.x - re.x, le.y - re.y)
        if (ied < 1f) return null
        return hypot(lip.x - nose.x, lip.y - nose.y) / ied
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
            val m = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            fullRef = full

            val texture = textureGrow?.let { BackgroundTexture.measure(full, face.boundingBox, 1f, it) }

            val ratio = OUTPUT_LONG_EDGE.toFloat() / max(full.width, full.height)
            val tw = (full.width * ratio).toInt().coerceAtLeast(1)
            val th = (full.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(full, tw, th, true)
            scaledRef = scaled

            val f = File(cacheDir, "stance_${name}_${seq++}.jpg")
            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            f.absolutePath to texture
        } catch (e: Exception) {
            Log.w(TAG, "Kare yazılamadı: ${e.message}")
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
