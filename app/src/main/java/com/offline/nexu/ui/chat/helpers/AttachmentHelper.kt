package com.offline.nexu.ui.chat.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AttachmentHelper {

    data class LocalMedia(val uri: Uri, val isVideo: Boolean, val album: String)

    fun showAttachmentMenu(context: Context, layoutInflater: LayoutInflater, onGalleryClick: () -> Unit, onCameraClick: () -> Unit, onFilesClick: () -> Unit) {
        val dialog = BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.layout_attachment_menu, null)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.btnGallery).setOnClickListener { dialog.dismiss(); onGalleryClick() }
        view.findViewById<View>(R.id.btnCamera).setOnClickListener { dialog.dismiss(); onCameraClick() }
        view.findViewById<View>(R.id.btnFiles).setOnClickListener { dialog.dismiss(); onFilesClick() }
        dialog.show()
    }

    fun openCustomGallery(context: Context, layoutInflater: LayoutInflater, onUriSelected: (Uri) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.layout_custom_gallery, null)
        val rvGallery = view.findViewById<RecyclerView>(R.id.rvCustomGallery)
        val spinnerAlbums = view.findViewById<Spinner>(R.id.spinnerAlbums)

        rvGallery.layoutManager = GridLayoutManager(context, 3)
        val mediaLocales = obtenerMediosLocales(context)

        val albums = listOf("Todos") + mediaLocales.map { it.album }.distinct().sorted()
        spinnerAlbums.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, albums)

        val adapter = CustomGalleryAdapter(context, mediaLocales) { uri ->
            onUriSelected(uri)
            dialog.dismiss()
        }
        rvGallery.adapter = adapter

        spinnerAlbums.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAlbum = albums[position]
                if (selectedAlbum == "Todos") adapter.updateData(mediaLocales)
                else adapter.updateData(mediaLocales.filter { it.album == selectedAlbum })
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun obtenerMediosLocales(context: Context): List<LocalMedia> {
        val mediaList = mutableListOf<LocalMedia>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"

        try {
            context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext() && mediaList.size < 300) {
                    val dataPath = cursor.getString(dataCol) ?: ""
                    if (dataPath.startsWith("/storage") || dataPath.startsWith("/sdcard")) {
                        val id = cursor.getLong(idCol)
                        val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val albumName = cursor.getString(bucketCol) ?: "Cámara"
                        val contentUri = Uri.withAppendedPath(uri, "" + id)
                        mediaList.add(LocalMedia(contentUri, isVideo, albumName))
                    }
                }
            }
        } catch (e: Exception) {}
        return mediaList
    }

    fun copiarImagenLocal(context: Context, uri: Uri, esDocumento: Boolean): Uri? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Usando directorio público para que la galería lo lea
            val baseMediaDir = context.externalMediaDirs.firstOrNull() ?: context.getExternalFilesDir(null)

            val mimeType = context.contentResolver.getType(uri) ?: ""
            val pathStr = uri.toString().lowercase()

            val isVideo = mimeType.contains("video") || pathStr.endsWith(".mp4")
            val isAudio = mimeType.contains("audio") || pathStr.endsWith(".ogg") || pathStr.endsWith(".mp3") || pathStr.endsWith(".m4a")

            val subFolder = when {
                esDocumento -> "NexuChat Documents"
                isVideo -> "NexuChat Videos"
                isAudio -> "NexuChat Audio"
                else -> "NexuChat Images"
            }

            val ext = when {
                esDocumento -> ".pdf"
                isVideo -> ".mp4"
                isAudio -> ".mp3"
                else -> ".jpg"
            }

            val prefix = when {
                esDocumento -> "DOC_"
                isVideo -> "VID_"
                isAudio -> "AUD_"
                else -> "IMG_"
            }

            // Agregamos el /Sent como pide la arquitectura
            val mediaDir = File(baseMediaDir, "NexuChat/Media/$subFolder/Sent")
            if (!mediaDir.exists()) mediaDir.mkdirs()

            val file = File(mediaDir, "${prefix}${System.currentTimeMillis()}$ext")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Uri.fromFile(file)
        } catch (e: Exception) { null }
    }

    suspend fun cargarMiniaturaVideoVistaPrevia(context: Context, uri: Uri, imageView: ImageView) {
        try {
            val file = File(uri.path!!)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(500, 500), null)
            } else {
                @Suppress("DEPRECATION")
                android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
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
                    playDrawable.setTint(Color.WHITE)
                    val paint = android.graphics.Paint().apply { color = Color.parseColor("#80000000"); style = android.graphics.Paint.Style.FILL; isAntiAlias = true }
                    canvas.drawCircle(canvas.width / 2f, canvas.height / 2f, iconSize / 1.5f, paint)
                    playDrawable.draw(canvas)
                }
                withContext(Dispatchers.Main) { imageView.setImageBitmap(canvasBitmap) }
            }
        } catch (e: Exception) {}
    }

    class CustomGalleryAdapter(private val context: Context, private var media: List<LocalMedia>, private val onClick: (Uri) -> Unit) : RecyclerView.Adapter<CustomGalleryAdapter.GalleryViewHolder>() {
        inner class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivGalleryThumbnail)
            val iconVideo: ImageView = view.findViewById(R.id.ivVideoIcon)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder = GalleryViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_media, parent, false))
        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val item = media[position]
            holder.iconVideo.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { context.contentResolver.loadThumbnail(item.uri, android.util.Size(200, 200), null) }
                    else { MediaStore.Images.Media.getBitmap(context.contentResolver, item.uri) }
                    withContext(Dispatchers.Main) { holder.image.setImageBitmap(bmp) }
                } catch (e: Exception) {}
            }
            holder.itemView.setOnClickListener { onClick(item.uri) }
        }
        override fun getItemCount() = media.size
        fun updateData(newData: List<LocalMedia>) { this.media = newData; notifyDataSetChanged() }
    }
}