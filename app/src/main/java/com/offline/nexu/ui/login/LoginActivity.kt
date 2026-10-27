package com.offline.nexu.ui.login

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.databinding.ActivityLoginBinding
import com.offline.nexu.ui.home.HomeActivity
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs
    private var currentEmoji = "👤"

    private val avatarOptions = listOf("👤", "🐱", "🐶", "🦊", "🤖", "👾", "😎", "🚀", "🌈", "🔥")

    data class SavedAccountItem(val id: String, val name: String, val avatar: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (prefs.isRegistered()) {
            if (isLocationEnabled()) goToHome() else showLocationWarning()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkAndRequestPermissions()

        actualizarBotonCuentasGuardadas()

        binding.btnViewAccounts.setOnClickListener { mostrarCuentasPreviasHermoso() }

        binding.btnChangeEmoji.setOnClickListener {
            val dialog = android.app.Dialog(this)
            dialog.setContentView(R.layout.dialog_emoji_picker)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val grid = dialog.findViewById<GridView>(R.id.gridEmojis)
            val adapter = object : ArrayAdapter<String>(this, R.layout.item_emoji, R.id.tvEmojiItem, avatarOptions) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val textView = view.findViewById<TextView>(R.id.tvEmojiItem)
                    textView.text = getItem(position)
                    return view
                }
            }
            grid.adapter = adapter
            grid.setOnItemClickListener { _, _, position, _ ->
                currentEmoji = avatarOptions[position]
                binding.tvSelectedEmoji.text = currentEmoji
                dialog.dismiss()
            }
            dialog.show()
        }

        binding.btnRegister.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (!isLocationEnabled()) {
                showLocationWarning()
                return@setOnClickListener
            }
            if (isUsernameTaken(user)) {
                Toast.makeText(this, "El nodo '$user' ya existe.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                val newProfile = UserProfile(id = UUID.randomUUID().toString(), name = user, avatar = currentEmoji)
                prefs.saveAccount(newProfile, pass)
                goToHome()
            } else {
                Toast.makeText(this, "Completa tus credenciales", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actualizarBotonCuentasGuardadas() {
        val cuentasGuardadas = prefs.getSavedAccountsArray()
        binding.btnViewAccounts.visibility = if (cuentasGuardadas.length() > 0) View.VISIBLE else View.GONE
    }

    private fun isUsernameTaken(name: String): Boolean {
        val cuentas = prefs.getSavedAccountsArray()
        for (i in 0 until cuentas.length()) {
            val acc = cuentas.getJSONObject(i)
            val profile = UserProfile.fromJson(acc.getString("profile"))
            if (profile.name.equals(name, ignoreCase = true)) return true
        }
        return false
    }

    private fun mostrarCuentasPreviasHermoso() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_saved_accounts)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val listView = dialog.findViewById<ListView>(R.id.listSavedAccounts)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseDialog)

        val cuentas = prefs.getSavedAccountsArray()
        val accountList = mutableListOf<SavedAccountItem>()

        for (i in 0 until cuentas.length()) {
            val acc = cuentas.getJSONObject(i)
            val profile = UserProfile.fromJson(acc.getString("profile"))
            accountList.add(SavedAccountItem(acc.getString("id"), profile.name, profile.avatar))
        }

        val adapter = object : ArrayAdapter<SavedAccountItem>(this, R.layout.item_saved_account, accountList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                var view = convertView
                if (view == null) {
                    view = LayoutInflater.from(context).inflate(R.layout.item_saved_account, parent, false)
                }

                val item = getItem(position)
                val tvAvatar = view!!.findViewById<TextView>(R.id.tvItemAvatar)
                val tvName = view.findViewById<TextView>(R.id.tvItemName)
                val btnDelete = view.findViewById<ImageView>(R.id.btnDeleteAccount)

                tvAvatar.text = if (item?.avatar?.startsWith("/") == true || item?.avatar?.startsWith("content://") == true) "🖼" else item?.avatar ?: "👤"
                tvName.text = item?.name

                // Ocultar X por defecto al refrescar la vista
                btnDelete.visibility = View.GONE

                // NUEVO: El clic principal de la fila para loguearse
                view.setOnClickListener {
                    dialog.dismiss()
                    pedirContrasenaParaLogin(item!!.id)
                }

                // NUEVO: Pulsación larga para revelar la "X"
                view.setOnLongClickListener {
                    btnDelete.visibility = if (btnDelete.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    true
                }

                // NUEVO: El clic en la "X" que invoca el layout elegante de HelpCoin
                btnDelete.setOnClickListener {
                    mostrarDialogoDesvincular(item!!, dialog)
                }

                return view
            }
        }

        listView.adapter = adapter
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun mostrarDialogoDesvincular(item: SavedAccountItem, menuDialog: android.app.Dialog) {
        val confirmDialog = android.app.Dialog(this)
        confirmDialog.setContentView(R.layout.dialog_unlink_node)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvMessage = confirmDialog.findViewById<TextView>(R.id.tvUnlinkMessage)
        tvMessage.text = "¿Estás seguro de que deseas olvidar la cuenta de ${item.name}?"

        confirmDialog.findViewById<Button>(R.id.btnCancelUnlink).setOnClickListener {
            confirmDialog.dismiss()
        }

        confirmDialog.findViewById<Button>(R.id.btnConfirmUnlink).setOnClickListener {
            prefs.deleteAccount(item.id)
            confirmDialog.dismiss()
            menuDialog.dismiss()
            actualizarBotonCuentasGuardadas()

            if (prefs.getSavedAccountsArray().length() > 0) {
                mostrarCuentasPreviasHermoso()
            } else {
                Toast.makeText(this@LoginActivity, "Bóveda vacía", Toast.LENGTH_SHORT).show()
            }
        }
        confirmDialog.show()
    }

    private fun pedirContrasenaParaLogin(userId: String) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_password)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etPassword = dialog.findViewById<EditText>(R.id.etDialogPassword)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmPassword)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelPassword)

        btnConfirm.setOnClickListener {
            val pass = etPassword.text.toString()
            if (prefs.verifyPassword(userId, pass)) {
                prefs.setCurrentSession(userId)
                dialog.dismiss()
                goToHome()
            } else {
                Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            mostrarCuentasPreviasHermoso()
        }

        dialog.setCancelable(false)
        dialog.show()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationWarning() {
        AlertDialog.Builder(this).setTitle("Ubicación Desactivada").setMessage("Debes encender la ubicación...").setPositiveButton("Ir a Ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.setCancelable(false).show()
    }

    private fun checkAndRequestPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isRegistered() && isLocationEnabled()) goToHome()
    }
}