package com.example.carrotnavi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AddressSearchAdapter(
    private val onItemClick: (KakaoDocument) -> Unit
) : RecyclerView.Adapter<AddressSearchAdapter.ViewHolder>() {

    private val items = mutableListOf<KakaoDocument>()

    fun submitList(newItems: List<KakaoDocument>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // We use LayoutInflater to inflate item_search_result.xml
        // But since we didn't setup viewbinding for it, we can just use findViewById
        val context = parent.context
        val id = context.resources.getIdentifier("item_search_result", "layout", context.packageName)
        val view = LayoutInflater.from(context).inflate(id, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPlaceName: TextView = itemView.findViewById(itemView.resources.getIdentifier("tvPlaceName", "id", itemView.context.packageName))
        private val tvRoadAddress: TextView = itemView.findViewById(itemView.resources.getIdentifier("tvRoadAddress", "id", itemView.context.packageName))
        private val tvAddress: TextView = itemView.findViewById(itemView.resources.getIdentifier("tvAddress", "id", itemView.context.packageName))

        fun bind(item: KakaoDocument) {
            tvPlaceName.text = item.place_name
            tvRoadAddress.text = item.road_address_name
            tvAddress.text = item.address_name

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
