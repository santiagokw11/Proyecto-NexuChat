package com.offline.nexu.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.databinding.ItemChatListBinding

class ChatListAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {
    private var chats = listOf<MessageEntity>()

    fun submitList(list: List<MessageEntity>) {
        chats = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chat = chats[position]
        holder.binding.tvName.text = chat.endpointId
        holder.binding.tvLastMessage.text = chat.text.ifEmpty { "📷 Imagen" }
        holder.binding.root.setOnClickListener { onClick(chat.endpointId) }
    }

    override fun getItemCount() = chats.size
    class ViewHolder(val binding: ItemChatListBinding) : RecyclerView.ViewHolder(binding.root)
}