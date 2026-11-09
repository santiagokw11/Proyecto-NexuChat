package com.offline.nexu.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaVaultAdapter(
    private val mediaList: List<String>,
    private val isGrid: Boolean = false, // <-- Detecta si estamos en la bóveda
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<MediaVaultAdapter.MediaViewHolder>() {

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivVaultMedia: ImageView = view.findViewById(R.id.ivVaultMedia)
        val ivVaultVideoIcon: ImageView = view.findViewById(R.id.ivVaultVideoIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        // Infla el diseño cuadrado adaptativo si es la bóveda, o el fijo si es el perfil
        val layoutId = if (isGrid) R.layout.item_vault_grid_media else R.layout.item_vault_media
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val uriStr = mediaList[position]
        val uri = Uri.parse(uriStr)

        val isVideo = uriStr.endsWith(".mp4", ignoreCase = true) || uriStr.contains("VID_") || uriStr.contains("video")

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

        holder.itemView.setOnClickListener {
            onClick(uriStr)
        }
    }

    override fun getItemCount() = mediaList.size
}