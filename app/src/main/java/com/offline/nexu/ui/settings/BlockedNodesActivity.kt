package com.offline.nexu.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.offline.nexu.R

class BlockedNodesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_nodes)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val listView = findViewById<ListView>(R.id.listBlockedNodes)
        val tvEmpty = findViewById<TextView>(R.id.tvEmptyList)

        // Simulación de una lista vacía de nodos bloqueados (Aquí puedes cargar desde tu base de datos)
        val blockedNodes = mutableListOf<String>()

        if (blockedNodes.isEmpty()) {
            listView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            listView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, blockedNodes)
            listView.adapter = adapter
        }
    }
}