package com.verifyblind.mobile.ui

import com.verifyblind.mobile.data.HistoryEntity

/**
 * İşlem geçmişi listesi gösterim kararları (saf/UI-bağımsız → birim testi edilebilir).
 */
object HistoryDisplay {

    /**
     * Listede birden fazla FARKLI (boş olmayan) cihaz adı varsa cihaz adı satırı gösterilir.
     * Tek cihazlı (veya cihaz adı olmayan eski) kullanıcıyı meşgul etmemek için ≤1'de gizlenir.
     * Not: `items` çözülmüş (decrypted) `deviceName` içermeli.
     */
    fun shouldShowDevice(items: List<HistoryEntity>): Boolean =
        items.asSequence()
            .map { it.deviceName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(2)
            .count() >= 2
}
