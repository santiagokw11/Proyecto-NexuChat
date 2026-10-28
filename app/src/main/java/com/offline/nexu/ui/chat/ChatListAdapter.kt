package com.offline.nexu.ui.chat

import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import java.text.SimpleDateFormat
import java.util.*

class ChatListAdapter(
    private val auraColor: Int,
    private val onChatClick: (ChatItemUI) -> Unit,
    private val onAvatarClick: (ChatItemUI) -> Unit,
    private val onChatLongClick: (ChatItemUI) -> Unit
) : ListAdapter<ChatItemUI, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    private var selectedEndpointId: String? = null

    fun setSelectedChat(endpointId: String?) {
        val previous = selectedEndpointId
        selectedEndpointId = endpointId

        val list = currentList
        val prevIndex = list.indexOfFirst { it.entity.endpointId == previous }
        val newIndex = list.indexOfFirst { it.entity.endpointId == endpointId }

        if (prevIndex != -1) notifyItemChanged(prevIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_list, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvChatName)
        private val tvLastMsg: TextView = itemView.findViewById(R.id.tvLastMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvChatTime)
        private val tvTimer: TextView = itemView.findViewById(R.id.tvChatTimer)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvAvatarText: TextView = itemView.findViewById(R.id.tvAvatarText)
        private val ivPin: ImageView = itemView.findViewById(R.id.ivPinIcon)
        private val ivMutedIcon: ImageView = itemView.findViewById(R.id.ivMutedIcon) // NUEVO

        fun bind(item: ChatItemUI) {
            tvName.text = item.displayTitle
            tvName.setTextColor(auraColor)

            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvTime.text = formatter.format(Date(item.entity.timestamp))

            val prefix = if (item.entity.isMine) "Tú: " else ""
            val msgContent = when {
                item.entity.imagePath != null -> "📸 Imagen adjunta"
                item.entity.text.isNotEmpty() -> item.entity.text
                else -> "Mensaje"
            }
            tvLastMsg.text = "$prefix$msgContent"

            ivPin.visibility = if (item.isPinned) View.VISIBLE else View.GONE
            ivPin.setColorFilter(auraColor)

            // Mostramos o escondemos la campanita silenciada
            ivMutedIcon.visibility = if (item.isMuted) View.VISIBLE else View.GONE

            if (item.avatar.startsWith("file://") || item.avatar.startsWith("content://") || item.avatar.startsWith("/")) {
                tvAvatarText.visibility = View.GONE
                ivAvatar.visibility = View.VISIBLE
                try { ivAvatar.setImageURI(Uri.parse(item.avatar)) }
                catch (e: Exception) {
                    tvAvatarText.visibility = View.VISIBLE
                    ivAvatar.visibility = View.GONE
                    tvAvatarText.text = if (item.entity.isGroup) "👥" else "👤"
                }
            } else {
                ivAvatar.visibility = View.GONE
                tvAvatarText.visibility = View.VISIBLE
                tvAvatarText.text = item.avatar
            }

            if (item.groupExpirationTime != null) {
                val remaining = item.groupExpirationTime - System.currentTimeMillis()
                if (remaining > 0) {
                    tvTimer.visibility = View.VISIBLE
                    val s = (remaining / 1000) % 60
                    val m = (remaining / (1000 * 60)) % 60
                    val h = (remaining / (1000 * 60 * 60)) % 24
                    val d = remaining / (1000 * 60 * 60 * 24)

                    val timeStr = when {
                        d > 0 -> "${d}d ${h}h"
                        h > 0 -> "${h}h ${m}m ${s}s"
                        else -> "${m}m ${s}s"
                    }
                    tvTimer.text = "⏱ $timeStr"
                } else {
                    tvTimer.visibility = View.GONE
                }
            } else {
                tvTimer.visibility = View.GONE
            }

            if (item.entity.endpointId == selectedEndpointId) {
                itemView.setBackgroundColor(ColorUtils.setAlphaComponent(auraColor, 50))
            } else {
                val outValue = TypedValue()
                itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                itemView.setBackgroundResource(outValue.resourceId)
            }

            itemView.setOnClickListener { onChatClick(item) }
            itemView.setOnLongClickListener { onChatLongClick(item); true }
            ivAvatar.setOnClickListener { onAvatarClick(item) }
            tvAvatarText.setOnClickListener { onAvatarClick(item) }
        }
    }
}

class ChatDiffCallback : DiffUtil.ItemCallback<ChatItemUI>() {
    override fun areItemsTheSame(oldItem: ChatItemUI, newItem: ChatItemUI) = oldItem.entity.endpointId == newItem.entity.endpointId
    override fun areContentsTheSame(oldItem: ChatItemUI, newItem: ChatItemUI) =
        oldItem.entity.msgId == newItem.entity.msgId &&
                oldItem.isPinned == newItem.isPinned &&
                oldItem.isMuted == newItem.isMuted &&
                oldItem.groupExpirationTime == newItem.groupExpirationTime
}