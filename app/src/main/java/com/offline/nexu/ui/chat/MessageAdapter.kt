package com.offline.nexu.ui.chat

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.model.Message
import com.offline.nexu.databinding.ItemMessageBinding

class MessageAdapter(private val onImageClick: (Uri) -> Unit) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<Message>()

    fun addMessage(msg: Message) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateMessageStatus(msgId: Long, newStatus: Int) {
        val index = messages.indexOfFirst { it.id == msgId }
        if (index != -1 && messages[index].status < newStatus) {
            messages[index].status = newStatus
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val b = holder.binding

        b.layoutSent.visibility = View.GONE
        b.layoutReceived.visibility = View.GONE

        if (msg.isMine) {
            b.layoutSent.visibility = View.VISIBLE

            if (msg.imageUri != null) {
                b.cvImageSent.visibility = View.VISIBLE
                try {
                    b.ivSent.setImageURI(msg.imageUri)
                } catch (e: Exception) {
                    b.ivSent.setImageResource(android.R.drawable.ic_menu_report_image)
                }
                b.ivSent.setOnClickListener { onImageClick(msg.imageUri) }
            } else { b.cvImageSent.visibility = View.GONE }

            if (msg.text.isNotEmpty()) {
                b.tvTextSent.visibility = View.VISIBLE
                b.tvTextSent.text = msg.text
            } else { b.tvTextSent.visibility = View.GONE }

            when (msg.status) {
                0 -> b.ivMessageStatus.setImageResource(R.drawable.ic_msg_clock)
                1 -> b.ivMessageStatus.setImageResource(R.drawable.ic_msg_sent)
                2 -> b.ivMessageStatus.setImageResource(R.drawable.ic_msg_delivered)
            }

        } else {
            b.layoutReceived.visibility = View.VISIBLE

            if (msg.imageUri != null) {
                b.cvImageReceived.visibility = View.VISIBLE
                try {
                    b.ivReceived.setImageURI(msg.imageUri)
                } catch (e: Exception) {
                    b.ivReceived.setImageResource(android.R.drawable.ic_menu_report_image)
                }
                b.ivReceived.setOnClickListener { onImageClick(msg.imageUri) }
            } else { b.cvImageReceived.visibility = View.GONE }

            if (msg.text.isNotEmpty()) {
                b.tvTextReceived.visibility = View.VISIBLE
                b.tvTextReceived.text = msg.text
            } else { b.tvTextReceived.visibility = View.GONE }
        }
    }

    override fun getItemCount(): Int = messages.size
    class MessageViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)
}