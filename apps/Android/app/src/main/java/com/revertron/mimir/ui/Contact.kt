package com.revertron.mimir.ui

data class Contact(val id: Long, val pubkey: ByteArray, val name: String, var lastMessage: String, var lastMessageTime: Long, var lastMessageDelivered: Boolean?, var unread: Int)
