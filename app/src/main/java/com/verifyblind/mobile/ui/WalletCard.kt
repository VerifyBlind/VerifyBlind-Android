package com.verifyblind.mobile.ui

data class WalletCard(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
    val lastUsed: String,
    val expiryDate: String = "—",
    /** Belge geçerlilik tarihi bugünden önceyse true → rozet "SÜRESİ DOLDU" gösterir. */
    val expired: Boolean = false
)
