package com.offline.nexu.ui.chat

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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

    private val createGroupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            finish()
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
                if (::adapter.isInitialized) {
                    adapter.filterList(s.toString())
                }
            }
        })

        findViewById<ExtendedFloatingActionButton>(R.id.btnNextStep).setOnClickListener {
            if (selectedContacts.isEmpty()) {
                Toast.makeText(this, "Selecciona al menos un participante", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, CreateGroupActivity::class.java).apply {
                putStringArrayListExtra("EXTRA_SELECTED_CONTACTS", ArrayList(selectedContacts.toList()))
            }
            createGroupLauncher.launch(intent)
        }
    }

    private fun aplicarColorAura() {
        val auraColor = ThemeUtils.getAuraColor(this)
        findViewById<TextView>(R.id.tvSelectMembersTitle).setTextColor(auraColor)
        findViewById<ExtendedFloatingActionButton>(R.id.btnNextStep).backgroundTintList = ColorStateList.valueOf(auraColor)
    }

    private fun actualizarContador() {
        val titleStr = if (selectedContacts.isEmpty()) "Seleccionar participantes" else "Participantes (${selectedContacts.size})"
        findViewById<TextView>(R.id.tvSelectMembersTitle).text = titleStr

        val btnNext = findViewById<ExtendedFloatingActionButton>(R.id.btnNextStep)
        if (selectedContacts.isEmpty()) {
            btnNext.shrink()
        } else {
            btnNext.extend()
        }
    }

    inner class ContactSelectionAdapter(
        private val contacts: List<String>,
        private val onSelectionChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<ContactSelectionAdapter.ViewHolder>() {

        private var filteredContacts: List<String> = contacts
        private val auraColor = ThemeUtils.getAuraColor(this@SelectMembersActivity)

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
            val tvName: TextView = view.findViewById(R.id.tvContactName)
            val cvAvatar: CardView = view.findViewById(R.id.cvAvatar)
            val tvAvatarInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = filteredContacts[position]
            holder.tvName.text = contact

            holder.tvAvatarInitial.text = if (contact.isNotEmpty()) contact.take(1).uppercase() else "?"
            holder.cvAvatar.setCardBackgroundColor(auraColor)
            holder.cbSelect.buttonTintList = ColorStateList.valueOf(auraColor)

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