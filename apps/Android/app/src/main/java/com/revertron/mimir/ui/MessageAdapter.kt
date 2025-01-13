package com.revertron.mimir.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.animation.AnimatorInflater
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ChatActivity
import com.revertron.mimir.R
import com.revertron.mimir.storage.SqlStorage
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import android.media.MediaPlayer
import android.content.Context

interface PlaybackCallback {
    fun onPlaybackStateChanged(isPlaying: Boolean)
}

fun playVoiceMessage(context: Context, file: File, callback: PlaybackCallback) {
    if (!file.exists() || file.length() == 0L) {
        Log.e("MessageAdapter", "Voice message file does not exist or is empty")
        Toast.makeText(context, R.string.playback_error, Toast.LENGTH_SHORT).show()
        callback.onPlaybackStateChanged(false)
        return
    }

    try {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.setOnPreparedListener { mp ->
            try {
                mp.start()
                callback.onPlaybackStateChanged(true)
            } catch (e: IllegalStateException) {
                Log.e("MessageAdapter", "Error starting media player", e)
                Toast.makeText(context, R.string.playback_error, Toast.LENGTH_SHORT).show()
                mp.release()
                callback.onPlaybackStateChanged(false)
            }
        }
        mediaPlayer.setOnCompletionListener { mp ->
            mp.release()
            callback.onPlaybackStateChanged(false)
        }
        mediaPlayer.setOnErrorListener { mp, what, extra ->
            Log.e("MessageAdapter", "MediaPlayer error: what=$what extra=$extra")
            Toast.makeText(context, R.string.playback_error, Toast.LENGTH_SHORT).show()
            mp.release()
            callback.onPlaybackStateChanged(false)
            true
        }
        mediaPlayer.prepareAsync()
    } catch (e: Exception) {
        Log.e("MessageAdapter", "Failed to play voice message", e)
        Toast.makeText(context, R.string.playback_error, Toast.LENGTH_SHORT).show()
        callback.onPlaybackStateChanged(false)
    }
}

class MessageAdapter(
    private val storage: SqlStorage,
    private val userId: Long,
    private val multiChat: Boolean,
    private val myName: String,
    private val contactName: String,
    private val onClick: View.OnClickListener,
    private val onReplyClick: View.OnClickListener,
    private val onPictureClick: View.OnClickListener
): RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)
    private val messageIds = storage.getMessageIds(userId).toMutableList()

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val name: AppCompatTextView
        val message: AppCompatTextView
        val picture: AppCompatImageView
        val picturePanel: FrameLayout
        val time: AppCompatTextView
        val sent: AppCompatImageView
        val replyToName: AppCompatTextView
        val replyToText: AppCompatTextView
        val replyToPanel: View
        val playButton: AppCompatImageButton
        val duration: AppCompatTextView

        init {
            name = view.findViewById(R.id.name)
            duration = view.findViewById(R.id.duration)
            message = view.findViewById(R.id.text)
            picture = view.findViewById(R.id.picture)
            picturePanel = view.findViewById(R.id.picture_panel)
            time = view.findViewById(R.id.time)
            sent = view.findViewById(R.id.status_image)
            replyToName = view.findViewById(R.id.reply_contact_name)
            replyToText = view.findViewById(R.id.reply_text)
            replyToPanel = view.findViewById(R.id.reply_panel)
            playButton = view.findViewById(R.id.play_button)
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
        view.findViewById<View>(R.id.reply_panel).setOnClickListener(onReplyClick)
        view.findViewById<View>(R.id.picture).setOnClickListener(onPictureClick)

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
        holder.playButton.visibility = View.GONE
        holder.duration.visibility = View.GONE
        holder.picture.setImageDrawable(null)
        holder.picturePanel.visibility = View.GONE

        when (message.type) {
            0 -> { // Text message
                holder.message.text = message.getText()
                holder.picturePanel.visibility = View.GONE
            }
            1 -> { // Message with attachment
                if (message.data != null) {
                    val json = JSONObject(String(message.data))
                    when (json.optString("type")) {
                        "voice" -> {
                            holder.picturePanel.visibility = View.VISIBLE
                            holder.picture.visibility = View.GONE
                            holder.playButton.visibility = View.VISIBLE
                            holder.duration.visibility = View.VISIBLE
                            val voiceDuration = json.optLong("duration", 0)
                            val minutes = voiceDuration / 60
                            val seconds = voiceDuration % 60
                            holder.duration.text = holder.itemView.context.getString(R.string.voice_message_duration, minutes, seconds)
                            holder.playButton.stateListAnimator = AnimatorInflater.loadStateListAnimator(
                                holder.itemView.context,
                                R.drawable.button_play_animator
                            )

                            holder.playButton.setOnClickListener { view ->
                                view.isClickable = false
                                val filePath = holder.itemView.context.filesDir.absolutePath + "/files/" + json.getString("name")
                                val file = File(filePath)
                                
                                if (!file.exists() || file.length() == 0L) {
                                    Log.e("MessageAdapter", "Voice message file does not exist or is empty")
                                    Toast.makeText(holder.itemView.context, R.string.playback_error, Toast.LENGTH_SHORT).show()
                                    view.isClickable = true
                                    return@setOnClickListener
                                }

                                try {
                                    (holder.itemView.context as ChatActivity).apply {
                                        playVoiceMessage(holder.itemView.context, file, object : PlaybackCallback {
                                            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                                                holder.playButton.isClickable = !isPlaying
                                                holder.playButton.setImageResource(
                                                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                                )
                                            }
                                        })
                                    }
                                } catch (e: Exception) {
                                    Log.e("MessageAdapter", "Error playing voice message", e)
                                    Toast.makeText(holder.itemView.context, R.string.playback_error, Toast.LENGTH_SHORT).show()
                                    view.isClickable = true
                                }
                            }
                        }
                        else -> {
                            val cachePath = holder.itemView.context.cacheDir.absolutePath + "/files/" + json.getString("name")
                            val filePath = holder.itemView.context.filesDir.absolutePath + "/files/" + json.getString("name")
                            val cacheFile = File(cachePath)
                            if (cacheFile.exists()) {
                                val uri: Uri = Uri.fromFile(cacheFile)
                                holder.picture.setImageURI(uri)
                                holder.picture.tag = Uri.fromFile(File(filePath))
                            } else {
                                val file = File(filePath)
                                if (file.exists()) {
                                    val uri: Uri = Uri.fromFile(file)
                                    holder.picture.setImageURI(uri)
                                    holder.picture.tag = uri
                                }
                            }
                            holder.picturePanel.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

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

        val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
        if (position == 0) {
            params.updateMargins(top = params.bottomMargin)
        } else {
            params.updateMargins(top = 0)
        }

        storage.setMessageRead(userId, message.id, true)
    }

    private fun formatTime(time: Long): String {
        val date = Date(time)
        val diff = Date().time - date.time
        return if (diff > 86400 * 1000) {
            "${dateFormatter.format(date)} ${timeFormatter.format(date)}"
        } else {
            timeFormatter.format(date)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val message = storage.getMessage(messageIds[position]) ?: return 0
        return if (message.incoming) 0 else 1
    }

    override fun getItemCount(): Int {
        return messageIds.size
    }

    fun addMessageId(messageId: Long) {
        if (!messageIds.contains(messageId)) {
            messageIds.add(messageId)
            notifyItemInserted(messageIds.size - 1)
        }
    }

    fun deleteMessageId(messageId: Long) {
        val index = messageIds.indexOf(messageId)
        if (index >= 0) {
            messageIds.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun setMessageDelivered(id: Long, delivered: Boolean) {
        val index = messageIds.indexOf(id)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun getMessageIdPosition(id: Long): Int {
        return messageIds.indexOf(id)
    }

    fun clearMessages() {
        val size = messageIds.size
        messageIds.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun updateMessage(messageId: Long) {
        val index = messageIds.indexOf(messageId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun isConnected(): Boolean {
        return true // TODO: Implement actual connection check
    }
}
