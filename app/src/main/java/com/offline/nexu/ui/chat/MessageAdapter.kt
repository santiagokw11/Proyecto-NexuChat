package com.offline.nexu.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val auraColor: Int,
    private val onImageClick: (Uri) -> Unit,
    private val onMessageLongClick: (Message) -> Unit
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

    fun updateProgress(msgId: Long, progress: Int) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages[index].progress = progress
            notifyItemChanged(index, "PROGRESS_UPDATE")
        }
    }

    fun removeMessage(msgId: Long) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    private fun cargarMedia(uri: Uri, imageView: ImageView, context: Context) {
        val path = uri.toString()
        val isVideo = path.endsWith(".mp4") || path.contains("VID_") || path.contains("video")

        if (isVideo) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val file = File(uri.path!!)
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(500, 500), null)
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
                    }

                    if (bitmap != null) {
                        val canvasBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = Canvas(canvasBitmap)
                        val playDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_media_play)

                        if (playDrawable != null) {
                            val iconSize = 120
                            val left = (canvas.width - iconSize) / 2
                            val top = (canvas.height - iconSize) / 2
                            playDrawable.setBounds(left, top, left + iconSize, top + iconSize)
                            playDrawable.setTint(android.graphics.Color.WHITE)

                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#80000000")
                                style = android.graphics.Paint.Style.FILL
                                isAntiAlias = true
                            }
                            canvas.drawCircle(canvas.width / 2f, canvas.height / 2f, iconSize / 1.5f, paint)
                            playDrawable.draw(canvas)
                        }

                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(canvasBitmap)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            imageView.setImageURI(uri)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("PROGRESS_UPDATE")) {
            val currentMessage = messages[position]

            if (currentMessage.isMine) {
                if (currentMessage.progress in 0..99) {
                    holder.progressBarSent.visibility = View.VISIBLE
                    holder.progressBarSent.progress = currentMessage.progress
                } else {
                    holder.progressBarSent.visibility = View.GONE
                }
            } else {
                if (currentMessage.progress in 0..99) {
                    holder.progressBarReceived.visibility = View.VISIBLE
                    holder.progressBarReceived.progress = currentMessage.progress
                } else {
                    holder.progressBarReceived.visibility = View.GONE
                }
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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

        // Clic largo a la burbuja del emisor/receptor
        holder.layoutSent.setOnLongClickListener { onMessageLongClick(currentMessage); true }
        holder.layoutReceived.setOnLongClickListener { onMessageLongClick(currentMessage); true }

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
                cargarMedia(currentMessage.imageUrl, holder.ivSent, holder.itemView.context)

                // Mantiene el OnClick para expandir, y AÑADIMOS el LongClick para eliminar/reenviar
                holder.ivSent.setOnClickListener { onImageClick(currentMessage.imageUrl) }
                holder.ivSent.setOnLongClickListener { onMessageLongClick(currentMessage); true }
            }

            if (currentMessage.progress in 0..99) {
                holder.progressBarSent.visibility = View.VISIBLE
                holder.progressBarSent.progress = currentMessage.progress
            } else {
                holder.progressBarSent.visibility = View.GONE
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
                cargarMedia(currentMessage.imageUrl, holder.ivReceived, holder.itemView.context)

                // Mantiene el OnClick y AÑADIMOS el LongClick también para las recibidas
                holder.ivReceived.setOnClickListener { onImageClick(currentMessage.imageUrl) }
                holder.ivReceived.setOnLongClickListener { onMessageLongClick(currentMessage); true }
            }

            if (currentMessage.progress in 0..99) {
                holder.progressBarReceived.visibility = View.VISIBLE
                holder.progressBarReceived.progress = currentMessage.progress
            } else {
                holder.progressBarReceived.visibility = View.GONE
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
        val progressBarSent: ProgressBar = view.findViewById(R.id.progressBarSent)

        val layoutReceived: View = view.findViewById(R.id.layoutReceived)
        val tvTextReceived: TextView = view.findViewById(R.id.tvTextReceived)
        val cvImageReceived: View = view.findViewById(R.id.cvImageReceived)
        val ivReceived: ImageView = view.findViewById(R.id.ivReceived)
        val tvTimeReceived: TextView = view.findViewById(R.id.tvTimeReceived)
        val progressBarReceived: ProgressBar = view.findViewById(R.id.progressBarReceived)
    }
}