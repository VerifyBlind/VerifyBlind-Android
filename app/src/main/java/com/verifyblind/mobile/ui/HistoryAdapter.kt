package com.verifyblind.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.verifyblind.mobile.R
import com.verifyblind.mobile.data.HistoryEntity
import com.verifyblind.mobile.data.PartnerItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : ListAdapter<HistoryEntity, RecyclerView.ViewHolder>(HistoryDiffCallback()) {

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_LOADING = 1
    }

    var partners: Map<String, PartnerItem> = emptyMap()
    private var isLoading = false

    fun updatePartners(newPartners: Map<String, PartnerItem>) {
        partners = newPartners
        notifyDataSetChanged()
    }

    fun setLoadingState(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = currentList.size + if (isLoading) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position < currentList.size) TYPE_ITEM else TYPE_LOADING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ITEM) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            HistoryViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_loading, parent, false)
            LoadingViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HistoryViewHolder && position < currentList.size) {
            holder.bind(getItem(position))
        }
    }

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)

        fun bind(item: HistoryEntity) {
            val context = itemView.context

            // Resolve Partner Info
            val partner = item.partnerId?.let { partners[it] }
            if (item.actionType == 2) {
                android.util.Log.d("VerifyBlind_History", "Öğe bağlanıyor ${item.id}. PartnerID: ${item.partnerId}, Bulundu: ${partner?.name ?: "YOK"}. Harita boyutu: ${partners.size}")
            }

            // Resolve Title and Description based on ActionType
            val (t, d) = when (item.actionType) {
                1 -> {
                    val regTitle = context.getString(R.string.history_action_registration)
                    val regDesc = context.getString(R.string.history_desc_registration)
                    if (item.revokeTime != null) "$regTitle ${context.getString(R.string.history_consent_withdrawn)}" to regDesc else regTitle to regDesc
                }
                2 -> {
                    // Shared Identity
                    val baseDesc = context.getString(R.string.history_action_shared)
                    val finalDesc = if (item.revokeTime != null) "$baseDesc ${context.getString(R.string.history_revoked_tag)}" else baseDesc

                    if (partner != null) {
                        partner.name to finalDesc
                    } else {
                        // Fallback: Extract partner name from description if possible
                        val fallbackName = item.description.removePrefix("Partner: ").trim()
                        if (fallbackName != item.description.trim() && fallbackName.isNotEmpty()) {
                            fallbackName to finalDesc
                        } else {
                            context.getString(R.string.history_action_shared) to context.getString(R.string.history_desc_shared)
                        }
                    }
                }
                3 -> context.getString(R.string.history_action_deleted) to context.getString(R.string.history_desc_deleted)
                4 -> context.getString(R.string.history_action_restored) to context.getString(R.string.history_desc_restored)
                5 -> context.getString(R.string.history_action_revoked) to context.getString(R.string.history_desc_revoked)
                else -> item.title to item.description
            }
            tvTitle.text = t
            tvDescription.text = d

            // Format date to: 14 Feb 22:45
            val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            tvDate.text = sdf.format(Date(item.timestamp))

            // Reset icon state (Fix for RecyclerView recycling)
            ivIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            ivIcon.setPadding(8, 8, 8, 8)
            ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)

            // Status & Icon
            if (item.actionType == 2 && partner != null) {
                // Shared Identity with Partner -> Use Partner Logo if available
                if (item.revokeTime != null) {
                    // Revoked - use gray error icon
                    ivIcon.setImageResource(R.drawable.ic_error)
                    ivIcon.setBackgroundResource(R.drawable.bg_circle_gray)
                    ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                } else if (!partner.logoBase64.isNullOrEmpty()) {
                    try {
                        val cleanBase64 = partner.logoBase64
                            .replace("data:image/png;base64,", "")
                            .replace("data:image/jpeg;base64,", "")
                            .trim()
                        val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            ivIcon.setImageBitmap(bitmap)
                            ivIcon.background = null
                            ivIcon.setPadding(0, 0, 0, 0)
                            ivIcon.imageTintList = null
                        } else {
                            setDefaultIcon(item.status, item.revokeTime != null)
                        }
                    } catch (e: Exception) {
                        setDefaultIcon(item.status, item.revokeTime != null)
                    }
                } else {
                    setDefaultIcon(item.status, item.revokeTime != null)
                }
            } else {
                setDefaultIcon(item.status, item.actionType == 5 || item.revokeTime != null)
            }
        }

        private fun setDefaultIcon(status: Int, isRevoked: Boolean) {
            if (isRevoked) {
                ivIcon.setImageResource(R.drawable.ic_error)
                ivIcon.setBackgroundResource(R.drawable.bg_circle_gray)
                return
            }
            when (status) {
                1 -> {
                    ivIcon.setImageResource(R.drawable.ic_check_circle)
                    ivIcon.setBackgroundResource(R.drawable.bg_circle_green)
                }
                2 -> {
                    ivIcon.setImageResource(R.drawable.ic_error)
                    ivIcon.setBackgroundResource(R.drawable.bg_circle_red)
                }
                else -> {
                    ivIcon.setImageResource(R.drawable.ic_history_24)
                    ivIcon.setBackgroundResource(R.drawable.bg_circle_gray)
                }
            }
        }
    }

    class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}

class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryEntity>() {
    override fun areItemsTheSame(oldItem: HistoryEntity, newItem: HistoryEntity): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: HistoryEntity, newItem: HistoryEntity): Boolean =
        oldItem == newItem
}
