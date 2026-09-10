package com.verifyblind.mobile.nfc

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Asgari yaş kapısı. Kullanım Şartları "on beş yaşını doldurmamış kullanıcılar" hizmet kapsamı
 * dışında der; bu kural 2026-09-11'e kadar KODDA HİÇ UYGULANMIYORDU — sözleşme bir şey diyor,
 * uygulama başka şey yapıyordu.
 *
 * **Neden [DocumentSupport]'tan ayrı bir sınıf:** DocumentSupport belgenin *teknik olarak
 * işlenebilir* olup olmadığına bakar (ülke, tip, DG2 formatı, AA). Yaş ise bir *politika*
 * kararıdır — belge kusursuz okunur, akış yine de reddedilir. İkisini karıştırmak, sınır 15'ten
 * 18'e çekildiğinde teknik kapıyı da kurcalamak demek olurdu.
 *
 * **Neden fotoğraf kontrolünden ÖNCE çağrılır:** 15 yaşını doldurmamış birinin kartı zaten
 * fotoğrafsız düzenlenir (fotoğraf yalnızca veli talebi ya da seyahat belgesi beyanıyla eklenir).
 * Yaş bakılmadan önce fotoğrafa bakılırsa kullanıcı "çipte fotoğraf yok" mesajını alır ve
 * **nüfus müdürlüğünden fotoğraflı kart çıkartırsa sorunun çözüleceğini sanır** — çözülmez,
 * çünkü asıl engel yaştır. Sıra bu yüzden yaş → fotoğraf.
 *
 * Bu sınıf kullanıcıya doğru mesajı göstermek içindir; TCKN/doğum tarihi cihazdan çıkmaz.
 * Saf fonksiyon (Android tipi bağımlılığı yok) → birim testlerle kapsanır.
 */
object AgePolicy {

    /** Hizmetin açık olduğu en küçük yaş. Kullanım Şartları'ndaki ifadeyle hizalı tutulur. */
    const val MINIMUM_AGE = 15

    enum class Verdict {
        /** Yaş sınırı karşılanıyor (ya da doğum tarihi okunamadı → karar enclave'e bırakılır). */
        ALLOWED,
        /** Kullanıcı [MINIMUM_AGE] yaşını doldurmamış → kayıt reddedilir. */
        UNDER_MINIMUM_AGE
    }

    /**
     * MRZ'nin `YYMMDD` doğum tarihini bugünkü tarihe göre değerlendirir.
     *
     * @param mrzDateOfBirth ICAO MRZ doğum tarihi alanı (6 hane, `YYMMDD`).
     * @param today Testlerde sabitlenebilsin diye dışarıdan verilir.
     */
    fun evaluate(mrzDateOfBirth: String?, today: LocalDate = LocalDate.now()): Verdict {
        val birthDate = parseMrzDate(mrzDateOfBirth, today) ?: return Verdict.ALLOWED
        val age = yearsBetween(birthDate, today)
        return if (age < MINIMUM_AGE) Verdict.UNDER_MINIMUM_AGE else Verdict.ALLOWED
    }

    /**
     * MRZ `YYMMDD` → [LocalDate]. Ayrıştırılamayan değerde **null** döner ve çağıran akışı
     * SÜRDÜRÜR: istemcideki bu kapı yalnızca erken/net mesaj içindir, bozuk bir MRZ okuması
     * yüzünden meşru kullanıcıyı kilitlemek yanlış olur. Nihai otorite enclave'dedir.
     *
     * **Yüzyıl kuralı:** MRZ yılı 2 hanedir, yani "30" hem 1930 hem 2030 olabilir. Doğum tarihi
     * geçmişte olmak zorunda olduğundan: 2000'li yüzyıl varsayılır, sonuç bugünden İLERİDEYSE
     * 1900'e düşülür. Böylece 2026'da "27" → 1927 (2027 henüz gelmedi), "10" → 2010 olur.
     */
    private fun parseMrzDate(value: String?, today: LocalDate): LocalDate? {
        val digits = value?.trim()?.replace("<", "") ?: return null
        if (digits.length != 6 || !digits.all { it.isDigit() }) return null

        val yy = digits.substring(0, 2).toInt()
        val mm = digits.substring(2, 4).toInt()
        val dd = digits.substring(4, 6).toInt()

        return try {
            val candidate = LocalDate.of(2000 + yy, mm, dd)
            if (candidate.isAfter(today)) LocalDate.of(1900 + yy, mm, dd) else candidate
        } catch (e: DateTimeParseException) {
            null
        } catch (e: java.time.DateTimeException) {
            // Ay/gün aralık dışı (ör. "991332") → ayrıştırılamaz say, kararı enclave'e bırak.
            null
        }
    }

    /**
     * Tam yıl cinsinden yaş. Doğum günü henüz gelmediyse bir eksiltir — "15 yaşını doldurmuş
     * olmak" ifadesinin karşılığı budur.
     */
    private fun yearsBetween(birthDate: LocalDate, today: LocalDate): Int {
        var age = today.year - birthDate.year
        if (today.monthValue < birthDate.monthValue ||
            (today.monthValue == birthDate.monthValue && today.dayOfMonth < birthDate.dayOfMonth)
        ) {
            age--
        }
        return age
    }
}
