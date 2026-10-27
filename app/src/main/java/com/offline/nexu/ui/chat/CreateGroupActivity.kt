package com.offline.nexu.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.ui.chat.group.GroupManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var selectedContactsIds: MutableList<String>
    private var selectedImageUri: Uri? = null
    private var auraColor: Int = 0

    private val requestGalleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) openCustomGalleryBottomSheet()
        else Toast.makeText(this, "Permiso denegado para ver fotos", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_group)

        auraColor = ThemeUtils.getAuraColor(this)
        val incomingContacts = intent.getStringArrayListExtra("EXTRA_SELECTED_CONTACTS")
        selectedContactsIds = incomingContacts?.toMutableList() ?: mutableListOf()

        if (selectedContactsIds.isEmpty()) {
            Toast.makeText(this, "Error: No hay participantes seleccionados", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        aplicarColorAura()

        val switchEphemeral = findViewById<Switch>(R.id.switchEphemeral)
        val llEphemeralSettings = findViewById<LinearLayout>(R.id.llEphemeralSettings)
        val etEphemeralValue = findViewById<EditText>(R.id.etEphemeralValue)
        val spinnerEphemeralUnit = findViewById<Spinner>(R.id.spinnerEphemeralUnit)

        val unitsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días"))
        spinnerEphemeralUnit.adapter = unitsAdapter

        switchEphemeral.setOnCheckedChangeListener { _, isChecked ->
            llEphemeralSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        findViewById<View>(R.id.cvGroupAvatar).setOnClickListener {
            comprobarPermisosYAbrirGaleria()
        }

        val rvSelectedContacts = findViewById<RecyclerView>(R.id.rvSelectedContacts)
        rvSelectedContacts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSelectedContacts.adapter = SelectedContactsAdapter(selectedContactsIds)

        findViewById<Button>(R.id.btnConfirmCreateGroup).setOnClickListener {
            val groupName = findViewById<EditText>(R.id.etGroupName).text.toString().trim()
            if (groupName.isEmpty()) {
                Toast.makeText(this, "Ingresa un nombre para el grupo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedContactsIds.isEmpty()) {
                Toast.makeText(this, "Debe quedar al menos un participante", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var expirationTime: Long? = null
            if (switchEphemeral.isChecked) {
                val valueStr = etEphemeralValue.text.toString().trim()
                val value = valueStr.toLongOrNull() ?: 0L
                if (value <= 0) {
                    Toast.makeText(this, "Ingresa un valor de tiempo válido mayor a 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val multiplier = when (spinnerEphemeralUnit.selectedItemPosition) {
                    0 -> 1000L; 1 -> 60 * 1000L; 2 -> 60 * 60 * 1000L; 3 -> 24 * 60 * 60 * 1000L; else -> 1000L
                }
                expirationTime = System.currentTimeMillis() + (value * multiplier)
            }

            // Delegamos la lógica al GroupManager
            GroupManager.createGroupNetworkCommand(this, groupName, selectedContactsIds, selectedImageUri, expirationTime) { newGroupId ->
                Toast.makeText(this, "Grupo creado exitosamente", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("EXTRA_IS_GROUP", true)
                    putExtra("EXTRA_GROUP_ID", newGroupId)
                    putExtra("EXTRA_GROUP_NAME", groupName)
                }
                startActivity(intent)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun comprobarPermisosYAbrirGaleria() {
        val permissionImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permissionImages) != PackageManager.PERMISSION_GRANTED) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestGalleryPermissionLauncher.launch(perms)
        } else {
            openCustomGalleryBottomSheet()
        }
    }

    private fun openCustomGalleryBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_custom_gallery, null)
        val rvGallery = view.findViewById<RecyclerView>(R.id.rvCustomGallery)

        rvGallery.layoutManager = GridLayoutManager(this, 3)
        val mediaUris = obtenerMediosLocales()

        rvGallery.adapter = CustomGalleryAdapter(mediaUris) { uri ->
            procesarUriSeleccionada(uri)
            dialog.dismiss()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun obtenerMediosLocales(): List<Pair<Uri, Boolean>> {
        val mediaList = mutableListOf<Pair<Uri, Boolean>>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"

        try {
            contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                var count = 0
                while (cursor.moveToNext() && count < 60) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = Uri.withAppendedPath(uri, "" + id)
                    mediaList.add(Pair(contentUri, false))
                    count++
                }
            }
        } catch (e: Exception) {}
        return mediaList
    }

    private fun procesarUriSeleccionada(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localUri = copyImageToLocal(uri)
            withContext(Dispatchers.Main) {
                if (localUri != null) {
                    selectedImageUri = localUri
                    val ivAvatar = findViewById<ImageView>(R.id.ivGroupAvatar)
                    ivAvatar.setImageURI(localUri)
                    ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivAvatar.imageTintList = null
                }
            }
        }
    }

    inner class CustomGalleryAdapter(private val media: List<Pair<Uri, Boolean>>, private val onClick: (Uri) -> Unit) : RecyclerView.Adapter<CustomGalleryAdapter.GalleryViewHolder>() {
        inner class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivGalleryThumbnail)
            val iconVideo: ImageView = view.findViewById(R.id.ivVideoIcon)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            return GalleryViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_media, parent, false))
        }
        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val (uri, _) = media[position]
            holder.iconVideo.visibility = View.GONE
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null) }
                    else { MediaStore.Images.Media.getBitmap(contentResolver, uri) }
                    withContext(Dispatchers.Main) { holder.image.setImageBitmap(bmp) }
                } catch (e: Exception) {}
            }
            holder.itemView.setOnClickListener { onClick(uri) }
        }
        override fun getItemCount() = media.size
    }

    private fun aplicarColorAura() {
        findViewById<TextView>(R.id.tvCreateGroupTitle).setTextColor(auraColor)
        findViewById<Button>(R.id.btnConfirmCreateGroup).backgroundTintList = ColorStateList.valueOf(auraColor)

        val ivAvatar = findViewById<ImageView>(R.id.ivGroupAvatar)
        if (selectedImageUri == null) {
            ivAvatar.imageTintList = ColorStateList.valueOf(auraColor)
        }
    }

    private fun copyImageToLocal(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val mediaDir = File(getExternalFilesDir(null), "NexuChat/Media/Groups")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val file = File(mediaDir, "GRP_IMG_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Uri.fromFile(file)
        } catch (e: Exception) { null }
    }

    inner class SelectedContactsAdapter(private val contacts: MutableList<String>) : RecyclerView.Adapter<SelectedContactsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivAvatar: ImageView = view.findViewById(R.id.ivSelectedAvatar)
            val ivRemove: ImageView = view.findViewById(R.id.ivRemoveContact)
            val tvName: TextView = view.findViewById(R.id.tvSelectedName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvName.text = contacts[position]
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            holder.ivRemove.backgroundTintList = ColorStateList.valueOf(auraColor)

            holder.ivRemove.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    contacts.removeAt(currentPos)
                    notifyItemRemoved(currentPos)

                    if (contacts.isEmpty()) {
                        Toast.makeText(this@CreateGroupActivity, "Has eliminado a todos los participantes.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }

        override fun getItemCount() = contacts.size
    }
}