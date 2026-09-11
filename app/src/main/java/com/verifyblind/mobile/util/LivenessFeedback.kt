package com.verifyblind.mobile.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.verifyblind.mobile.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Liveness jest geri bildirimi — ses + titreşim.
 *
 * "Başını SAĞA çevir" komutunu yerine getiren kullanıcı EKRANI GÖREMİYOR. Onay yalnızca görsel
 * (✅) olduğu sürece hareketinin kabul edildiğini fark edemiyor, bekliyor ve süreyi harcıyor
 * (kullanıcı geri bildirimi 2026-08-21). Ses cihazın medya sesine bağlı olduğundan titreşim
 * HER ZAMAN yanında verilir — sessizdeki kullanıcı da geri bildirimsiz kalmaz.
 *
 * Ses dosyaları iOS `Resources/liveness_*.wav` ile **birebir aynıdır** (aynı üretici, aynı dalga
 * formu) → iki platformda aynı işitsel dil.
 *
 * Nesne [com.verifyblind.mobile.LivenessActivity] açılır açılmaz kurulur, ilk jestte DEĞİL:
 * `SoundPool.load()` asenkrondur ve yükleme bitmeden yapılan `play()` çağrısı sessizce yutulur
 * (bkz. [loadedSamples]).
 */
class LivenessFeedback(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /**
     * Yüklemesi tamamlanmış sample'lar.
     *
     * `SoundPool.load()` ASENKRONDUR: sample decode edilene kadar `play()` hata vermez, istisna
     * atmaz — yalnızca 0 döner ve HİÇBİR ŞEY çalmaz. Cihazda görüldü (2026-09-11): ilk doğru
     * jestte titreşim geliyor ama ses gelmiyor, sonraki jestlerde ikisi de geliyordu. Titreşim
     * [Vibrator] üzerinden anında çalıştığı için arıza yalnız ses tarafında görünüyordu.
     */
    private val loadedSamples = ConcurrentHashMap.newKeySet<Int>()

    /** Yükleme bitmeden istenen çalma; sample hazır olur olmaz çalınır. 0 = bekleyen yok. */
    @Volatile private var pendingSample = 0
    @Volatile private var pendingSince = 0L

    init {
        // Dinleyici yüklemelerden ÖNCE kurulur — hızlı biten bir yükleme callback'i kaçmasın.
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                AppLog.warning("Jest sesi yüklenemedi: sampleId=$sampleId status=$status", TAG)
                return@setOnLoadCompleteListener
            }
            loadedSamples.add(sampleId)
            if (pendingSample == sampleId) {
                pendingSample = 0
                // Geç kalan onay sesi kafa karıştırır: jest çoktan geçtiyse sessiz kal.
                if (SystemClock.uptimeMillis() - pendingSince <= PENDING_MAX_WAIT_MS) playNow(sampleId)
            }
        }
    }

    private val okId = soundPool.load(context, R.raw.liveness_ok, 1)
    private val wrongId = soundPool.load(context, R.raw.liveness_wrong, 1)
    private val doneId = soundPool.load(context, R.raw.liveness_done, 1)

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        AppLog.warning("Titreşim servisi alınamadı: ${e.message}", TAG)
        null
    }

    /** Hareket kabul edildi. */
    fun stepOk() {
        play(okId)
        vibrate(longArrayOf(0, 40))
    }

    /** Yanlış hareket — onaydan net ayrışan çift titreşim. */
    fun wrong() {
        play(wrongId)
        vibrate(longArrayOf(0, 45, 70, 45))
    }

    /** Tüm dizi tamamlandı. */
    fun done() {
        play(doneId)
        vibrate(longArrayOf(0, 90))
    }

    /**
     * Hareket süresi azalıyor. SESSİZDİR: 4. bir ton kullanıcıyı şaşırtır; hafif bir dokunuş
     * "acele et" demeye yeter ve kafa çevrikken de hissedilir.
     */
    fun nudge() {
        vibrate(longArrayOf(0, 20))
    }

    fun release() {
        pendingSample = 0
        try {
            soundPool.release()
        } catch (e: Exception) {
            AppLog.warning("SoundPool release hatası: ${e.message}", TAG)
        }
    }

    /** Sample hazırsa hemen çalar, değilse yükleme bitene kadar kuyruklar (bkz. [loadedSamples]). */
    private fun play(id: Int) {
        if (loadedSamples.contains(id)) {
            playNow(id)
            return
        }
        pendingSince = SystemClock.uptimeMillis()
        pendingSample = id
    }

    private fun playNow(id: Int) {
        try {
            soundPool.play(id, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {
            AppLog.warning("Jest sesi çalınamadı: ${e.message}", TAG)
        }
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            AppLog.warning("Titreşim başarısız: ${e.message}", TAG)
        }
    }

    private companion object {
        const val TAG = "LivenessFeedback"

        /**
         * Kuyruklanmış sesin çalınabileceği en geç an. Küçük wav'lar normalde 200 ms'nin altında
         * yüklenir; bunun ötesinde gelen onay sesi artık hangi jesti onayladığı belirsiz olur.
         */
        const val PENDING_MAX_WAIT_MS = 1200L
    }
}
