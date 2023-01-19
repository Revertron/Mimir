package com.revertron.mimir.ui

import com.revertron.mimir.storage.SqlStorage

data class Contact(
    val id: Long,
    val pubkey: ByteArray,
    val name: String,
    var lastMessage: SqlStorage.Message?,
    var unread: Int
)
