package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.convertToTimeZone
import com.revertron.mimir.storage.SqlStorage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class MessageAdapter(
    private val storage: SqlStorage,
    private val userId: Long,
    private val multiChat: Boolean,
    private val myName: String,
    private val contactName: String,
    private val onClick: View.OnClickListener,
    private val onReplyClick: View.OnClickListener
): RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)
    private val messageIds = storage.getMessageIds(userId).toMutableList()

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val name: AppCompatTextView
        val message: AppCompatTextView
        val time: AppCompatTextView
        val sent: AppCompatImageView
        val replyToName: AppCompatTextView
        val replyToText: AppCompatTextView
        val replyToPanel: View

        init {
            name = view.findViewById(R.id.name)
            message = view.findViewById(R.id.text)
            time = view.findViewById(R.id.time)
            sent = view.findViewById(R.id.status_image)
            replyToName = view.findViewById(R.id.reply_contact_name)
            replyToText = view.findViewById(R.id.reply_text)
            replyToPanel = view.findViewById(R.id.reply_panel)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        view.setOnClickListener(onClick)
        //TODO make item background reflect touches
        view.findViewById<View>(R.id.reply_panel).setOnClickListener(onReplyClick)

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
        holder.message.text = message.getText()
        holder.time.text = formatTime(message.time)
        holder.itemView.tag = message.id
        holder.sent.tag = message.delivered

        if (message.replyTo != 0L) {
            val replyToMessage = storage.getMessage(message.replyTo, true)
            if (replyToMessage != null) {
                holder.replyToPanel.visibility = View.VISIBLE
                holder.replyToPanel.tag = replyToMessage.id
                holder.replyToName.text = contactName
                holder.replyToText.text = replyToMessage.getText()
            } else {
                // Message may be deleted
                holder.replyToPanel.visibility = View.GONE
                holder.replyToName.text = ""
                holder.replyToText.text = ""
            }
        } else {
            holder.replyToPanel.visibility = View.GONE
            holder.replyToName.text = ""
            holder.replyToText.text = ""
        }

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

    private fun formatTime(time: Long): String {
        val date = Date(convertToTimeZone(time))
        val diff = Date().time - date.time
        return if (diff > 86400 * 1000) {
            "${dateFormatter.format(date)} ${timeFormatter.format(date)}"
        } else {
            timeFormatter.format(date)
        }
    }

    override fun getItemViewType(position: Int): Int {
        //TODO optimize double get of message from DB
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

    fun deleteMessageId(messageId: Long) {
        val index = messageIds.indexOf(messageId)
        if (index > 0) {
            messageIds.remove(messageId)
            notifyItemRemoved(index)
        }
    }

    fun setMessageDelivered(id: Long, delivered: Boolean) {
        for ((index, message) in messageIds.withIndex()) {
            if (message == id) {
                notifyItemChanged(index)
                break
            }
        }
    }

    fun getMessageIdPosition(id: Long): Int {
        for ((index, message) in messageIds.withIndex()) {
            if (message == id) {
                return index
            }
        }
        return -1
    }
}