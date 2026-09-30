package com.offline.nexu.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.databinding.ActivityLoginBinding
import com.offline.nexu.ui.home.HomeActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs
    private var currentEmoji = "👤"

    private val avatarOptions = listOf("👤", "🐱", "🐶", "🦊", "🤖", "👾", "😎", "🚀", "🌈", "🔥")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        if (prefs.isRegistered()) {
            goToHome()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnChangeEmoji.setOnClickListener {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Selecciona tu estilo")
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, avatarOptions)
            builder.setAdapter(adapter) { _, which ->
                currentEmoji = avatarOptions[which]
                binding.tvSelectedEmoji.text = currentEmoji
            }
            builder.show()
        }

        binding.btnRegister.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isNotEmpty() && pass.isNotEmpty()) {
                prefs.saveUser(user, pass, currentEmoji)
                goToHome()
            } else {
                Toast.makeText(this, "Completa tus credenciales", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}