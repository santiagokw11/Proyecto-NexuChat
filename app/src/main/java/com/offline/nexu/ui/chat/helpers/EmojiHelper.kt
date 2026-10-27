package com.offline.nexu.ui.chat.helpers

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.utils.EmojiProvider
import com.offline.nexu.utils.ThemeUtils

class EmojiHelper(
    private val rootView: View,
    private val auraColor: Int,
    private val onEmojiClick: (String) -> Unit
) {
    private val flEmojiPicker: View? = rootView.findViewById(R.id.flEmojiPicker)
    private val rvEmojis: RecyclerView? = rootView.findViewById(R.id.rvEmojis)

    fun setup() {
        if (rvEmojis == null) return
        rvEmojis.layoutManager = GridLayoutManager(rootView.context, 8)
        rvEmojis.adapter = EmojiAdapter(EmojiProvider.getAllEmojis()) { onEmojiClick(it) }

        val tabs = listOf(
            rootView.findViewById<TextView>(R.id.tabSmileys),
            rootView.findViewById<TextView>(R.id.tabPeople),
            rootView.findViewById<TextView>(R.id.tabAnimals),
            rootView.findViewById<TextView>(R.id.tabFood),
            rootView.findViewById<TextView>(R.id.tabTravel),
            rootView.findViewById<TextView>(R.id.tabObjects),
            rootView.findViewById<TextView>(R.id.tabSymbols)
        )

        var currentOffset = 0
        val offsets = mutableListOf<Int>()
        val categories = listOf(
            EmojiProvider.emojisSmileys, EmojiProvider.emojisPeople, EmojiProvider.emojisAnimals,
            EmojiProvider.emojisFood, EmojiProvider.emojisTravel, EmojiProvider.emojisObjects, EmojiProvider.emojisSymbols
        )

        categories.forEach { category ->
            offsets.add(currentOffset)
            currentOffset += category.size
        }

        tabs.forEachIndexed { index, tab ->
            tab?.setOnClickListener { clickedTab ->
                tabs.forEach { t ->
                    if (t == clickedTab) {
                        t?.setTextColor(auraColor)
                        t?.setTypeface(null, android.graphics.Typeface.BOLD)
                    } else {
                        t?.setTextColor(ContextCompat.getColor(rootView.context, R.color.text_secondary))
                        t?.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                }
                (rvEmojis.layoutManager as GridLayoutManager).scrollToPositionWithOffset(offsets[index], 0)
            }
        }
    }

    fun toggle() {
        if (flEmojiPicker?.visibility == View.VISIBLE) hide() else flEmojiPicker?.visibility = View.VISIBLE
    }

    fun hide() {
        flEmojiPicker?.visibility = View.GONE
    }

    fun isVisible() = flEmojiPicker?.visibility == View.VISIBLE

    inner class EmojiAdapter(private val emojis: List<String>, private val onClick: (String) -> Unit) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {
        inner class EmojiViewHolder(val tvEmoji: TextView) : RecyclerView.ViewHolder(tvEmoji)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                textSize = 32f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 24, 0, 24)
                isClickable = true
                isFocusable = true
                setBackgroundResource(ThemeUtils.getSelectableItemBackground(parent.context))
            }
            return EmojiViewHolder(textView)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            holder.tvEmoji.text = emojis[position]
            holder.tvEmoji.setOnClickListener { onClick(emojis[position]) }
        }
        override fun getItemCount() = emojis.size
    }
}