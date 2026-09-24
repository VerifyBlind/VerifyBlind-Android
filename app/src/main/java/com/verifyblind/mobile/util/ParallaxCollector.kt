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
import kotlin.math.min

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
 * Uzaklaşmanın yeterliliği **mutlak** ölçülür ([RETREAT_TARGET_FRACTION]): yüz kutusu kadraj
 * genişliğinin belli bir oranına inmeli. Ölçünün göreli olduğu sürümde ("başladığın yerden şu
 * kadar küçül") hedef, kullanıcının nerede durakladığına göre kayıyordu ve sık sık kadraja
 * sığmayan bir yakınlaşma isteniyordu; ayrıntı o sabitin belgesinde.
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
 *
 * Sahada bulunan iki incelik (2026-09-17):
 *  - Moladan çıkmak **tek ölçümle olmaz** ([BG_RECOVERY_SAMPLES]): telefonu ileri geri
 *    oynatarak uyarıyı geçmek mümkündü. Artık üst üste ölçüm ve kararlı kadraj aranıyor.
 *  - Kapının baktığı sayı hâlâ **tüm kare**; yanında merkez pencere değeri de ölçülüp günlüğe
 *    yazılıyor ([backgroundKeepFraction]). Pencere geometrik olarak daha doğru ölçü ama eşiğin
 *    payı yok (prod: eşyalı oda 18,66 / eşik 18,0), o yüzden metrik dağılım görülmeden
 *    değiştirilmiyor.
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
        /**
         * Bu koşuda gerçekten İSTENEN açıklık. Normalde [TARGET_SPAN]; uzak referans bir
         * tavanla erken alındıysa kadraja sığan değere indirilmiştir. [spanRatio] bunsuz
         * okunamaz: 1,4 açıklık, hedef 2,0 iken "kullanıcı yaklaşmadı", hedef 1,4 iken
         * "hedefe ulaştı ama ölçüm zaten zayıf doğdu" demektir.
         */
        val targetSpan: Float,
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
         * Yakın karede yüz kutusunun kadraj genişliğinde kaplayabileceği en büyük oran.
         *
         * Bu bir MESAFE değil ÇERÇEVELEME sınırı, ve tam bu yüzden lensten (FOV) bağımsız:
         * kutu kenara dayandığında yüz kadrajdan taşar, ML Kit'in kutusu bozulur ve yüzün
         * etrafında ölçülecek arka plan kalmaz.
         */
        const val MAX_NEAR_FACE_FRACTION = 0.62f

        /**
         * 🔴 UZAKLAŞMANIN KABUL ÖLÇÜSÜ — MUTLAK: yüz kutusu kadraj genişliğinin bu oranına
         * inmeli. "Başladığın yere göre şu kadar küçül" DEĞİL.
         *
         * **Neden göreli ölçü terk edildi (2026-09-17 saha denemesi).** Eski kural "başlangıç
         * genişliğinin 1,22 katı kadar küçül" idi ve hedefle TUTARSIZDI: kullanıcı jestleri
         * büyük silüette bitirir (kadrajın ~%75'i), 1,22× geri çekilme onu %61'e indirir,
         * oradan [TARGET_SPAN] = 2,0'a ulaşmak %123 eder — yani yüzün kadrajdan TAŞMASI
         * gerekir. Hedef fiziksel olarak ulaşılamazdı. Sahada görülen tam olarak buydu:
         * kullanıcı uzaklaşırken bir an durdu, kural o İLK duraklamada kilitlendi ve ardından
         * erişilemeyen bir yakınlaşma istendi.
         *
         * Mutlak kuralın tanımı doğrudan **ulaşılabilirlik koşulu**:
         * `uzakOran × TARGET_SPAN ≤ MAX_NEAR_FACE_FRACTION`. Kapı "şu kadar uzaklaş" demiyor,
         * "hedefi kadraja sığdırabileceğin kadar uzaklaş" diyor. Kullanıcı zaten yeterince
         * uzakta başladıysa hiç geri çekilmesi gerekmez; bugünkü gereksiz sürtünme de kalkar.
         */
        const val RETREAT_TARGET_FRACTION = MAX_NEAR_FACE_FRACTION / TARGET_SPAN

        /**
         * Tavanlardan biriyle erken kabul edilen uzak referansta istenecek en küçük açıklık.
         *
         * Tavan yolunda hedef kadraja sığacak şekilde DÜŞÜRÜLÜR ([effectiveSpan]); bu da bir
         * taban olmazsa 1,0'a kadar inip yakınlaşma adımını anlamsız kılabilir.
         */
        private const val MIN_EFFECTIVE_SPAN = 1.15f

        /** Oran gürültüsü — bunun altındaki düşüş "hâlâ uzaklaşıyor" sayılmaz (~1 px). */
        private const val FRACTION_EPSILON = 0.002f

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

        /**
         * 🔴 Doku molasından çıkmak için ÜST ÜSTE kaç ölçüm yeterli gelmeli.
         *
         * Sahada bulunan kaçamak (2026-09-17): "arka planınız düz" uyarısı gelince telefonu
         * ileri geri oynatmak kapıyı açıyordu. Sebep tek ölçümle karar verilmesiydi — kadrajın
         * kenarından bir an geçen herhangi bir desen (tavan köşesindeki tahtalar) eşiği anlık
         * aşıyor ve mola bitiyordu. Ortam hiç düzelmemiş oluyordu; kullanıcı çabasını boşa
         * harcıyor, akış ölçülemeyecek karelerle devam ediyordu.
         */
        private const val BG_RECOVERY_SAMPLES = 3

        /**
         * Ardışık doku ölçümleri arasında yüz genişliğinin oynayabileceği pay.
         *
         * Kaçamağın hareketi tam olarak buydu: yaklaş-uzaklaş. Kadraj değişirken alınan
         * ölçümler aynı sahneyi ölçmüyor; seri kırılır ve mola sürer.
         */
        private const val BG_STABLE_WIDTH_TOLERANCE = 0.08f

        /**
         * Meşru parallaks bandının ALT ucu (yüz ölçeği / arka plan ölçeği) — 40 fotoğrafta
         * ölçülen bant 1,383-1,585. [backgroundKeepFraction] bunun üzerine kurulu.
         */
        private const val MIN_PARALLAX_RATIO = 1.38f

        /** Doku penceresi bundan daha fazla daraltılmaz — yoksa örneklenecek piksel kalmaz. */
        private const val MIN_KEEP_FRACTION = 0.5f

        /**
         * 🔴 Dokunun ölçüleceği pencere: yakın karede kadrajda KALACAK merkez bölge.
         *
         * Neden tüm kare YANLIŞ: ORB, arka plan desenini uzak VE yakın karede eşleştirmek
         * zorunda. Yalnız uzak karenin kenarında görünen bir desen yaklaşınca kadrajdan çıkar;
         * ölçüme katkısı sıfırdır ama tüm-kare ortalamasını yukarı çeker. Sahada gözlenen tam
         * bu: düz duvarın önündeki kullanıcı geçti, arkasındaki tek desen tavan köşesindeki
         * tahtalardı (2026-09-17).
         *
         * Pay, yüzün açıklığı değil ARKA PLANIN büyümesiyle belirlenir — parallaksın tanımı bu:
         * yüz `span` kadar büyürken arka plan yalnız `span / MIN_PARALLAX_RATIO` kadar büyür,
         * kadrajda kalan pay da bunun tersidir. [TARGET_SPAN] = 2,0 ve bant alt ucu 1,38 için
         * ≈ %69 — yüzü dışlayınca örneklenecek alanın kabaca dörtte biri kalır, bu yeterli.
         *
         * Ortalama ölçüldüğü için eşiğin yeniden kalibrasyonu ŞART DEĞİL: her yeri dokulu bir
         * odada pencere daraltmak ortalamayı oynatmaz, yalnız "ortası boş, kenarı kalabalık"
         * sahneyi doğru biçimde düşürür.
         */
        fun backgroundKeepFraction(span: Float): Float =
            (MIN_PARALLAX_RATIO / span).coerceIn(MIN_KEEP_FRACTION, 1f)

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
         * Arka plan doku eşiği (gradyan enerjisi). **18 → 14, gerçek koşulardan (2026-09-19).**
         *
         * İlk değer (18) tahmindi ve sahada meşru kullanıcının ÇOĞUNU eliyordu. On koşuluk
         * ilk dağılım:
         * ```
         * çıplak duvar    10,8 · 11,5 · 11,8 · 13,6      ← ölçülemez, elenmeli
         * mutfak dolabı   14,2
         * duvar + tablo   15,1
         * gece penceresi  15,8
         * perde           16,1
         * kitaplık        18,1
         * kapı + koridor  19,3
         * monitör düzeneği 22,1                          ← EN YÜKSEK, bkz. aşağıda
         * ```
         * 18 eşiği yedi meşru sahnenin BEŞİNİ reddediyordu (mutfak, perde, tablolu duvar, gece
         * penceresi ve sınır durumları). 14, on örneğin hepsini doğru ayırıyor; en yakın
         * yanlış-kabul adayı 13,6'lık çıplak duvar, pay 0,6. Pay ince ama hatanın ucuz tarafı
         * bu: eşik düşükse ölçüm sessizce başarısız olur, yüksekse meşru kullanıcı her seferinde
         * uyarı yiyor ve uyarıyı atlatmayı öğreniyor — sahada tam olarak bu oldu.
         *
         * 🔴 **Yüksek doku "meşru" DEMEK DEĞİL.** Monitör düzeneği listenin en yükseğini aldı:
         * ekran çerçevesi, masa ve monitörün arkasındaki gerçek oda hepsi gradyan üretiyor.
         * Bu kapı bir sahtecilik kapısı değil, ÖLÇÜLEBİLİRLİK kapısı — "ORB'un eşleştireceği
         * bir şey var mı". Düzeneği eleyecek olan B oranı (yüz ölçeği / arka plan ölçeği) ve o
         * henüz hesaplanmıyor.
         *
         * n = 10, tek cihaz, tek ev. Dağılım büyüdükçe yeniden bakılacak.
         */
        const val MIN_BACKGROUND_TEXTURE = 14f
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

    /**
     * Uzaklaşmanın başındaki yüz/kadraj oranı — YALNIZ ilerleme yüzdesini çizmek için.
     * Kabul ölçüsü artık buna değil, mutlak [RETREAT_TARGET_FRACTION]'a bakıyor.
     */
    private var startFraction = 0f

    /** Ulaşılan en küçük yüz/kadraj oranı = kullanıcının en uzak olduğu nokta. */
    private var minFraction = Float.MAX_VALUE
    private var minSeenAt = 0L
    private var farWidth = 0f

    /** Bu koşuda istenen açıklık — uzak referans alınırken belirlenir, bkz. [Result.targetSpan]. */
    private var effectiveSpan = TARGET_SPAN
    private var bgTexture = 0f

    /**
     * Merkez pencerede ölçülen doku — YALNIZ gözlem. Kapı ve sunucuya giden değer [bgTexture].
     * Gerekçe [capture] içinde: eşiğin payı yok, metriği değiştirmeden önce dağılım gerekiyor.
     */
    private var bgTextureWindow = 0f

    /** Molada üst üste kaç ölçüm eşiği geçti (bkz. [BG_RECOVERY_SAMPLES]). */
    private var bgGoodStreak = 0

    /** Seri sayılırken kadrajın kararlı kaldığını denetlemek için son ölçümdeki yüz genişliği. */
    private var bgLastWidth = 0f

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

        // ML Kit kutusu DÖNDÜRÜLMÜŞ (dik) görüntü uzayında verilir — InputImage
        // `rotationDegrees` ile kuruluyor (bkz. LivenessAnalyzer). Kadraj genişliği de aynı
        // uzayda alınmalı, yoksa 90°'de oran ters çıkar ve mutlak kapı anlamsızlaşır.
        val rot = imageProxy.imageInfo.rotationDegrees
        val frameW = (if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width).toFloat()
        if (frameW <= 0f) return

        when (phase) {
            Phase.RETREAT -> handleRetreat(imageProxy, face, w, frameW, now)
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
     *  1. yüz MUTLAK hedefe indi ([RETREAT_TARGET_FRACTION] — kadraj genişliğinin oranı),
     *  2. kare kullanıcının EN UZAK olduğu anda alınıyor (geri dönerken değil),
     *  3. o noktada bir an duruldu.
     *
     * 🔴 1. koşulun MUTLAK olması şart. Göreli hâlinde ("başladığın yerden %22 küçül") kural
     * kullanıcının nereden başladığına bağlıydı: uzaklaşırken bir an duran kullanıcıda o ilk
     * duraklamada kilitleniyor, sonra kadraja sığmayan bir yakınlaşma isteniyordu. Mutlak
     * hedef bunu kökten kapatır — hedef nerede durduğuna göre değişmez.
     */
    private fun handleRetreat(imageProxy: ImageProxy, face: Face, w: Float, frameW: Float, now: Long) {
        val fraction = w / frameW
        if (startFraction <= 0f) {
            startFraction = fraction
            minFraction = fraction
            minSeenAt = now
        }
        if (fraction < minFraction - FRACTION_EPSILON) {   // hâlâ uzaklaşıyor
            minFraction = fraction
            minSeenAt = now
        }

        val reached = minFraction <= RETREAT_TARGET_FRACTION
        val atMinimum = fraction <= minFraction * AT_MINIMUM_TOLERANCE
        val settled = now - minSeenAt >= RETREAT_SETTLE_MS
        val elapsed = now - retreatStartedAt

        val accept = when {
            reached && atMinimum && settled -> true
            // Yumuşak tavan: hedefe inemedi (kısa kol, dar oda, dar açılı lens) ama hiç
            // değilse en uzak noktasında. Hedef açıklık aşağıda kadraja sığacak şekilde
            // düşürülür; açıklık küçük kalırsa enclave "not_approached" yazar.
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
            val progress = when {
                !atMinimum -> -1f
                // Kullanıcı zaten hedefin ötesinde başladı: geri çekilecek bir şey yok,
                // yalnız durulması bekleniyor. %0 göstermek onu boşuna geri yürütürdü.
                startFraction <= RETREAT_TARGET_FRACTION -> 1f
                else -> ((startFraction - minFraction) /
                    (startFraction - RETREAT_TARGET_FRACTION)).coerceIn(0f, 1f)
            }
            onGuidance(Phase.RETREAT, progress)
            return
        }

        // 🔴 Hedef açıklık, doku ölçümünden ÖNCE belirlenir: doku yalnız yakın karede kadrajda
        // KALACAK bölgede anlamlıdır ve o bölgenin genişliği hedefe bağlı ([backgroundKeepFraction]).
        //
        // Hedef, uzak referansın gerçekten nerede alındığına göre kadraja sığacak biçimde
        // seçilir. Tavan yoluyla yeterince uzaklaşmadan kabul ettiysek 2,0'ı istemek ulaşılamaz
        // bir hedef dayatmak olur. Küçük açıklık zayıf sinyal demektir, SIFIR kare demek değildir;
        // enclave `ied_ratio`ya bakıp "not_approached" yazar ve kayıt yine düşmez.
        effectiveSpan = (MAX_NEAR_FACE_FRACTION / fraction)
            .coerceIn(MIN_EFFECTIVE_SPAN, TARGET_SPAN)

        // En uzak referans kare + arka plan doku kontrolü BURADA — kullanıcı bütün hareketi
        // yapmadan önce, ki ortamı düzeltebilsin.
        val captured = capture(imageProxy, face, w, textureKeep = backgroundKeepFraction(effectiveSpan))
        if (!captured) return

        Log.i(
            TAG,
            "Uzak referans: oran=${"%.3f".format(fraction)} " +
                "(hedef ${"%.3f".format(RETREAT_TARGET_FRACTION)}) " +
                "istenen açıklık=${"%.2f".format(effectiveSpan)} " +
                "doku=${"%.1f".format(bgTexture)} " +
                "(pencere ${"%.1f".format(bgTextureWindow)} @ " +
                "%${(backgroundKeepFraction(effectiveSpan) * 100).toInt()})"
        )

        if (!bgGateWaived && bgTexture < MIN_BACKGROUND_TEXTURE) {
            Log.i(TAG, "Arka plan dokusu yetersiz: $bgTexture < $MIN_BACKGROUND_TEXTURE")
            paths.removeLastOrNull()?.let { runCatching { File(it).delete() } }
            widths.removeLastOrNull()
            phase = Phase.BACKGROUND_POOR
            bgPoorSince = now
            bgGoodStreak = 0
            bgLastWidth = 0f
            onGuidance(Phase.BACKGROUND_POOR, 0f)
            onBackgroundPoor()
            // Yeniden denemeye açık: kullanıcı yer değiştirirse uzaklaşma baştan başlar.
            // ⚠️ startedAt SIFIRLANMAZ: toplam süre tek ve dürüst kalsın. Düzeltmede geçen
            // süre pausedMs'e yazılıp bütçeden düşülür.
            startFraction = 0f
            minFraction = Float.MAX_VALUE
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

        // 🔴 Tek ölçümle çıkılmaz. Sahada bulunan kaçamak: uyarı gelince telefonu ileri geri
        // oynatmak kapıyı açıyordu — kadrajın kenarından bir an geçen desen eşiği anlık aşıyor,
        // ortam hiç düzelmemiş olmasına rağmen mola bitiyordu. Artık hem ÜST ÜSTE
        // [BG_RECOVERY_SAMPLES] ölçüm hem de KARARLI kadraj aranıyor; yaklaş-uzaklaş hareketi
        // seriyi kırdığı için kaçamağın kendisi seriyi imkânsız kılıyor.
        val w = face.boundingBox.width().toFloat()
        val steady = bgLastWidth > 0f &&
            abs(w - bgLastWidth) <= bgLastWidth * BG_STABLE_WIDTH_TOLERANCE
        bgLastWidth = w

        bgGoodStreak = if (t >= MIN_BACKGROUND_TEXTURE && steady) bgGoodStreak + 1 else 0
        if (bgGoodStreak >= BG_RECOVERY_SAMPLES) {
            Log.i(TAG, "Arka plan düzeldi ($t, $bgGoodStreak ölçüm) — uzaklaşmaya dönülüyor")
            resumeRetreat(now, waited)
        }
    }

    /** Arka plan molasından uzaklaşma adımına dön; molada geçen süre bütçeden düşülür. */
    private fun resumeRetreat(now: Long, waited: Long) {
        pausedMs += waited
        phase = Phase.RETREAT
        retreatStartedAt = now
        startFraction = 0f
        minFraction = Float.MAX_VALUE
        minSeenAt = now
        onGuidance(Phase.RETREAT, 0f)
    }

    private fun handleApproach(imageProxy: ImageProxy, face: Face, w: Float, now: Long) {
        if (farWidth <= 0f) return

        val span = w / farWidth
        val progress = ((span - 1f) / (effectiveSpan - 1f)).coerceIn(0f, 1f)
        onGuidance(Phase.APPROACH, progress)

        // Hedef mesafeler eşit aralıklı: 1,00 → effectiveSpan arası FRAME_COUNT-1 adım.
        val step = (effectiveSpan - 1f) / (FRAME_COUNT - 1)
        val needed = 1f + step * nextTargetIndex
        if (span < needed) return
        if (now - lastFrameAt < FRAME_INTERVAL_MS) return

        if (capture(imageProxy, face, w, textureKeep = null)) {
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
                targetSpan = effectiveSpan,
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
    private fun capture(imageProxy: ImageProxy, face: Face, faceW: Float, textureKeep: Float?): Boolean {
        var srcRef: Bitmap? = null
        var fullRef: Bitmap? = null
        var scaledRef: Bitmap? = null
        return try {
            val bmp = imageProxy.toBitmap() ?: return false
            srcRef = bmp
            val m = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            fullRef = full

            if (textureKeep != null) {
                // 🔴 Kapı TÜM KARE değerine bakar, pencere değerine DEĞİL — bilerek.
                //
                // Pencere ([backgroundKeepFraction]) geometrik olarak doğru ölçü: yalnız uzak
                // karenin kenarında görünen desen ORB'a eşleştirecek bir şey vermez. Ama
                // 2026-09-17 prod verisi eşikte pay BIRAKMADIĞINI gösterdi: eşyalı oda 18,66,
                // çıplak duvarlar 10,8-13,6, eşik 18,0. Pencereye geçmek meşru sahnenin
                // değerini de düşürür ve o %3,7'lik payı yer — yani meşru kullanıcıyı gereksiz
                // uyarmaya başlarız. Önce dağılım, sonra eşik: pencere değeri şimdilik yalnız
                // ÖLÇÜLÜR ve günlüğe yazılır.
                bgTexture = textureOf(full, face.boundingBox, 1f)
                bgTextureWindow = textureOf(full, face.boundingBox, textureKeep)
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
            // Kapıyla AYNI ölçü: molayı açan sayı, molayı açtıran sayıyla aynı olmalı.
            textureOf(full, face.boundingBox, 1f)
        } catch (e: Exception) {
            null
        } finally {
            fullRef?.let { if (it !== srcRef) it.recycle() }
            srcRef?.recycle()
        }
    }

    /** Ölçü [BackgroundTexture]'ta — duruş dizisiyle aynı sayı olmak zorunda. */
    private fun textureOf(full: Bitmap, faceBox: Rect, keepFraction: Float): Float =
        BackgroundTexture.measure(full, faceBox, keepFraction)
}
