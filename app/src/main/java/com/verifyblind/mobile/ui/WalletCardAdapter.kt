package com.verifyblind.mobile.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.verifyblind.mobile.R
import com.verifyblind.mobile.databinding.ItemWalletCardBinding

class WalletCardAdapter(private val cards: List<WalletCard>) :
    RecyclerView.Adapter<WalletCardAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemWalletCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWalletCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        holder.binding.tvCardName.text = card.name
        holder.binding.tvCardType.text = card.type
        holder.binding.tvExpiryDate.text = card.expiryDate

        // AKTİF / SÜRESİ DOLDU — belge geçerlilik tarihinden türetilir (statik etiket değil).
        val badge = holder.binding.tvActiveBadge
        val ctx = badge.context
        if (card.expired) {
            badge.text = ctx.getString(R.string.wallet_expired)
            badge.setTextColor(Color.parseColor("#EF4444"))
            badge.setBackgroundResource(R.drawable.bg_badge_expired)
        } else {
            badge.text = ctx.getString(R.string.wallet_active)
            badge.setTextColor(Color.parseColor("#00C853"))
            badge.setBackgroundResource(R.drawable.bg_badge_active)
        }
    }

    override fun getItemCount() = cards.size
}
