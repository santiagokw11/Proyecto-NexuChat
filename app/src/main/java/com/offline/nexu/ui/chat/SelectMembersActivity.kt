package com.offline.nexu.ui.chat

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectMembersActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var myLocalUserId: String
    private var allContacts: List<String> = emptyList()
    private val selectedContacts = mutableSetOf<String>()
    private lateinit var adapter: ContactSelectionAdapter
    private var preselectedUser: String? = null

    // NUEVO: Launcher para saber cuándo se creó el grupo y poder cerrar esta pantalla
    private val createGroupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            finish() // Cierra la lista de miembros para que no aparezca al darle atrás
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_members)

        database = AppDatabase.getDatabase(this)
        myLocalUserId = Prefs(this).getUserProfile().id

        preselectedUser = intent.getStringExtra("PRESELECTED_USER")
        if (preselectedUser != null) {
            selectedContacts.add(preselectedUser!!)
        }

        aplicarColorAura()
        actualizarContador()

        val rvContacts = findViewById<RecyclerView>(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val recentMessages = database.nexuDao().getRecentChats(myLocalUserId)
            allContacts = recentMessages.filter { !it.isGroup && !it.endpointId.startsWith("GRP_") }
                .map { it.endpointId }.distinct()

            withContext(Dispatchers.Main) {
                adapter = ContactSelectionAdapter(allContacts) { user, isChecked ->
                    if (isChecked) selectedContacts.add(user) else selectedContacts.remove(user)
                    actualizarContador()
                }
                rvContacts.adapter = adapter
            }
        }

        findViewById<EditText>(R.id.etSearchContacts).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filterList(s.toString())
            }
        })

        findViewById<Button>(R.id.btnNextStep).setOnClickListener {
            if (selectedContacts.isEmpty()) {
                Toast.makeText(this, "Selecciona al menos un participante", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, CreateGroupActivity::class.java).apply {
                putStringArrayListExtra("EXTRA_SELECTED_CONTACTS", ArrayList(selectedContacts.toList()))
            }
            // NUEVO: Usamos el launcher en vez de startActivity normal
            createGroupLauncher.launch(intent)
        }
    }

    private fun aplicarColorAura() {
        val auraColor = ThemeUtils.getAuraColor(this)
        findViewById<TextView>(R.id.tvSelectMembersTitle).setTextColor(auraColor)
        findViewById<Button>(R.id.btnNextStep).backgroundTintList = android.content.res.ColorStateList.valueOf(auraColor)
    }

    private fun actualizarContador() {
        val titleStr = if (selectedContacts.isEmpty()) "Seleccionar participantes" else "Participantes (${selectedContacts.size})"
        findViewById<TextView>(R.id.tvSelectMembersTitle).text = titleStr
    }

    inner class ContactSelectionAdapter(
        private val contacts: List<String>,
        private val onSelectionChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<ContactSelectionAdapter.ViewHolder>() {

        private var filteredContacts: List<String> = contacts

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
            val tvName: TextView = view.findViewById(R.id.tvContactName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = filteredContacts[position]
            holder.tvName.text = contact

            holder.cbSelect.isChecked = selectedContacts.contains(contact)

            holder.itemView.setOnClickListener {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
                onSelectionChanged(contact, holder.cbSelect.isChecked)
            }
        }

        override fun getItemCount() = filteredContacts.size

        fun filterList(query: String) {
            filteredContacts = if (query.isEmpty()) {
                contacts
            } else {
                contacts.filter { it.contains(query, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }
    }
}