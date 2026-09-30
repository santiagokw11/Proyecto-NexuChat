package com.offline.nexu.ui.chat

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.offline.nexu.databinding.ActivityImageViewerBinding

class ImageViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra("IMAGE_URI")
        if (uriString != null) {
            binding.photoView.setImageURI(Uri.parse(uriString))
        }

        binding.btnBack.setOnClickListener { finish() }
    }
}