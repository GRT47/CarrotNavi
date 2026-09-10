package com.example.carrotnavi

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AddressSearchAdapter(
    private val onItemClick: (KakaoDocument) -> Unit,
    private val onBookmarkClick: (KakaoDocument) -> Unit
) : RecyclerView.Adapter<AddressSearchAdapter.ViewHolder>() {

    private val items = mutableListOf<KakaoDocument>()
    private var selectedPosition = RecyclerView.NO_POSITION

    fun submitList(newItems: List<KakaoDocument>, selectFirst: Boolean = false) {
        items.clear()
        items.addAll(newItems)
        selectedPosition = if (selectFirst && newItems.isNotEmpty()) 0 else RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        val prevPos = selectedPosition
        selectedPosition = position
        if (prevPos != RecyclerView.NO_POSITION) {
            notifyItemChanged(prevPos)
        }
        if (selectedPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(selectedPosition)
        }
    }

    fun getSelectedItem(): KakaoDocument? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }

    fun clearSelection() {
        val prevPos = selectedPosition
        selectedPosition = RecyclerView.NO_POSITION
        if (prevPos != RecyclerView.NO_POSITION) {
            notifyItemChanged(prevPos)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPlaceName: TextView = itemView.findViewById(R.id.tvPlaceName)
        private val tvRoadAddress: TextView = itemView.findViewById(R.id.tvRoadAddress)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
        private val btnBookmark: ImageView = itemView.findViewById(R.id.btnBookmark)

        fun bind(item: KakaoDocument, isSelected: Boolean) {
            tvPlaceName.text = item.place_name
            tvRoadAddress.text = item.road_address_name
            tvAddress.text = item.address_name

            if (isSelected) {
                itemView.setBackgroundColor(Color.parseColor("#EFF6FF"))
                tvPlaceName.setTextColor(Color.parseColor("#2563EB"))
                tvPlaceName.typeface = Typeface.DEFAULT_BOLD
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
                tvPlaceName.setTextColor(Color.parseColor("#111111"))
                tvPlaceName.typeface = Typeface.DEFAULT
            }

            itemView.setOnClickListener {
                val currentPos = adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val prevPos = selectedPosition
                    selectedPosition = currentPos
                    if (prevPos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(prevPos)
                    }
                    notifyItemChanged(selectedPosition)
                    onItemClick(item)
                }
            }

            btnBookmark.setOnClickListener {
                val currentPos = adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    onBookmarkClick(items[currentPos])
                }
            }
        }
    }
}
