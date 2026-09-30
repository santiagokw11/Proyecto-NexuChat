package com.offline.nexu.ui.chat

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.model.Message

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messagesList = mutableListOf<Message>()

    fun addMessage(message: Message) {
        messagesList.add(message)
        notifyItemInserted(messagesList.size - 1)
    }

    fun updateMessageImage(payloadId: Long, uri: Uri) {
        val index = messagesList.indexOfFirst { !it.isMine && it.payloadId == payloadId }
        if (index != -1) {
            messagesList[index] = messagesList[index].copy(imageUri = uri)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messagesList[position])
    }

    override fun getItemCount(): Int = messagesList.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        private val bubbleLayout: LinearLayout = itemView.findViewById(R.id.bubbleLayout)
        private val cvImage: CardView = itemView.findViewById(R.id.cvImage)
        private val ivMessageImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        private val pbLoading: ProgressBar = itemView.findViewById(R.id.pbLoading)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(message: Message) {
            messageContainer.gravity = if (message.isMine) Gravity.END else Gravity.START

            // Colores estilo WhatsApp (Verde oscuro enviados, Gris oscuro recibidos)
            bubbleLayout.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (message.isMine) "#005C4B" else "#202C33")
            )

            // Lógica de Imagen y Carga
            if (message.imageUri != null) {
                cvImage.visibility = View.VISIBLE
                ivMessageImage.visibility = View.VISIBLE
                pbLoading.visibility = View.GONE
                ivMessageImage.setImageURI(message.imageUri)

                ivMessageImage.setOnClickListener {
                    val intent = Intent(itemView.context, ImageViewerActivity::class.java)
                    intent.putExtra("IMAGE_URI", message.imageUri.toString())
                    itemView.context.startActivity(intent)
                }
            } else if (message.payloadId != null && !message.isMine) {
                // Imagen descargándose: muestra ProgressBar en lugar de las flechas
                cvImage.visibility = View.VISIBLE
                ivMessageImage.visibility = View.GONE
                pbLoading.visibility = View.VISIBLE
            } else {
                cvImage.visibility = View.GONE
            }

            // Lógica de Texto
            if (message.text.isNotEmpty()) {
                tvMessage.visibility = View.VISIBLE
                tvMessage.text = message.text

                // Reducir la separación entre imagen y texto
                val params = tvMessage.layoutParams as LinearLayout.LayoutParams
                params.topMargin = if (cvImage.visibility == View.VISIBLE) 2 else 0
                tvMessage.layoutParams = params
            } else {
                tvMessage.visibility = View.GONE
            }
        }
    }
}