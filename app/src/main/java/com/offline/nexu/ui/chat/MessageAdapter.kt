package com.offline.nexu.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val onRetryClick: (Message) -> Unit,
    private val onSelectionModeChanged: (Boolean, Int) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    val messages = mutableListOf<Message>()
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    var isSelectionMode = false
    val selectedMessages = mutableSetOf<Long>()

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: Long? = null
    private var currentlyPlayingProgress: Float = 0f
    private var currentPlaybackSpeed: Float = 1.0f
    private var progressJob: Job? = null

    fun releaseMediaPlayer() {
        progressJob?.cancel()
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
        currentlyPlayingId = null
    }

    private fun playAudio(msgId: Long, uri: Uri, context: Context) {
        if (currentlyPlayingId == msgId) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            } else {
                mediaPlayer?.start()
            }
            val idx = messages.indexOfFirst { it.msgId == msgId }
            if (idx != -1) notifyItemChanged(idx)
            return
        }

        releaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setSpeed(currentPlaybackSpeed)
                }
                start()
                setOnCompletionListener {
                    currentlyPlayingProgress = 0f
                    val idx = messages.indexOfFirst { msg -> msg.msgId == currentlyPlayingId }
                    currentlyPlayingId = null
                    progressJob?.cancel()
                    if (idx != -1) notifyItemChanged(idx)
                }
            }
            currentlyPlayingId = msgId
            startProgressUpdate()
            val idx = messages.indexOfFirst { it.msgId == msgId }
            if (idx != -1) notifyItemChanged(idx)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al reproducir audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSpeed() {
        currentPlaybackSpeed = when (currentPlaybackSpeed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { mediaPlayer?.playbackParams = mediaPlayer?.playbackParams?.setSpeed(currentPlaybackSpeed) ?: return }
            catch (e: Exception) {}
        }
        if (currentlyPlayingId != null) {
            val idx = messages.indexOfFirst { it.msgId == currentlyPlayingId }
            if (idx != -1) notifyItemChanged(idx)
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val duration = mp.duration.toFloat()
                        if (duration > 0) {
                            currentlyPlayingProgress = mp.currentPosition / duration
                            val idx = messages.indexOfFirst { it.msgId == currentlyPlayingId }
                            if (idx != -1) notifyItemChanged(idx, "AUDIO_PROGRESS")
                        }
                    }
                }
                delay(50)
            }
        }
    }

    fun toggleSelection(msgId: Long) {
        if (selectedMessages.contains(msgId)) selectedMessages.remove(msgId) else selectedMessages.add(msgId)
        if (selectedMessages.isEmpty()) isSelectionMode = false
        onSelectionModeChanged(isSelectionMode, selectedMessages.size)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedMessages.clear()
        onSelectionModeChanged(false, 0)
        notifyDataSetChanged()
    }

    fun addMessage(message: Message) {
        if (messages.any { it.msgId == message.msgId }) return
        val insertIndex = messages.indexOfFirst { it.msgId > message.msgId }
        if (insertIndex == -1) {
            messages.add(message)
            notifyItemInserted(messages.size - 1)
        } else {
            messages.add(insertIndex, message)
            notifyItemInserted(insertIndex)
            notifyItemRangeChanged(insertIndex, messages.size - insertIndex)
        }
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
        if (index != -1) { messages[index].progress = progress; notifyItemChanged(index, "PROGRESS_UPDATE") }
    }

    fun removeMessage(msgId: Long) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages.removeAt(index); selectedMessages.remove(msgId); notifyItemRemoved(index)
            if (index < messages.size) notifyItemChanged(index)
            if(isSelectionMode) onSelectionModeChanged(isSelectionMode, selectedMessages.size)
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
                            val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#80000000"); style = android.graphics.Paint.Style.FILL; isAntiAlias = true }
                            canvas.drawCircle(canvas.width / 2f, canvas.height / 2f, iconSize / 1.5f, paint)
                            playDrawable.draw(canvas)
                        }
                        withContext(Dispatchers.Main) { imageView.setImageBitmap(canvasBitmap) }
                    }
                } catch (e: Exception) { e.printStackTrace() }
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
        if (payloads.contains("AUDIO_PROGRESS")) {
            val currentMessage = messages[position]
            if (currentlyPlayingId == currentMessage.msgId) {
                if (currentMessage.isMine) holder.waveformSent.progress = currentlyPlayingProgress
                else holder.waveformReceived.progress = currentlyPlayingProgress
            }
            return
        }
        if (payloads.contains("PROGRESS_UPDATE")) {
            val currentMessage = messages[position]
            if (currentMessage.isMine) {
                if (currentMessage.progress in 0..99) {
                    holder.progressBarSent.visibility = View.VISIBLE
                    holder.progressBarSent.progress = currentMessage.progress
                } else holder.progressBarSent.visibility = View.GONE
            } else {
                if (currentMessage.progress in 0..99) {
                    holder.progressBarReceived.visibility = View.VISIBLE
                    holder.progressBarReceived.progress = currentMessage.progress
                } else holder.progressBarReceived.visibility = View.GONE
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val currentMessage = messages[position]

        var showDateSeparator = false
        if (position == 0) showDateSeparator = true
        else {
            val previousMessage = messages[position - 1]
            if (!isSameDay(previousMessage.msgId, currentMessage.msgId)) showDateSeparator = true
        }

        if (showDateSeparator) {
            holder.tvDateSeparator.visibility = View.VISIBLE
            holder.tvDateSeparator.text = getDateLabel(currentMessage.msgId)
        } else holder.tvDateSeparator.visibility = View.GONE

        val timeString = timeFormat.format(Date(currentMessage.msgId))

        holder.tvSystemMessage.visibility = View.GONE
        holder.layoutSent.visibility = View.GONE
        holder.layoutReceived.visibility = View.GONE

        val isSelected = selectedMessages.contains(currentMessage.msgId)
        if (isSelected) holder.itemView.setBackgroundColor(ColorUtils.setAlphaComponent(auraColor, 50))
        else holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val clickListener = View.OnClickListener {
            if (isSelectionMode && currentMessage.status != 99) toggleSelection(currentMessage.msgId)
        }

        val longClickListener = View.OnLongClickListener {
            if (!isSelectionMode && currentMessage.status != 99) { isSelectionMode = true; toggleSelection(currentMessage.msgId) }
            true
        }

        holder.itemView.setOnClickListener(clickListener)
        holder.itemView.setOnLongClickListener(longClickListener)
        holder.layoutSent.setOnClickListener(clickListener)
        holder.layoutSent.setOnLongClickListener(longClickListener)
        holder.layoutReceived.setOnClickListener(clickListener)
        holder.layoutReceived.setOnLongClickListener(longClickListener)

        val path = currentMessage.imageUrl?.toString() ?: ""
        val isAudio = path.endsWith(".ogg") || path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".opus") || currentMessage.text?.startsWith("[Audio]") == true

        if (currentMessage.status == 99) {
            holder.tvSystemMessage.visibility = View.VISIBLE
            holder.tvSystemMessage.text = currentMessage.text
        } else if (currentMessage.isMine) {
            holder.layoutSent.visibility = View.VISIBLE
            holder.layoutSent.backgroundTintList = ColorStateList.valueOf(auraColor)
            holder.tvTimeSent.text = timeString

            if (isAudio) {
                holder.layoutAudioSent.visibility = View.VISIBLE
                holder.cvImageSent.visibility = View.GONE
                holder.tvTextSent.visibility = View.GONE

                val isPlaying = currentlyPlayingId == currentMessage.msgId && mediaPlayer?.isPlaying == true
                holder.btnPlayPauseSent.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                holder.tvSpeedSent.text = "${currentPlaybackSpeed}x"

                holder.waveformSent.waveColor = ColorUtils.setAlphaComponent(Color.WHITE, 100)
                holder.waveformSent.progressColor = Color.WHITE

                if (currentMessage.text?.startsWith("[Audio]:") == true) {
                    val afterAudio = currentMessage.text!!.substringAfter("[Audio]:")
                    if (afterAudio.contains(":")) {
                        holder.tvDurationSent.text = afterAudio.substringBeforeLast(":")
                        val ampsStr = afterAudio.substringAfterLast(":")
                        val amps = ampsStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (amps.isNotEmpty()) holder.waveformSent.setAmplitudes(amps)
                    } else {
                        holder.tvDurationSent.text = "00:00"
                        val amps = afterAudio.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (amps.isNotEmpty()) holder.waveformSent.setAmplitudes(amps)
                    }
                }

                if (currentlyPlayingId == currentMessage.msgId) holder.waveformSent.progress = currentlyPlayingProgress
                else holder.waveformSent.progress = 0f

                holder.btnPlayPauseSent.setOnClickListener { currentMessage.imageUrl?.let { uri -> playAudio(currentMessage.msgId, uri, holder.itemView.context) } }
                holder.tvSpeedSent.setOnClickListener { toggleSpeed() }
                holder.waveformSent.onProgressChanged = { prog -> if (currentlyPlayingId == currentMessage.msgId) mediaPlayer?.seekTo((prog * (mediaPlayer?.duration ?: 0)).toInt()) }
            } else {
                holder.layoutAudioSent.visibility = View.GONE
                if (currentMessage.text.isNullOrEmpty()) holder.tvTextSent.visibility = View.GONE
                else { holder.tvTextSent.visibility = View.VISIBLE; holder.tvTextSent.text = currentMessage.text }

                if (currentMessage.imageUrl == null) {
                    holder.cvImageSent.visibility = View.GONE; holder.ivForwardSent.visibility = View.GONE
                } else {
                    holder.cvImageSent.visibility = View.VISIBLE
                    cargarMedia(currentMessage.imageUrl, holder.ivSent, holder.itemView.context)
                    holder.ivSent.setOnClickListener { if (isSelectionMode) toggleSelection(currentMessage.msgId) else onImageClick(currentMessage.imageUrl) }
                    holder.ivSent.setOnLongClickListener(longClickListener)
                }
            }

            if (currentMessage.progress in 0..99) { holder.progressBarSent.visibility = View.VISIBLE; holder.progressBarSent.progress = currentMessage.progress }
            else holder.progressBarSent.visibility = View.GONE

            // LÓGICA INFALIBLE PARA MUTAR EL VECTOR Y PINTARLO
            if (currentMessage.status == -2) {
                holder.ivRetrySent.visibility = View.VISIBLE
                holder.ivMessageStatus.visibility = View.GONE
                holder.ivRetrySent.setOnClickListener { onRetryClick(currentMessage) }
            } else {
                holder.ivRetrySent.visibility = View.GONE
                holder.ivMessageStatus.visibility = View.VISIBLE

                val drawableRes = when (currentMessage.status) {
                    0 -> R.drawable.ic_msg_clock
                    1 -> R.drawable.ic_msg_sent
                    else -> R.drawable.ic_msg_delivered
                }

                val colorToApply = if (currentMessage.status == 3) auraColor else Color.WHITE

                // mutate() desvincula el drawable de su estado original en caché o XML
                val drawable = ContextCompat.getDrawable(holder.itemView.context, drawableRes)?.mutate()
                drawable?.setTint(colorToApply)
                holder.ivMessageStatus.setImageDrawable(drawable)
            }

        } else {
            holder.layoutReceived.visibility = View.VISIBLE
            holder.tvTimeReceived.text = timeString

            if (currentMessage.isGroup) {
                holder.tvSenderName.visibility = View.VISIBLE; holder.tvSenderName.text = currentMessage.originalSender ?: "Miembro"; holder.tvSenderName.setTextColor(auraColor)
            } else holder.tvSenderName.visibility = View.GONE

            if (isAudio) {
                holder.layoutAudioReceived.visibility = View.VISIBLE
                holder.cvImageReceived.visibility = View.GONE
                holder.tvTextReceived.visibility = View.GONE

                val isPlaying = currentlyPlayingId == currentMessage.msgId && mediaPlayer?.isPlaying == true
                holder.btnPlayPauseReceived.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                holder.tvSpeedReceived.text = "${currentPlaybackSpeed}x"

                val textColor = ContextCompat.getColor(holder.itemView.context, R.color.text_primary)
                holder.waveformReceived.waveColor = ColorUtils.setAlphaComponent(textColor, 100)
                holder.waveformReceived.progressColor = auraColor

                if (currentMessage.text?.startsWith("[Audio]:") == true) {
                    val afterAudio = currentMessage.text!!.substringAfter("[Audio]:")
                    if (afterAudio.contains(":")) {
                        holder.tvDurationReceived.text = afterAudio.substringBeforeLast(":")
                        val ampsStr = afterAudio.substringAfterLast(":")
                        val amps = ampsStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (amps.isNotEmpty()) holder.waveformReceived.setAmplitudes(amps)
                    } else {
                        holder.tvDurationReceived.text = "00:00"
                        val amps = afterAudio.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (amps.isNotEmpty()) holder.waveformReceived.setAmplitudes(amps)
                    }
                }

                if (currentlyPlayingId == currentMessage.msgId) holder.waveformReceived.progress = currentlyPlayingProgress
                else holder.waveformReceived.progress = 0f

                holder.btnPlayPauseReceived.setOnClickListener { currentMessage.imageUrl?.let { uri -> playAudio(currentMessage.msgId, uri, holder.itemView.context) } }
                holder.tvSpeedReceived.setOnClickListener { toggleSpeed() }
                holder.waveformReceived.onProgressChanged = { prog -> if (currentlyPlayingId == currentMessage.msgId) mediaPlayer?.seekTo((prog * (mediaPlayer?.duration ?: 0)).toInt()) }
            } else {
                holder.layoutAudioReceived.visibility = View.GONE
                if (currentMessage.text.isNullOrEmpty()) holder.tvTextReceived.visibility = View.GONE
                else { holder.tvTextReceived.visibility = View.VISIBLE; holder.tvTextReceived.text = currentMessage.text }

                if (currentMessage.imageUrl == null) {
                    holder.cvImageReceived.visibility = View.GONE; holder.ivForwardReceived.visibility = View.GONE
                } else {
                    holder.cvImageReceived.visibility = View.VISIBLE
                    cargarMedia(currentMessage.imageUrl, holder.ivReceived, holder.itemView.context)
                    holder.ivReceived.setOnClickListener { if (isSelectionMode) toggleSelection(currentMessage.msgId) else onImageClick(currentMessage.imageUrl) }
                    holder.ivReceived.setOnLongClickListener(longClickListener)
                }
            }

            if (currentMessage.progress in 0..99) { holder.progressBarReceived.visibility = View.VISIBLE; holder.progressBarReceived.progress = currentMessage.progress }
            else holder.progressBarReceived.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun getDateLabel(timestamp: Long): String {
        val today = Calendar.getInstance(); val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        if (today.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR)) return "Hoy"
        val ayer = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (ayer.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) && ayer.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR)) return "Ayer"
        val antier = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
        if (antier.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) && antier.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR)) return "Antier"
        val haceUnaSemana = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }
        if (msgDate.after(haceUnaSemana)) return SimpleDateFormat("EEEE", Locale("es", "ES")).format(Date(timestamp)).replaceFirstChar { it.uppercase() }
        val formatoCompleto = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es", "ES"))
        return formatoCompleto.format(Date(timestamp))
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDateSeparator: TextView = view.findViewById(R.id.tvDateSeparator)
        val tvSystemMessage: TextView = view.findViewById(R.id.tvSystemMessage)

        val layoutSent: View = view.findViewById(R.id.layoutSent)
        val tvTextSent: TextView = view.findViewById(R.id.tvTextSent)
        val cvImageSent: View = view.findViewById(R.id.cvImageSent)
        val ivSent: ImageView = view.findViewById(R.id.ivSent)
        val tvTimeSent: TextView = view.findViewById(R.id.tvTimeSent)
        val ivMessageStatus: ImageView = view.findViewById(R.id.ivMessageStatus)
        val progressBarSent: ProgressBar = view.findViewById(R.id.progressBarSent)
        val ivForwardSent: ImageView = view.findViewById(R.id.ivForwardSent)
        val ivRetrySent: ImageView = view.findViewById(R.id.ivRetrySent)

        val layoutAudioSent: View = view.findViewById(R.id.layoutAudioSent)
        val btnPlayPauseSent: ImageView = view.findViewById(R.id.btnPlayPauseSent)
        val waveformSent: WaveformSeekBar = view.findViewById(R.id.waveformSent)
        val tvSpeedSent: TextView = view.findViewById(R.id.tvSpeedSent)
        val tvDurationSent: TextView = view.findViewById(R.id.tvDurationSent)

        val layoutReceived: View = view.findViewById(R.id.layoutReceived)
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvTextReceived: TextView = view.findViewById(R.id.tvTextReceived)
        val cvImageReceived: View = view.findViewById(R.id.cvImageReceived)
        val ivReceived: ImageView = view.findViewById(R.id.ivReceived)
        val tvTimeReceived: TextView = view.findViewById(R.id.tvTimeReceived)
        val progressBarReceived: ProgressBar = view.findViewById(R.id.progressBarReceived)
        val ivForwardReceived: ImageView = view.findViewById(R.id.ivForwardReceived)

        val layoutAudioReceived: View = view.findViewById(R.id.layoutAudioReceived)
        val btnPlayPauseReceived: ImageView = view.findViewById(R.id.btnPlayPauseReceived)
        val waveformReceived: WaveformSeekBar = view.findViewById(R.id.waveformReceived)
        val tvSpeedReceived: TextView = view.findViewById(R.id.tvSpeedReceived)
        val tvDurationReceived: TextView = view.findViewById(R.id.tvDurationReceived)
    }
}

class WaveformSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }
    var progress: Float = 0f
        set(value) { field = value; invalidate() }

    var waveColor: Int = Color.GRAY
        set(value) { field = value; invalidate() }

    var progressColor: Int = Color.GREEN

    private var amplitudes = FloatArray(35) { 0.1f }
    var onProgressChanged: ((Float) -> Unit)? = null

    fun setAmplitudes(amps: FloatArray) {
        this.amplitudes = if (amps.size == 35) amps else FloatArray(35) { i -> amps.getOrElse(i) { 0.1f } }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / amplitudes.size

        for (i in amplitudes.indices) {
            val barX = i * barWidth + barWidth / 2
            val barHeight = amplitudes[i] * (h - paint.strokeWidth)
            val startY = (h - barHeight) / 2
            val endY = startY + barHeight

            paint.color = if (i.toFloat() / amplitudes.size <= progress) progressColor else waveColor
            canvas.drawLine(barX, startY, barX, endY, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                progress = (event.x / width).coerceIn(0f, 1f)
                onProgressChanged?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}