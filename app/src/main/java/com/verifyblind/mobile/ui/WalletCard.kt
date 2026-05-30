package com.verifyblind.mobile.ui

data class WalletCard(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
    val lastUsed: String,
    val expiryDate: String = "—"
)
