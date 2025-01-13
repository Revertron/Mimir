package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.revertron.mimir.ui.Contact
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.DecoderException
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import kotlin.math.abs

const val PICTURE_MAX_SIZE = 5 * 1024 * 1024

fun createServiceNotification(context: Context, state: State): Notification {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    val channelId = "Foreground Service"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = context.getString(R.string.channel_name_service)
        val descriptionText = context.getString(R.string.channel_description_service)
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val text = when (state) {
        State.Disabled -> context.getText(R.string.state_disabled)
        State.Enabled -> context.getText(R.string.state_enabled)
    }

    return NotificationCompat.Builder(context, channelId)
        .setShowWhen(false)
        .setContentTitle(text)
        .setSmallIcon(R.drawable.ic_mannaz_notification)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

/**
 * It is used in intent-filter in AndroidManifest, keep it in sync
 */
fun getMimirUriHost(): String {
    return "mm.yggdrasil.link"
}

fun updateQrCode(name: String, pubKey: String, imageView: ImageView) {
    val encoded = URLEncoder.encode(name, "UTF-8")
    val link = "mimir://mm/u/${pubKey}/$encoded"
    val qrCode = BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 600, 600)
    imageView.setImageBitmap(qrCode)
}

/**
 * Copies picture file to app directory, creates preview
 *
 * @return Hash of file, null if some error occurs
 */
fun prepareFileForMessage(context: Context, uri: Uri, isVoiceMessage: Boolean = false): JSONObject? {
    val tag = "prepareFileForMessage"
    val size = uri.length(context)
    if (size > PICTURE_MAX_SIZE) {
        Log.e(tag, "File is too big")
        return null
    }
    val contentResolver = context.contentResolver
    val inputStream = contentResolver.openInputStream(uri)
    val imagesDir = File(context.filesDir, "files")
    if (!imagesDir.exists()) {
        imagesDir.mkdirs()
    }
    val fileName = randomString(16)
    val ext = if (isVoiceMessage) "m4a" else getMimeType(context, uri)
    val fullName = "$fileName.$ext"
    val outputFile = File(imagesDir, fullName)
    val outputStream = FileOutputStream(outputFile)
    inputStream.use { input ->
        outputStream.use { output ->
            val copied = input?.copyTo(output) ?: {
                Log.e(tag, "File is not accessible")
                null
            }
            if (copied != size) {
                Log.e(tag, "Error copying file to app storage!")
                return null
            }
        }
    }
    val hash = getFileHash(outputFile)
    val json = JSONObject()
    json.put("name", fullName)
    json.put("size", size)
    json.put("hash", Hex.toHexString(hash))
    return json
}

fun getFileHash(file: File): ByteArray {
    val messageDigest = MessageDigest.getInstance("SHA-256")
    val inputStream = FileInputStream(file)
    val buffer = ByteArray(8192)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        messageDigest.update(buffer, 0, bytesRead)
    }
    inputStream.close()
    return messageDigest.digest()
}

fun getMimeType(context: Context, uri: Uri): String? {
    //Check uri format to avoid null
    val extension: String? = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        //If scheme is a content
        val mime = MimeTypeMap.getSingleton()
        mime.getExtensionFromMimeType(context.contentResolver.getType(uri))
    } else {
        //If scheme is a File
        //This will replace white spaces with %20 and also other special characters. This will avoid returning null values on file name with spaces and special characters.
        MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(File(uri.path)).toString())
    }
    return extension
}

fun getImagePreview(context: Context, fileName: String, maxSize: Int, quality: Int): Bitmap? {
    val imagesDir = File(context.filesDir, "files")
    val cacheDir = File(context.cacheDir, "files")
    val previewFile = File(cacheDir, fileName)
    val created = if (!previewFile.exists()) {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val originalFile = File(imagesDir, fileName).absolutePath
        createImagePreview(originalFile, previewFile.absolutePath, maxSize, quality)
    } else {
        false
    }
    if (created) {
        return BitmapFactory.decodeFile(previewFile.absolutePath)
    } else {
        val originalFile = File(imagesDir, fileName).absolutePath
        return BitmapFactory.decodeFile(originalFile)
    }
}

fun createImagePreview(filePath: String, previewPath: String, maxSize: Int, quality: Int): Boolean {
    val file = File(filePath)
    if (file.length() < 500 * 1024) {
        return false
    }
    val bitmap = BitmapFactory.decodeFile(filePath) ?: return false
    val inWidth: Int = bitmap.width
    val inHeight: Int = bitmap.height
    if (inWidth <= maxSize && inHeight <= maxSize) {
        bitmap.recycle()
        return false
    }
    val outWidth: Int
    val outHeight: Int
    if (inWidth > inHeight) {
        outWidth = maxSize
        outHeight = inHeight * maxSize / inWidth
    } else {
        outHeight = maxSize
        outWidth = inWidth * maxSize / inHeight
    }
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true)
    bitmap.recycle()
    val previewFile = File(previewPath)
    val outputStream = FileOutputStream(previewFile)
    outputStream.use {
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
    }
    scaledBitmap.recycle()
    return true
}

fun deleteFileAndPreview(context: Context, fileName: String) {
    val imagesDir = File(context.filesDir, "files")
    val cacheDir = File(context.cacheDir, "files")
    File(imagesDir, fileName).delete()
    File(cacheDir, fileName).delete()
}

fun getFileContents(filePath: String): ByteArray {
    val file = File(filePath)
    val inputStream = FileInputStream(file)
    val bytes = inputStream.readBytes()
    inputStream.close()
    return bytes
}

fun saveFileForMessage(context: Context, fileName: String, data: ByteArray) {
    val imagesDir = File(context.filesDir, "files")
    if (!imagesDir.exists()) {
        imagesDir.mkdirs()
    }
    val outputFile = File(imagesDir, fileName)
    val outputStream = FileOutputStream(outputFile)
    outputStream.use {
        it.write(data)
        it.flush()
    }
    val cacheDir = File(context.cacheDir, "files")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    val previewFile = File(cacheDir, fileName)
    val originalFile = File(imagesDir, fileName).absolutePath
    //TODO refactor this methods to make it more clear
    createImagePreview(originalFile, previewFile.absolutePath, 512, 80)
}

fun getYggdrasilAddress(): InetAddress? {
    val interfaces: List<NetworkInterface> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    } catch (e: java.lang.Exception) {
        return null
    }

    for (i in interfaces) {
        if (!i.isUp || i.isLoopback) continue

        for (addr in i.inetAddresses) {
            val bytes = addr.address
            if (bytes.size > 4 && (bytes[0] == 0x2.toByte() || bytes[0] == 0x3.toByte())) {
                Log.d("Utils", "Found Ygg IP $addr")
                return addr
            }
        }
    }
    return null
}

fun isSubnetYggdrasilAddress(address: InetAddress): Boolean {
    return address.address[0] == 0x3.toByte()
}

fun isAddressFromSubnet(address: InetAddress, subnet: InetAddress): Boolean {
    // Логирование для отладки
    Log.d("NetworkUtils", "Checking address: ${address.hostAddress}")
    Log.d("NetworkUtils", "Against subnet: ${subnet.hostAddress}")

    // Проверка диапазонов 200:: и 300::
    val addressStr = address.hostAddress ?: return false
    val subnetStr = subnet.hostAddress ?: return false

    // Проверяем, что оба адреса начинаются с 200:: или 300::
    val validPrefixes = listOf("200:", "300:")
    val addressPrefix = addressStr.substring(0, 4)
    val subnetPrefix = subnetStr.substring(0, 4)

    if (!validPrefixes.contains(addressPrefix) || !validPrefixes.contains(subnetPrefix)) {
        Log.w("NetworkUtils", "Invalid prefix - address: $addressPrefix, subnet: $subnetPrefix")
        return false
    }

    // Проверяем, что адреса находятся в одной подсети
    for (b in 1..7) {
        if (address.address[b] != subnet.address[b]) {
            return false
        }
    }
    return true
}

fun randomString(length: Int): String {
    val characters = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { characters.random() }
        .joinToString("")
}

fun randomBytes(length: Int): ByteArray {
    val buf = ByteArray(length)
    val random = Random(System.currentTimeMillis())
    random.nextBytes(buf)
    return buf
}

fun validPublicKey(text: String): Boolean {
    try {
        val key = Ed25519PublicKeyParameters(Hex.decode(text))
        Log.d(MainActivity.TAG, "Got valid public key $key")
        return true
    } catch (e: IllegalArgumentException) {
        Log.d(MainActivity.TAG, "Wrong public key $text")
        return false
    } catch (e: DecoderException) {
        Log.d(MainActivity.TAG, "Wrong public key $text")
        return false
    }
}

/**
 * Gets current time in seconds in UTC timezone
 */
fun getUtcTime(): Long {
    val calendar = Calendar.getInstance()
    return calendar.timeInMillis / 1000
}

/**
 * Gets current time in milliseconds in UTC timezone
 */
fun getUtcTimeMs(): Long {
    val calendar = Calendar.getInstance()
    return calendar.timeInMillis
}

fun isColorDark(color: Int): Boolean {
    val r = ((color shr 16) and 0xff) / 255.0
    val g = ((color shr 8) and 0xff) / 255.0
    val b = (color and 0xff) / 255.0
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return luminance < 0.5
}

fun getAvatarColor(pubkey: ByteArray): Int {
    val hashCode = pubkey.contentHashCode()
    return darkColors[abs(hashCode) % darkColors.size].toInt()
}

private val darkColors = arrayOf(
    0xFF2F4F4F, // Dark slate gray
    0xFF4682B4, // Steel blue
    0xFF556B2F, // Dark olive green
    0xFFBDB76B, // Dark khaki
    0xFF8FBC8F, // Dark sea green
    0xFF66CDAA, // Medium aquamarine
    0xFF0000CD, // Medium blue
    0xFF9370DB, // Medium purple
    0xFF3CB371, // Medium sea green
    0xFF7B68EE, // Medium slate blue
    0xFF00FA9A, // Medium spring green
    0xFF48D1CC, // Medium turquoise
    0xFF6B8E23, // Olive drab
    0xFF98FB98, // Pale green
    0xFFAFEEEE, // Pale turquoise
    0xFFB8860B, // Dark goldenrod
    0xFF006400, // Dark green
    0xFFA9A9A9, // Dark grey
    0xFFFF8C00, // Dark orange
    0xFF9932CC, // Dark orchid
    0xFFE9967A, // Dark salmon
    0xFF00CED1, // Dark turquoise
    0xFF9400D3, // Dark violet
    0xFF00BFFF, // Deep sky blue
    0xFF696969, // Dim gray
    0xFF228B22, // Forest green
    0xFFFFD700, // Gold
    0xFFADFF2F, // Green yellow
    0xFFADD8E6, // Light blue
    0xFF90EE90  // Light green
)

fun getInitials(contact: Contact): String {
    val name = contact.name.trim()
    if (name.isEmpty() || name.length < 2) {
        return Hex.toHexString(contact.pubkey, 0, 1)
    }

    if (name.length == 2) {
        return name
    }

    if (name.contains(" ")) {
        val pos = name.indexOf(" ") + 1
        return name.substring(0, 1) + name.substring(pos, pos + 1)
    }

    return name.substring(0, 2)
}

enum class State {
    Disabled, Enabled;
}

fun Uri.length(context: Context): Long {

    val TAG = "Uri.length"

    val fromContentProviderColumn = fun(): Long {
        // Try to get content length from the content provider column OpenableColumns.SIZE
        // which is recommended to implement by all the content providers
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                this,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            ) ?: throw Exception("Content provider returned null or crashed")
            val sizeColumnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumnIndex != -1 && cursor.count > 0) {
                cursor.moveToFirst()
                cursor.getLong(sizeColumnIndex)
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: e.javaClass.simpleName)
            -1
        } finally {
            cursor?.close()
        }
    }

    val fromFileDescriptor = fun(): Long {
        // Try to get content length from content scheme uri or file scheme uri
        var fileDescriptor: ParcelFileDescriptor? = null
        return try {
            fileDescriptor = context.contentResolver.openFileDescriptor(this, "r")
                ?: throw Exception("Content provider recently crashed")
            fileDescriptor.statSize
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: e.javaClass.simpleName)
            -1
        } finally {
            fileDescriptor?.close()
        }
    }

    val fromAssetFileDescriptor = fun(): Long {
        // Try to get content length from content scheme uri, file scheme uri or android resource scheme uri
        var assetFileDescriptor: AssetFileDescriptor? = null
        return try {
            assetFileDescriptor = context.contentResolver.openAssetFileDescriptor(this, "r")
                ?: throw Exception("Content provider recently crashed")
            assetFileDescriptor.length
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: e.javaClass.simpleName)
            -1
        } finally {
            assetFileDescriptor?.close()
        }
    }

    return when (scheme) {
        ContentResolver.SCHEME_FILE -> {
            fromFileDescriptor()
        }
        ContentResolver.SCHEME_CONTENT -> {
            val length = fromContentProviderColumn()
            if (length >= 0) {
                length
            } else {
                fromFileDescriptor()
            }
        }
        ContentResolver.SCHEME_ANDROID_RESOURCE -> {
            fromAssetFileDescriptor()
        }
        else -> {
            -1
        }
    }
}
