package com.example.carrotnavi

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SearchHistoryAdapter(
    private val onItemClick: (SearchHistoryItem) -> Unit,
    private val onDeleteClick: (SearchHistoryItem) -> Unit,
    private val onBookmarkClick: (SearchHistoryItem) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<SearchHistoryItem>()
    private var selectedPosition = -1

    fun submitList(newItems: List<SearchHistoryItem>, autoSelectFirst: Boolean = false) {
        items.clear()
        items.addAll(newItems)
        selectedPosition = if (autoSelectFirst && items.isNotEmpty()) 0 else -1
        notifyDataSetChanged()
        if (selectedPosition == 0 && items.isNotEmpty()) {
            onItemClick(items[0])
        }
    }

    fun clearSelection() {
        val prev = selectedPosition
        selectedPosition = -1
        if (prev != -1) {
            notifyItemChanged(prev)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.llHistoryItemContainer)
        private val tvPlaceName: TextView = itemView.findViewById(R.id.tvHistoryPlaceName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvHistoryAddress)
        private val tvDate: TextView = itemView.findViewById(R.id.tvHistoryDate)
        private val btnBookmark: ImageView = itemView.findViewById(R.id.btnBookmarkHistory)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteHistory)

        fun bind(item: SearchHistoryItem, isSelected: Boolean) {
            tvPlaceName.text = item.place_name.ifEmpty { item.road_address_name.ifEmpty { item.address_name } }

            val address = if (item.road_address_name.isNotEmpty()) {
                item.road_address_name
            } else {
                item.address_name
            }
            tvAddress.text = address
            tvAddress.visibility = if (address.isNotEmpty()) View.VISIBLE else View.GONE

            tvDate.text = formatDate(item.timestamp)

            if (isSelected) {
                container.setBackgroundColor(Color.parseColor("#EFF6FF"))
                tvPlaceName.setTextColor(Color.parseColor("#2563EB"))
            } else {
                container.setBackgroundColor(Color.TRANSPARENT)
                tvPlaceName.setTextColor(Color.parseColor("#111111"))
            }

            container.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val prev = selectedPosition
                    selectedPosition = pos
                    if (prev != -1) notifyItemChanged(prev)
                    notifyItemChanged(selectedPosition)
                    onItemClick(items[pos])
                }
            }

            btnBookmark.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onBookmarkClick(items[pos])
                }
            }

            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(items[pos])
                }
            }
        }

        private fun formatDate(timestamp: Long): String {
            if (timestamp <= 0L) return ""
            val now = Calendar.getInstance()
            val itemCal = Calendar.getInstance().apply { timeInMillis = timestamp }

            return if (now.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == itemCal.get(Calendar.DAY_OF_YEAR)) {
                val timeFormat = SimpleDateFormat("오늘 a h:mm", Locale.KOREA)
                timeFormat.format(Date(timestamp))
            } else {
                val dateFormat = SimpleDateFormat("MM.dd", Locale.KOREA)
                dateFormat.format(Date(timestamp))
            }
        }
    }
}
