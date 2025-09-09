package com.revertron.mimir.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R

class LogAdapter(private val logs: List<String>): RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    class LogViewHolder(val textView: TextView): RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val textView = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        textView.setTextIsSelectable(true)
        return LogViewHolder(textView)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.textView.text = logs[position]
        holder.itemView.setOnLongClickListener {
            val clipboard = holder.itemView.context
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Log line", holder.textView.text)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(holder.itemView.context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun getItemCount() = logs.size
}