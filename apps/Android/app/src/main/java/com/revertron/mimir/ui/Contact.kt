package com.revertron.mimir.ui

data class Contact(val id: Long, val pubkey: String, val name: String, var lastMessage: String, var lastMessageTime: Long, var unread: Int)
