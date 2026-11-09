package com.offline.nexu.ui.chat

import android.content.res.ColorStateList
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
    private val onChatLongClick: (ChatItemUI) -> Unit,
    private val onSelectionModeChanged: (Boolean, Int) -> Unit
) : ListAdapter<ChatItemUI, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    var isSelectionMode = false
    val selectedChats = mutableSetOf<String>()
    private var selectedEndpointId: String? = null

    fun toggleSelection(endpointId: String) {
        if (selectedChats.contains(endpointId)) {
            selectedChats.remove(endpointId)
        } else {
            selectedChats.add(endpointId)
        }

        isSelectionMode = selectedChats.isNotEmpty()
        onSelectionModeChanged(isSelectionMode, selectedChats.size)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedChats.clear()
        onSelectionModeChanged(false, 0)
        notifyDataSetChanged()
    }

    fun setSelectedChat(endpointId: String?) {
        selectedEndpointId = endpointId
        notifyDataSetChanged()
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
        private val ivMutedIcon: ImageView = itemView.findViewById(R.id.ivMutedIcon)
        private val vOnlineIndicator: View = itemView.findViewById(R.id.vOnlineIndicator)
        private val ivMsgStatus: ImageView = itemView.findViewById(R.id.ivMsgStatus)
        private val tvUnreadBadge: TextView = itemView.findViewById(R.id.tvUnreadBadge)

        fun bind(item: ChatItemUI) {
            tvName.text = item.displayTitle
            tvName.setTextColor(auraColor)

            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvTime.text = formatter.format(Date(item.entity.timestamp))

            val prefix = if (item.entity.isMine && item.entity.status != -1) "Tú: " else ""

            val msgContent = when {
                item.entity.status == -1 -> " "
                item.entity.imagePath != null -> {
                    val path = item.entity.imagePath.toString().lowercase()
                    when {
                        path.endsWith(".mp4") || path.contains("vid_") || path.contains("video") -> "🎥 Video adjunto"
                        path.endsWith(".pdf") || path.contains("doc_") || path.contains("document") -> "📄 Documento adjunto"
                        else -> "📸 Imagen adjunta"
                    }
                }
                item.entity.text?.isNotEmpty() == true -> item.entity.text
                else -> "Mensaje"
            }
            tvLastMsg.text = "$prefix$msgContent"

            // AHORA LEE EL ESTADO EN TIEMPO REAL DESDE EL MODELO
            if (!item.entity.isGroup) {
                vOnlineIndicator.visibility = View.VISIBLE
                if (item.isOnline) {
                    vOnlineIndicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                } else {
                    vOnlineIndicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                }
            } else {
                vOnlineIndicator.visibility = View.GONE
            }

            if (item.entity.isMine && item.entity.status != -1 && item.entity.text?.startsWith("SYS:") == false) {
                ivMsgStatus.visibility = View.VISIBLE
                when (item.entity.status) {
                    0 -> ivMsgStatus.setImageResource(R.drawable.ic_msg_clock)
                    1 -> ivMsgStatus.setImageResource(R.drawable.ic_msg_sent)
                    2 -> ivMsgStatus.setImageResource(R.drawable.ic_msg_delivered)
                    3 -> ivMsgStatus.setImageResource(R.drawable.ic_msg_delivered)
                    else -> ivMsgStatus.visibility = View.GONE
                }
            } else {
                ivMsgStatus.visibility = View.GONE
            }

            ivPin.visibility = if (item.isPinned) View.VISIBLE else View.GONE
            ivPin.setColorFilter(auraColor)

            ivMutedIcon.visibility = if (item.isMuted) View.VISIBLE else View.GONE

            val isSelected = selectedChats.contains(item.entity.endpointId)

            if (isSelected) {
                tvAvatarText.visibility = View.VISIBLE
                ivAvatar.visibility = View.GONE
                tvAvatarText.text = "✓"
                (itemView.findViewById<androidx.cardview.widget.CardView>(R.id.cvAvatar)).setCardBackgroundColor(auraColor)
                tvAvatarText.setTextColor(Color.WHITE)
            } else {
                (itemView.findViewById<androidx.cardview.widget.CardView>(R.id.cvAvatar)).setCardBackgroundColor(Color.TRANSPARENT)
                tvAvatarText.setTextColor(Color.WHITE)

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
                    (itemView.findViewById<androidx.cardview.widget.CardView>(R.id.cvAvatar)).setCardBackgroundColor(Color.parseColor("#444444"))
                }
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

            if (item.unreadCount > 0) {
                tvUnreadBadge.visibility = View.VISIBLE
                tvUnreadBadge.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
                tvUnreadBadge.backgroundTintList = ColorStateList.valueOf(auraColor)
                tvUnreadBadge.setPadding(12, 4, 12, 4)
            } else if (item.forceUnread) {
                tvUnreadBadge.visibility = View.VISIBLE
                tvUnreadBadge.text = ""
                tvUnreadBadge.backgroundTintList = ColorStateList.valueOf(auraColor)
                tvUnreadBadge.setPadding(16, 16, 16, 16)
            } else {
                tvUnreadBadge.visibility = View.GONE
            }

            if (isSelected || item.entity.endpointId == selectedEndpointId) {
                itemView.setBackgroundColor(ColorUtils.setAlphaComponent(auraColor, 50))
            } else {
                val outValue = TypedValue()
                itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                itemView.setBackgroundResource(outValue.resourceId)
            }

            itemView.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.entity.endpointId) else onChatClick(item)
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    onChatLongClick(item)
                } else {
                    toggleSelection(item.entity.endpointId)
                }
                true
            }

            val toggleAction: (View) -> Boolean = {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    toggleSelection(item.entity.endpointId)
                } else {
                    toggleSelection(item.entity.endpointId)
                }
                true
            }

            ivAvatar.setOnLongClickListener(toggleAction)
            tvAvatarText.setOnLongClickListener(toggleAction)

            ivAvatar.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.entity.endpointId) else onAvatarClick(item)
            }
            tvAvatarText.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.entity.endpointId) else onAvatarClick(item)
            }
        }
    }
}

class ChatDiffCallback : DiffUtil.ItemCallback<ChatItemUI>() {
    override fun areItemsTheSame(oldItem: ChatItemUI, newItem: ChatItemUI) = oldItem.entity.endpointId == newItem.entity.endpointId
    override fun areContentsTheSame(oldItem: ChatItemUI, newItem: ChatItemUI) =
        oldItem.entity.msgId == newItem.entity.msgId &&
                oldItem.isPinned == newItem.isPinned &&
                oldItem.isMuted == newItem.isMuted &&
                oldItem.groupExpirationTime == newItem.groupExpirationTime &&
                oldItem.unreadCount == newItem.unreadCount &&
                oldItem.forceUnread == newItem.forceUnread &&
                oldItem.isOnline == newItem.isOnline // AÑADIDO PARA ACTUALIZACIÓN EN TIEMPO REAL
}