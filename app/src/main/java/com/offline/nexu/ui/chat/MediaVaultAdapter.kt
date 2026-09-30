package com.offline.nexu.ui.chat

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R

class MediaVaultAdapter(
    private val mediaList: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<MediaVaultAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivMedia: ImageView = view.findViewById(R.id.ivMedia)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uriStr = mediaList[position]
        try {
            holder.ivMedia.setImageURI(Uri.parse(uriStr))
        } catch (e: Exception) {
            holder.ivMedia.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        // Al tocar la imagen, ejecuta la acción que pasemos desde el Activity
        holder.itemView.setOnClickListener { onClick(uriStr) }
    }

    override fun getItemCount(): Int = mediaList.size
}