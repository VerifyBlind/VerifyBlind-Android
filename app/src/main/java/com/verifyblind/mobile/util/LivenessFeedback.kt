package com.verifyblind.mobile.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.verifyblind.mobile.R

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
        try {
            soundPool.release()
        } catch (e: Exception) {
            AppLog.warning("SoundPool release hatası: ${e.message}", TAG)
        }
    }

    private fun play(id: Int) {
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
    }
}
