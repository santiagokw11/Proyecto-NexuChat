package com.offline.nexu.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.databinding.ItemChatListBinding

data class ChatItemUI(val entity: com.offline.nexu.data.local.db.MessageEntity, val isPinned: Boolean)

class ChatListAdapter(
    private val auraColor: Int, // NUEVO: Recibe el color del Aura
    private val onChatClick: (String) -> Unit,
    private val onAvatarClick: (String) -> Unit,
    private val onChatLongClick: (String) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    private var chats = listOf<ChatItemUI>()

    fun submitList(list: List<ChatItemUI>) {
        chats = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = chats[position]
        val chat = item.entity

        holder.binding.tvName.text = chat.endpointId
        // NUEVO: Aplica el color del Aura al nombre del contacto en la lista
        holder.binding.tvName.setTextColor(auraColor)

        holder.binding.tvLastMessage.text = chat.text.ifEmpty { "📷 Imagen compartida" }

        holder.binding.root.setOnClickListener { onChatClick(chat.endpointId) }
        holder.binding.root.setOnLongClickListener { onChatLongClick(chat.endpointId); true }

        val avatarView = holder.itemView.findViewById<View>(R.id.ivAvatar)
        avatarView?.setOnClickListener { onAvatarClick(chat.endpointId) }

        val pinView = holder.itemView.findViewById<View>(R.id.tvPin)
        pinView?.visibility = if (item.isPinned) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = chats.size
    class ViewHolder(val binding: ItemChatListBinding) : RecyclerView.ViewHolder(binding.root)
}