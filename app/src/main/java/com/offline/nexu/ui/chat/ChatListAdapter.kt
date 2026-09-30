package com.offline.nexu.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.databinding.ItemChatListBinding

class ChatListAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {
    // Aquí guardaré la lista de mensajes (o chats) que quiero mostrar en la pantalla.
    private var chats = listOf<MessageEntity>()

    fun submitList(list: List<MessageEntity>) {
        // Esta función me sirve para actualizar la lista de chats. 
        // Primero guardo la nueva lista, y luego le aviso a mi RecyclerView que los datos cambiaron para que vuelva a dibujarlos.
        chats = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Aquí "inflo" o preparo el diseño de cada fila individual (item_chat_list) usando ViewBinding.
        val binding = ItemChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // En esta función agarro el chat específico dependiendo de la posición (fila) en la que estoy.
        val chat = chats[position]
        
        // Pongo el nombre o ID del contacto en el texto principal.
        holder.binding.tvName.text = chat.endpointId
        
        // Aquí pongo el último mensaje que enviaron o recibieron. 
        // Si el texto está vacío, asumo que mandaron una imagen y muestro "📷 Imagen".
        holder.binding.tvLastMessage.text = chat.text.ifEmpty { "📷 Imagen" }
        
        // Por último, configuro para que cuando el usuario toque esta fila, abra el chat con esta persona.
        holder.binding.root.setOnClickListener { onClick(chat.endpointId) }
    }

    override fun getItemCount() = chats.size
    
    // Esta es mi clase auxiliar (ViewHolder) que mantiene agarradas todas las partes visuales de la fila.
    class ViewHolder(val binding: ItemChatListBinding) : RecyclerView.ViewHolder(binding.root)
}