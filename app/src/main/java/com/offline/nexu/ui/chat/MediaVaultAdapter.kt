package com.offline.nexu.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaVaultAdapter(
    private val dataList: List<String>,
    private val currentTab: String = "MEDIA",
    private val isGrid: Boolean = false,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_MEDIA = 1
    private val VIEW_TYPE_DOC = 2

    override fun getItemViewType(position: Int): Int {
        return if (currentTab == "MEDIA") VIEW_TYPE_MEDIA else VIEW_TYPE_DOC
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivVaultMedia: ImageView = view.findViewById(R.id.ivVaultMedia)
        val ivVaultVideoIcon: ImageView = view.findViewById(R.id.ivVaultVideoIcon)
    }

    inner class DocViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivDocIcon: ImageView = view.findViewById(R.id.ivDocIcon)
        val tvDocName: TextView = view.findViewById(R.id.tvDocName)
        val tvDocType: TextView = view.findViewById(R.id.tvDocType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_MEDIA) {
            val layoutId = if (isGrid) R.layout.item_vault_grid_media else R.layout.item_vault_media
            MediaViewHolder(LayoutInflater.from(parent.context).inflate(layoutId, parent, false))
        } else {
            DocViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_vault_doc, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val itemStr = dataList[position]

        if (holder is MediaViewHolder) {
            val uri = Uri.parse(itemStr)
            val isVideo = itemStr.endsWith(".mp4", ignoreCase = true) || itemStr.contains("VID_") || itemStr.contains("video")

            holder.ivVaultMedia.setImageDrawable(null)

            if (isVideo) {
                holder.ivVaultVideoIcon.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val file = File(uri.path ?: "")
                        if (file.exists()) {
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(500, 500), null)
                            } else {
                                @Suppress("DEPRECATION")
                                android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
                            }
                            withContext(Dispatchers.Main) {
                                if (bitmap != null) holder.ivVaultMedia.setImageBitmap(bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                holder.ivVaultVideoIcon.visibility = View.GONE
                holder.ivVaultMedia.setImageURI(uri)
            }

            holder.itemView.setOnClickListener { onClick(itemStr) }

        } else if (holder is DocViewHolder) {
            if (currentTab == "LINKS") {
                holder.ivDocIcon.setImageResource(R.drawable.ic_copy) // O usa un icono de globo terráqueo/enlace si tienes
                holder.tvDocName.text = itemStr
                holder.tvDocType.text = "Enlace externo"
            } else {
                // Es un Documento o Audio
                val lower = itemStr.lowercase()
                val fileName = Uri.parse(itemStr).lastPathSegment ?: "Archivo Desconocido"
                holder.tvDocName.text = fileName

                if (lower.endsWith(".pdf")) {
                    holder.ivDocIcon.setImageResource(R.drawable.ic_copy)
                    holder.tvDocType.text = "Documento PDF"
                } else if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
                    holder.ivDocIcon.setImageResource(R.drawable.ic_copy)
                    holder.tvDocType.text = "Documento Word"
                } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.contains("audio")) {
                    holder.ivDocIcon.setImageResource(R.drawable.ic_bell) // Cambiar por ícono de nota de voz si tienes
                    holder.tvDocType.text = "Nota de Voz / Audio"
                } else {
                    holder.ivDocIcon.setImageResource(R.drawable.ic_clip)
                    holder.tvDocType.text = "Archivo"
                }
            }

            holder.itemView.setOnClickListener { onClick(itemStr) }
        }
    }

    override fun getItemCount() = dataList.size
}