package com.verifyblind.mobile.util

import android.content.Context
import com.verifyblind.mobile.R

/**
 * Kart ekleme akışı yarıda kalınca "bir sorun mu yaşadınız?" sorma kararı — iOS
 * `FlowFeedbackPrompt` paritesi.
 *
 * İki kural:
 *  • Yalnız HATA sonrası sorulur, sıradan vazgeçişte değil. Telefonu çaldığı için çıkan kullanıcıya
 *    destek kutusu göstermek gereksiz gürültüdür.
 *  • Sıklık sınırlı (bkz. [INTERVAL_MS]). Aynı olaydan doğan ikinci tetik yutulur; aksi halde
 *    uygulama kullanıcıyı suçluyormuş gibi hissettirir.
 *
 * Karar tamamen CİHAZDA verilir — sunucuda "şu kullanıcıya soruldu mu" diye kayıt tutulmaz.
 */
object FlowFeedbackPrompt {

    private const val PREFS = "VerifyBlind_Prefs"
    private const val KEY_LAST_SHOWN = "feedback_prompt_last_shown"
    /**
     * KALICI DEĞER. Haftalık sınıra DÖNÜLMEYECEK (2026-08-26 kararı) — bu bir test ayarı değil.
     *
     * Haftalık sınır kâğıt üzerinde "kullanıcıyı yormayan ürün" gibi görünür, pratikte tersini
     * yapar: 2026-08-24'te bir kullanıcı 60 saniye arayla İKİ FARKLI sebeple (anti-spoof, sonra
     * çip imzası) düştü ve sınır ikinci raporu yuttu — tam da öğrenmemiz gereken vakayı.
     * Her ayrı hata AYRI bir arıza yüzeyidir ve sorabildiğimiz tek an, hatanın hemen ardındaki
     * andır. Sınırın işi AYNI olaydan doğan ikinci tetiği yutmaktır, farklı bir olayı susturmak
     * değil — 60 saniye tam olarak bunu yapar.
     */
    private const val INTERVAL_MS = 60L * 1000

    /** Kayıt akışının hangi adımında takılındı — konu satırını ön-doldurmak için. */
    enum class FlowStep(val labelRes: Int) {
        MRZ(R.string.feedback_step_mrz),
        NFC(R.string.feedback_step_nfc),
        LIVENESS(R.string.feedback_step_liveness),
        SUBMIT(R.string.feedback_step_submit),
    }

    fun shouldOffer(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SHOWN, 0L)
        return last == 0L || now - last >= INTERVAL_MS
    }

    /**
     * Kutu GÖSTERİLDİĞİNDE çağrılır (kullanıcı "hayır" dese de sayaç işler — asıl amaç sıklığı
     * sınırlamak, cevabı kaydetmek değil).
     */
    fun markShown(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SHOWN, now)
            .apply()
    }

    /** "Kart ekleme sorunu: Çip okuma" gibi hazır bir konu satırı. */
    fun subject(context: Context, step: FlowStep): String =
        context.getString(R.string.feedback_subject_card_add, context.getString(step.labelRes))
}
