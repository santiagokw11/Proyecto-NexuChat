package com.offline.nexu.ui.chat

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.offline.nexu.databinding.ActivityImageViewerBinding
import java.io.InputStream
import java.io.OutputStream

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUriString = intent.getStringExtra("IMAGE_URI")
        if (imageUriString == null) {
            finish()
            return
        }

        val uri = Uri.parse(imageUriString)
        binding.ivZoomable.setImageURI(uri)

        binding.toolbarViewer.setNavigationOnClickListener { finish() }

        binding.btnSaveImage.setOnClickListener {
            saveImageToGallery(uri)
        }

        // Lógica de Zoom perfecta y centrada
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f))

                // Aplica el zoom desde el centro exacto de la imagen
                binding.ivZoomable.scaleX = scaleFactor
                binding.ivZoomable.scaleY = scaleFactor
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return true
    }

    private fun saveImageToGallery(sourceUri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(sourceUri)

            val imageDetails = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "NexuChat_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexuChat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val imageUri = contentResolver.insert(collection, imageDetails)

            if (imageUri != null && inputStream != null) {
                val outputStream: OutputStream? = contentResolver.openOutputStream(imageUri)
                outputStream?.let {
                    inputStream.copyTo(it)
                    it.close()
                }
                inputStream.close()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    imageDetails.clear()
                    imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(imageUri, imageDetails, null, null)
                }
                Toast.makeText(this, "¡Guardada en la Galería! 📸", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}