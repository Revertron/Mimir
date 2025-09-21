package com.revertron.mimir.ui

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R

class SettingsAdapter(
    private val items: List<Item>,
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /* -------------------- data model -------------------- */
    data class Item(
        val id: Int,                       // stable identifier (R.string.* or any unique Int)
        @StringRes val titleRes: Int,
        @StringRes val descriptionRes: Int,
        val isSwitch: Boolean,
        var checked: Boolean = false
    )

    /* -------------------- callbacks -------------------- */
    interface Listener {
        fun onSwitchToggled(@StringRes id: Int, isChecked: Boolean)
        fun onItemClicked(@StringRes id: Int)
    }

    /* -------------------- view types -------------------- */
    companion object {
        private const val TYPE_SWITCH = 0
        private const val TYPE_PLAIN  = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isSwitch) TYPE_SWITCH else TYPE_PLAIN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SWITCH) {
            SwitchVH(inflater.inflate(R.layout.row_settings_switch, parent, false))
        } else {
            PlainVH(inflater.inflate(R.layout.row_settings_plain, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val res: Resources = holder.itemView.context.resources

        when (holder) {
            is SwitchVH -> {
                holder.title.text = res.getString(item.titleRes)
                holder.description.text = res.getString(item.descriptionRes)
                holder.switchWidget.isChecked = item.checked

                holder.switchWidget.setOnCheckedChangeListener { _, isChecked ->
                    item.checked = isChecked
                    listener.onSwitchToggled(item.id, isChecked)
                }
                holder.itemView.setOnClickListener { holder.switchWidget.isChecked = !holder.switchWidget.isChecked }
            }
            is PlainVH  -> {
                holder.title.text = res.getString(item.titleRes)
                holder.description.text = res.getString(item.descriptionRes)
                holder.itemView.setOnClickListener { listener.onItemClicked(item.id) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /* -------------------- ViewHolders -------------------- */
    class SwitchVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.title)
        val description: TextView = itemView.findViewById(R.id.description)
        val switchWidget: SwitchCompat = itemView.findViewById(R.id.switchWidget)
    }

    class PlainVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.title)
        val description: TextView = itemView.findViewById(R.id.description)
    }
}