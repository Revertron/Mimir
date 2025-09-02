package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R

class PeerAdapter(
    private val data: List<String>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerVH>() {

    inner class PeerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.peerText)
        val removeBtn: ImageButton = itemView.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerVH {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_peer, parent, false)
        return PeerVH(view)
    }

    override fun onBindViewHolder(holder: PeerVH, position: Int) {
        holder.text.text = data[position]
        holder.removeBtn.setOnClickListener { onRemove(holder.adapterPosition) }
    }

    override fun getItemCount(): Int = data.size
}