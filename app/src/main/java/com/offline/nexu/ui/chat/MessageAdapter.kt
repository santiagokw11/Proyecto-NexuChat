package com.offline.nexu.ui.chat

import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.model.Message
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val auraColor: Int,
    private val onImageClick: (Uri) -> Unit,
    private val onMessageLongClick: (Message) -> Unit // Callback para toque largo
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<Message>()

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateMessageStatus(msgId: Long, newStatus: Int) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages[index].status = newStatus
            notifyItemChanged(index)
        }
    }

    // Remueve visualmente el mensaje de la lista
    fun removeMessage(msgId: Long) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val currentMessage = messages[position]

        var showDateSeparator = false
        if (position == 0) {
            showDateSeparator = true
        } else {
            val previousMessage = messages[position - 1]
            if (!isSameDay(previousMessage.msgId, currentMessage.msgId)) {
                showDateSeparator = true
            }
        }

        if (showDateSeparator) {
            holder.tvDateSeparator.visibility = View.VISIBLE
            holder.tvDateSeparator.text = getDateLabel(currentMessage.msgId)
        } else {
            holder.tvDateSeparator.visibility = View.GONE
        }

        val timeString = timeFormat.format(Date(currentMessage.msgId))

        // LÓGICA DE PULSACIÓN LARGA PARA ELIMINAR
        holder.layoutSent.setOnLongClickListener {
            onMessageLongClick(currentMessage)
            true
        }
        holder.layoutReceived.setOnLongClickListener {
            onMessageLongClick(currentMessage)
            true
        }

        if (currentMessage.isMine) {
            holder.layoutSent.visibility = View.VISIBLE
            holder.layoutReceived.visibility = View.GONE

            holder.layoutSent.backgroundTintList = ColorStateList.valueOf(auraColor)
            holder.tvTimeSent.text = timeString

            if (currentMessage.text.isNullOrEmpty()) {
                holder.tvTextSent.visibility = View.GONE
            } else {
                holder.tvTextSent.visibility = View.VISIBLE
                holder.tvTextSent.text = currentMessage.text
            }

            if (currentMessage.imageUrl == null) {
                holder.cvImageSent.visibility = View.GONE
            } else {
                holder.cvImageSent.visibility = View.VISIBLE
                holder.ivSent.setImageURI(currentMessage.imageUrl)
                holder.ivSent.setOnClickListener { onImageClick(currentMessage.imageUrl) }
            }

            when (currentMessage.status) {
                0 -> holder.ivMessageStatus.setImageResource(R.drawable.ic_msg_clock)
                1 -> holder.ivMessageStatus.setImageResource(R.drawable.ic_msg_sent)
                2 -> holder.ivMessageStatus.setImageResource(R.drawable.ic_msg_delivered)
            }

        } else {
            holder.layoutSent.visibility = View.GONE
            holder.layoutReceived.visibility = View.VISIBLE

            holder.tvTimeReceived.text = timeString

            if (currentMessage.text.isNullOrEmpty()) {
                holder.tvTextReceived.visibility = View.GONE
            } else {
                holder.tvTextReceived.visibility = View.VISIBLE
                holder.tvTextReceived.text = currentMessage.text
            }

            if (currentMessage.imageUrl == null) {
                holder.cvImageReceived.visibility = View.GONE
            } else {
                holder.cvImageReceived.visibility = View.VISIBLE
                holder.ivReceived.setImageURI(currentMessage.imageUrl)
                holder.ivReceived.setOnClickListener { onImageClick(currentMessage.imageUrl) }
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun getDateLabel(timestamp: Long): String {
        val calendar = Calendar.getInstance()
        val todayYear = calendar.get(Calendar.YEAR)
        val todayDay = calendar.get(Calendar.DAY_OF_YEAR)

        calendar.timeInMillis = timestamp
        val msgYear = calendar.get(Calendar.YEAR)
        val msgDay = calendar.get(Calendar.DAY_OF_YEAR)

        return when {
            todayYear == msgYear && todayDay == msgDay -> "Hoy"
            todayYear == msgYear && todayDay - 1 == msgDay -> "Ayer"
            else -> dateFormat.format(Date(timestamp))
        }
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDateSeparator: TextView = view.findViewById(R.id.tvDateSeparator)

        val layoutSent: View = view.findViewById(R.id.layoutSent)
        val tvTextSent: TextView = view.findViewById(R.id.tvTextSent)
        val cvImageSent: View = view.findViewById(R.id.cvImageSent)
        val ivSent: ImageView = view.findViewById(R.id.ivSent)
        val tvTimeSent: TextView = view.findViewById(R.id.tvTimeSent)
        val ivMessageStatus: ImageView = view.findViewById(R.id.ivMessageStatus)

        val layoutReceived: View = view.findViewById(R.id.layoutReceived)
        val tvTextReceived: TextView = view.findViewById(R.id.tvTextReceived)
        val cvImageReceived: View = view.findViewById(R.id.cvImageReceived)
        val ivReceived: ImageView = view.findViewById(R.id.ivReceived)
        val tvTimeReceived: TextView = view.findViewById(R.id.tvTimeReceived)
    }
}