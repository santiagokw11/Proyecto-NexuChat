package com.offline.nexu.ui.chat

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R

class SharedImagesAdapter(private val images: List<String>) : RecyclerView.Adapter<SharedImagesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivSharedImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shared_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            holder.ivImage.setImageURI(Uri.parse(images[position]))
        } catch (e: Exception) {
            holder.ivImage.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    override fun getItemCount(): Int = images.size
}