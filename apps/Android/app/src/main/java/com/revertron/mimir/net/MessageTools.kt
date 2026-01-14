package com.revertron.mimir.net

import android.content.Context
import android.util.Log
import com.revertron.mimir.getImageExtensionOrNull
import com.revertron.mimir.randomString
import com.revertron.mimir.saveFileForMessage
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.storage.SqlStorage
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream


private const val TAG = "MessageTools"

/**
 * Parses and saves a group chat message.
 * Handles decryption, deserialization, media attachments, deduplication, and database storage.
 *
 * @param chatId The group chat ID
 * @param messageId The server message ID
 * @param guid The message GUID (for deduplication)
 * @param author The message author's public key
 * @param encryptedData The encrypted message data
 * @param storage The storage instance
 * @param broadcast Whether to broadcast the message to activities (true for real-time, false for sync)
 * @param fromSync Whether this message is being synced from server (true) or received real-time (false)
 * @return Local message ID if successful, 0 if skipped/failed
 */
fun parseAndSaveGroupMessage(
    context: Context,
    chatId: Long,
    messageId: Long,
    guid: Long,
    timestamp: Long,
    author: ByteArray,
    encryptedData: ByteArray,
    storage: SqlStorage,
    broadcast: Boolean = true,
    fromSync: Boolean = false
): Long {
    try {
        // Get chat info first to determine the correct mediator for this chat
        val chatInfo = storage.getGroupChat(chatId)
        if (chatInfo == null) {
            Log.e(TAG, "Chat $chatId not found in database")
            return 0
        }

        // Check if this is a system message from the mediator (not encrypted)
        // System messages come from the chat's mediator, not necessarily the default one
        if (author.contentEquals(chatInfo.mediatorPubkey)) {
            Log.d(TAG, "Processing system message $messageId for chat $chatId (not encrypted)")

            // Parse system message to handle member management
            val sysMsg = parseSystemMessage(encryptedData)
            // Handle message deletion - this is an invisible system message
            if (sysMsg is SystemMessage.MessageDeleted) {
                Log.i(TAG, "Deleting message with guid ${sysMsg.deletedGuid} from chat $chatId")
                storage.deleteGroupMessageByGuid(chatId, sysMsg.deletedGuid)
                // Don't save this system message to DB - it's invisible
                return 0
            }

            // System messages are not encrypted, save directly to database
            // Format: [event_code(1)][...event-specific data...]
            val localId = storage.addGroupMessage(
                chatId,
                messageId,
                guid,
                author,
                timestamp, // Use current time for system messages
                1000, // System messages are type 1000
                true, // Mark as system message
                encryptedData, // This is actually unencrypted system message data
                fromSync = fromSync
            )

            Log.i(TAG, "System message saved with local ID: $localId")
            return localId
        }

        // Decrypt message using shared key
        val decryptedData = try {
            GroupChatCrypto.decryptMessage(encryptedData, chatInfo.sharedKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message $messageId (${encryptedData.size} bytes)", e)

            // Save failed message to DB with error text
            val errorMessage = "<Can't decrypt the message>".toByteArray()
            val localId = storage.addGroupMessage(
                chatId,
                messageId,
                guid,
                author,
                System.currentTimeMillis(), // Use current time since we can't decrypt the real timestamp
                0, // Text message type
                false, // Not a system message
                errorMessage,
                fromSync = fromSync
            )

            return localId
        }

        // Deserialize message using the standard readMessage function
        val bais = ByteArrayInputStream(decryptedData)
        val dis = DataInputStream(bais)

        // Read header and message
        val header = readHeader(dis)
        val message = readMessage(dis)

        if (message == null) {
            Log.e(TAG, "Failed to deserialize message for chat $chatId")
            return 0
        }

        Log.d(TAG, "Got message for chat $chatId: guid = ${message.guid}, replyTo = ${message.replyTo}")

        var m = ByteArray(0)
        // Handle different message types
        if (message.type == 1 || message.type == 3) {
            // Media attachment: extract file and get text from JSON
            // Type 1 = image, Type 2 = general file
            val typeLabel = if (message.type == 1) "image" else "file"
            Log.i(TAG, "Processing $typeLabel attachment for chat $chatId")

            try {
                // Parse wire format: [jsonSize(u32)][JSON][fileBytes]
                var offset = 0

                // Read JSON length (first 4 bytes, big-endian)
                val jsonSize = ((message.data[offset].toInt() and 0xFF) shl 24) or
                        ((message.data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((message.data[offset + 2].toInt() and 0xFF) shl 8) or
                        (message.data[offset + 3].toInt() and 0xFF)
                offset += 4

                // Extract original JSON metadata
                val originalJson = JSONObject(String(message.data, offset, jsonSize, Charsets.UTF_8))
                offset += jsonSize

                // Extract file bytes
                val fileBytes = message.data.copyOfRange(offset, message.data.size)

                // Generate new filename and save file bytes
                val fileName = randomString(16)
                val ext = if (message.type == 1) {
                    // For images, detect extension from image bytes
                    getImageExtensionOrNull(fileBytes)
                } else {
                    // For files, use original extension from metadata
                    originalJson.optString("originalName", "file").substringAfterLast('.', "bin")
                }
                val fullName = "$fileName.$ext"

                saveFileForMessage(context, fullName, fileBytes)
                Log.i(TAG, "Saved $typeLabel attachment: $fullName (${fileBytes.size} bytes)")

                // Update JSON with new filename, keep all other fields (text, size, hash, originalName, mimeType)
                originalJson.put("name", fullName)
                m = originalJson.toString().toByteArray()

            } catch (e: Exception) {
                Log.e(TAG, "Error processing attachment: ${e.message}", e)
            }
        } else {
            // Plain text message
            m = message.data
        }

        //Log.i(TAG, "Decrypted message from ${author.take(8)}: $message")

        // Check if message already exists (dedup by GUID)
        if (storage.checkGroupMessageExists(chatId, message.guid)) {
            Log.i(TAG, "Message with guid=${message.guid} already exists, doing nothing")
            return 0
        }

        // Validate message data before saving to prevent database corruption
        val validatedData = validateMessageData(m, message.type, message.guid)

        // Save to database
        val localId = storage.addGroupMessage(
            chatId,
            messageId,
            message.guid,
            author,
            message.sendTime,
            message.type,
            false, // not a system message
            validatedData,
            message.replyTo,
            fromSync = fromSync
        )

        Log.i(TAG, "Message saved with local ID: $localId")
        return localId
    } catch (e: Exception) {
        Log.e(TAG, "Error processing message for chat $chatId", e)
        return 0
    }
}

/**
 * Validates message data before saving to database.
 * For attachment messages (type 1 and 3), verifies the data is valid JSON.
 * Corrupted data is replaced with an error placeholder.
 *
 * @param data The message data to validate
 * @param type The message type (1=image, 3=file, 0=text, etc.)
 * @param guid The message GUID (for logging)
 * @return Validated data, or error placeholder if validation fails
 */
private fun validateMessageData(data: ByteArray, type: Int, guid: Long): ByteArray {
    // Only validate JSON for attachment types
    if (type != 1 && type != 3) {
        return data
    }

    if (data.isEmpty()) {
        Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - data is empty")
        return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
    }

    try {
        val dataStr = String(data, Charsets.UTF_8)

        // Check if it starts with valid JSON
        if (dataStr.isEmpty() || dataStr[0] != '{') {
            Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - data doesn't start with '{'. First 4 bytes: ${data.take(4).joinToString(" ") { "0x%02x".format(it) }}")
            return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
        }

        // Verify it's parseable JSON
        JSONObject(dataStr)
        return data // Valid

    } catch (e: JSONException) {
        Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - JSON parsing failed: ${e.message}. Data length: ${data.size}")
        return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
    } catch (e: Exception) {
        Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - validation error: ${e.message}")
        return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
    }
}