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
 *  • Kullanıcı başına en fazla HAFTADA BİR. Aynı akşam üç kez deneyip vazgeçen birine her seferinde
 *    sormak dırdır olur ve uygulama kullanıcıyı suçluyormuş gibi hissettirir.
 *
 * Karar tamamen CİHAZDA verilir — sunucuda "şu kullanıcıya soruldu mu" diye kayıt tutulmaz.
 */
object FlowFeedbackPrompt {

    private const val PREFS = "VerifyBlind_Prefs"
    private const val KEY_LAST_SHOWN = "feedback_prompt_last_shown"
    private const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

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
