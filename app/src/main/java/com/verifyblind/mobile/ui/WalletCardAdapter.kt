package com.verifyblind.mobile.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    }

    override fun getItemCount() = cards.size
}
