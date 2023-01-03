package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.storage.SqlStorage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class MessageAdapter(private val storage: SqlStorage, private val userId: Long, private val multiChat: Boolean, private val myName: String, private val contactName: String, private val onclick: View.OnClickListener?):
    RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val messageIds = storage.getMessageIds(userId).toMutableList()

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val name: AppCompatTextView
        val message: AppCompatTextView
        val time: AppCompatTextView
        val sent: AppCompatImageView

        init {
            name = view.findViewById(R.id.name)
            message = view.findViewById(R.id.text)
            time = view.findViewById(R.id.time)
            sent = view.findViewById(R.id.status_image)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        view.setOnClickListener(onclick)
        //view.setOnLongClickListener(onlongclick)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = storage.getMessage(messageIds[position]) ?: return
        if (multiChat) {
            val name = if (message.incoming) contactName else myName
            holder.name.text = "$name:"
            holder.name.visibility = View.VISIBLE
        } else {
            holder.name.visibility = View.GONE
        }
        holder.message.text = String(message.message!!)
        holder.time.text = timeFormatter.format(Date(message.time))
        holder.itemView.tag = message.id
        holder.sent.tag = message.delivered
        if (message.delivered) {
            holder.sent.setImageResource(R.drawable.ic_message_delivered)
        } else {
            holder.sent.setImageResource(R.drawable.ic_message_not_sent)
        }
        val layoutParams = holder.itemView.layoutParams as RecyclerView.LayoutParams
        if (position == 0) {
            layoutParams.updateMargins(top = layoutParams.bottomMargin)
        } else {
            layoutParams.updateMargins(top = 0)
        }
        //TODO somehow propagate this event to notification manager to cancel notification if it exists
        storage.setMessageRead(userId, message.id, true)
    }

    override fun getItemViewType(position: Int): Int {
        val message = storage.getMessage(messageIds[position]) ?: return 0
        return if (message.incoming) 0 else 1
    }

    override fun getItemCount(): Int {
        return messageIds.size
    }

    fun addMessageId(messageId: Long) {
        messageIds.add(messageId)
        notifyItemInserted(messageIds.size - 1)
    }

    fun setMessageDelivered(id: Long, delivered: Boolean) {
        for ((index, message) in messageIds.withIndex()) {
            if (message == id) {
                notifyItemChanged(index)
                break
            }
        }
    }
}