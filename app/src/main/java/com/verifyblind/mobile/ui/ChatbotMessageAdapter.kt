package com.verifyblind.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.verifyblind.mobile.R

data class ChatTurn(
    val role: String,        // "user" | "assistant"
    val content: String
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * Sade chat liste adapter'ı. Kullanıcı ve bot mesajları için iki ayrı layout.
 * VerifyBlind genel tasarım dili (HelpFragment) ile uyumlu görsel tonlama.
 */
class ChatbotMessageAdapter(
    private val items: MutableList<ChatTurn>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_BOT  = 2
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position].isUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val v = inflater.inflate(R.layout.item_chatbot_message_user, parent, false)
            UserVH(v)
        } else {
            val v = inflater.inflate(R.layout.item_chatbot_message_bot, parent, false)
            BotVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val turn = items[position]
        when (holder) {
            is UserVH -> holder.tvText.text = turn.content
            is BotVH  -> holder.tvText.text = turn.content
        }
    }

    fun append(turn: ChatTurn) {
        items.add(turn)
        notifyItemInserted(items.size - 1)
    }

    fun replaceAll(newItems: List<ChatTurn>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun snapshot(): List<ChatTurn> = items.toList()

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvText)
    }

    class BotVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvText)
    }
}
