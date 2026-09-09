package com.example.carrotnavi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FavoritesAdapter(
    private val onItemClick: (BookmarkItem) -> Unit,
    private val onDeleteClick: (BookmarkItem) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    private val items = mutableListOf<BookmarkItem>()

    fun submitList(newItems: List<BookmarkItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPlaceName: TextView = itemView.findViewById(R.id.tvFavoritePlaceName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvFavoriteAddress)
        private val btnStartGuide: View? = itemView.findViewById(R.id.btnStartGuide)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteFavorite)

        fun bind(item: BookmarkItem) {
            tvPlaceName.text = item.place_name
            val address = if (item.road_address_name.isNotEmpty()) {
                item.road_address_name
            } else {
                item.address_name
            }
            tvAddress.text = address
            tvAddress.visibility = if (address.isNotEmpty()) View.VISIBLE else View.GONE

            val clickAction = View.OnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(items[pos])
                }
            }
            itemView.setOnClickListener(clickAction)
            btnStartGuide?.setOnClickListener(clickAction)

            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(items[pos])
                }
            }
        }
    }
}
