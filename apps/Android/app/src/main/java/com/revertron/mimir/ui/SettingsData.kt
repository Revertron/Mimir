package com.revertron.mimir.ui

import android.content.Context
import androidx.preference.PreferenceManager
import com.revertron.mimir.R

object SettingsData {

    const val KEY_AUTO_UPDATES = "auto-updates"
    const val KEY_IMAGES_FORMAT = "images-format"
    const val KEY_IMAGES_QUALITY = "images-quality"
    const val KEY_MESSAGE_FONT_SIZE = "message-font-size"

    fun create(context: Context): List<SettingsAdapter.Item> {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)

        return listOf(
            SettingsAdapter.Item(
                id = R.string.configure_peers,
                titleRes = R.string.configure_peers,
                descriptionRes = R.string.configure_peers_description,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.resize_big_pics,
                titleRes = R.string.resize_big_pics,
                descriptionRes = R.string.resize_big_pics_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.message_font_size,
                titleRes = R.string.message_font_size,
                descriptionRes = R.string.message_font_size_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.backup_and_restore,
                titleRes = R.string.backup_and_restore,
                descriptionRes = R.string.backup_and_restore_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.automatic_updates_checking,
                titleRes = R.string.automatic_updates_checking,
                descriptionRes = R.string.automatic_updates_checking_desc,
                isSwitch = true,
                checked = sp.getBoolean(KEY_AUTO_UPDATES, true)
            ),

            SettingsAdapter.Item(
                id = R.string.check_for_updates,
                titleRes = R.string.check_for_updates,
                descriptionRes = R.string.check_for_updates_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.advanced,
                titleRes = R.string.advanced,
                descriptionRes = R.string.advanced_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.action_about,
                titleRes = R.string.action_about,
                descriptionRes = R.string.action_about_desc,
                isSwitch = false,
                checked = false
            )
        )
    }
}