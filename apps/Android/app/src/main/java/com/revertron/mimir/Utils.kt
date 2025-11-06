package com.revertron.mimir

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.revertron.mimir.ui.Contact
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.DecoderException
import org.bouncycastle.util.encoders.Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

const val PICTURE_MAX_SIZE = 5 * 1024 * 1024
const val UPDATE_SERVER = "https://update.mimir-app.net"
const val IP_CACHE_DEFAULT_TTL = 900

/**
 * Checks for available updates.
 * Returns false if there was some network or server error, true otherwise.
 */
fun checkUpdates(context: Context, forced: Boolean = false): Boolean {
    val TAG = "checkUpdates"

    val url = URL("$UPDATE_SERVER/versions")

    try {
        TrafficStats.setThreadStatsTag(12345)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            Log.i(TAG, "Server returned ${conn.responseCode}")
            return false
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONArray(body)
        var newestVersion: String? = null
        var newestDesc: String? = null
        var newestApkPath: String? = null
        var newestCode = Version(0, 0, 0)
        val (lang, tag) = getSystemLocale()

        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)
            val ver = obj.getString("version")
            val code = Version.parse(ver)
            if (code > newestCode) {
                newestCode = code
                newestVersion = ver
                var desc = obj.optString("description_$tag")
                if (desc == null || desc.isEmpty())
                    desc = obj.optString("description_$lang")
                if (desc == null || desc.isEmpty())
                    desc = obj.optString("description")
                newestDesc = desc
                newestApkPath = obj.getString("apk_path")
            }
        }

        Log.i(TAG, "Newest version found: $newestVersion")

        val current = Version.parse(BuildConfig.VERSION_NAME)
        if (newestCode > current && newestVersion != null) {
            Log.i(TAG, "Found new version: $newestVersion")
            NotificationHelper.showUpdateAvailableNotification(context, newestCode, newestDesc!!, newestApkPath!!)
        } else if (forced) {
            Toast.makeText(context, R.string.already_last_version, Toast.LENGTH_LONG).show()
        }
        return true
    } catch (e: Exception) {
        val message: String = e.localizedMessage ?: e.message!!
        val text = context.getString(R.string.error_checking_updates, message)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        e.printStackTrace()
        return false
    }
}

fun getSystemLocale(): Pair<String, String> {
    val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Resources.getSystem().configuration.locales[0]
    } else {
        Resources.getSystem().configuration.locale   // deprecated but still works
    }

    return Pair(locale.language, locale.toLanguageTag())
}

fun getLogcatLastMinutes(minutes: Long): List<String> {
    val result = mutableListOf<String>()

    val logIgnoredLines = listOf("/studio.deploy", "OpenGLRenderer", "D/libEGL", "D/EGL_emulation", "s_glBindAttribLocation", "HostComposition ext")

    // Важно: "-v time" добавляет timestamp в формате "MM-dd HH:mm:ss.SSS"
    val process = Runtime.getRuntime().exec("logcat -d -v time")
    val reader = BufferedReader(InputStreamReader(process.inputStream))

    val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    val now = System.currentTimeMillis()
    val minutesAgo = now - minutes * 60 * 1000

    var line: String?
    read@ while (reader.readLine().also { line = it } != null) {
        if (line!!.length < 18) continue // защита от строк без даты

        for (ignored in logIgnoredLines) {
            if (line.contains(ignored)) continue@read
        }

        val tsString = line!!.substring(0, 18) // "MM-dd HH:mm:ss.SSS"
        try {
            val parsed = sdf.parse(tsString)
            val calendar = Calendar.getInstance()
            calendar.time = parsed!!
            // Подставляем текущий год, иначе даты будут "1970"
            calendar.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
            val timestamp = calendar.timeInMillis

            if (timestamp >= minutesAgo) {
                result.add(line!!)
            }
        } catch (_: Exception) {
            // строки без времени пропускаем
        }
    }
    reader.close()
    return result
}

/**
 * It is used in intent-filter in AndroidManifest, keep it in sync
 */
fun getMimirUriHost(): String {
    return "x.mimir-app.net"
}

fun isDefaultForDomain(context: Context, domain: String): Boolean {
    val pm = context.packageManager
    val pkg = context.packageName

    // Find the Activity that handles https://domain.tld
    val intent = Intent(Intent.ACTION_VIEW, "https://$domain".toUri())
    val ris = pm.queryIntentActivities(intent, 0)
    val myActivity = ris.firstOrNull { it.activityInfo.packageName == pkg } ?: return false
    return myActivity.activityInfo.packageName == pkg
}

fun updateQrCode(name: String, pubKey: String, imageView: ImageView) {
    val encoded = URLEncoder.encode(name, "UTF-8")
    val link = "mimir://mm/u/${pubKey}/$encoded"
    val qrCode = BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 600, 600)
    imageView.setImageBitmap(qrCode)
}

fun formatDuration(elapsed: Long): String {
    val totalSeconds = elapsed / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val text = if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
    return text
}

/**
 * Load avatar from a user-picked [Uri] and return a **square** bitmap whose
 * **smallest** side is exactly [maxSize] px.
 *
 * Large images are
 * 1. down-scaled so that the *smaller* dimension becomes [maxSize],
 * 2. centre-cropped to a square,
 * 3. returned as an immutable [Bitmap].
 *
 * **Call this from a background thread only** – the function blocks.
 *
 * @param uri      content:// or file:// Uri obtained from ActivityResultContracts.GetContent
 * @param maxSize  desired width/height of the final square avatar
 * @return         square avatar bitmap, or null if the Uri cannot be decoded
 */
fun loadSquareAvatar(context: Context, uri: Uri, maxSize: Int): Bitmap? {
    val cr = context.contentResolver

    /* ---------------------------------------------------------------------
       1. Decode only bounds → calculate inSampleSize for the *biggest*
          possible down-scale that still keeps the smaller side ≥ maxSize
       ------------------------------------------------------------------ */
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }

    val (w, h) = o.outWidth to o.outHeight
    if (w <= 0 || h <= 0) return null
    if (w == h && w < maxSize) {
        val bmp = cr.openInputStream(uri).use { ins ->
            BitmapFactory.decodeStream(ins)
        } ?: return null
        return bmp
    }

    val shortEdge = min(w, h)
    val scaleFactor = shortEdge / maxSize        // integer scale
    val sampleSize = scaleFactor.coerceAtLeast(1)

    /* ---------------------------------------------------------------------
       2. Decode *with* sampling
       ------------------------------------------------------------------ */
    val o2 = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = cr.openInputStream(uri).use { ins ->
        BitmapFactory.decodeStream(ins, null, o2)
    } ?: return null

    /* ---------------------------------------------------------------------
       3. Fine-scale so that the *smaller* side becomes exactly maxSize
       ------------------------------------------------------------------ */
    val scale = maxSize.toFloat() / min(bmp.width, bmp.height)
    val matrix = Matrix().apply { postScale(scale, scale) }

    val scaled = Bitmap.createBitmap(
        bmp, 0, 0, bmp.width, bmp.height, matrix, true
    )

    /* ---------------------------------------------------------------------
       4. Centre-crop to square
       ------------------------------------------------------------------ */
    val cropSize = min(scaled.width, scaled.height)
    val cropX = (scaled.width - cropSize) / 2
    val cropY = (scaled.height - cropSize) / 2

    val avatar = Bitmap.createBitmap(
        scaled, cropX, cropY, cropSize, cropSize
    )

    /* ---------------------------------------------------------------------
       5. Clean up if we created an intermediate bitmap
       ------------------------------------------------------------------ */
    if (scaled != bmp) scaled.recycle()
    return avatar
}

enum class ImageSize(val maxEdge: Int) {
    NoScale(20000),
    HighDPI(3840),
    FullHD(1920),
    Small(1280);

    companion object {
        fun fromInt(index: Int): ImageSize =
            entries.toTypedArray().getOrElse(index) { NoScale }
    }
}

/**
 * Resize the image behind this [Uri] so that its largest edge is <= [target].maxEdge
 * and compress it to JPEG with the given quality (0..100).
 * Returns the resulting byte array.
 *
 * @throws IllegalArgumentException if the Uri cannot be opened or is not an image.
 */
fun Uri.resizeAndCompress(context: Context, target: ImageSize, quality: Int = 90): ByteArray {

    val contentResolver = context.contentResolver

    // Read only bounds to calculate inSampleSize
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

    val inputStream = contentResolver.openInputStream(this)
    inputStream?.use {
        BitmapFactory.decodeStream(it, null, options)
    }

    val (origW, origH) = options.outWidth to options.outHeight
    if (origW <= 0 || origH <= 0)
        throw IllegalArgumentException("Not a valid image")

    val maxEdge = target.maxEdge
    val sample = calculateInSampleSize(origW, origH, maxEdge)

    // Decode sampled bitmap
    options.inSampleSize = sample
    options.inJustDecodeBounds = false

    val bitmap = contentResolver.openInputStream(this)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IllegalArgumentException("Cannot decode $this")

    // Rotate according to EXIF orientation
    val rotated = rotateBitmapAccordingToExif(bitmap, this, contentResolver)

    // Compress to JPEG
    return ByteArrayOutputStream().use { out ->
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        rotated.recycle()
        out.toByteArray()
    }
}

fun loadRoundedAvatar(context: Context, fileName: String?, size: Int = 32, corners: Int = 6): RoundedBitmapDrawable? {
    if (fileName == null || fileName.isEmpty()) return null
    if (fileName.isNotEmpty()) {
        val avatarsDir = File(context.filesDir, "avatars")
        val f = File(avatarsDir, fileName)
        val resources = context.resources
        try {
            f.inputStream().use {
                val iconSize = TypedValue.applyDimension(COMPLEX_UNIT_DIP, size.toFloat(), resources.displayMetrics).toInt()
                val raw = BitmapFactory.decodeFile(f.absolutePath)
                val scaled = raw.scale(iconSize, iconSize)

                val drawable = RoundedBitmapDrawableFactory.create(resources, scaled).apply {
                    cornerRadius = TypedValue.applyDimension(COMPLEX_UNIT_DIP, corners.toFloat(), resources.displayMetrics)
                }
                return drawable
            }
        } catch (e: IOException) {
            Log.e("Utils", e.toString())
            return null
        }
    }
    return null
}

fun loadRoundedBitmap(context: Context, buffer: ByteArray, size: Int = 32, corners: Int = 6): RoundedBitmapDrawable? {
    val resources = context.resources
    val iconSize = TypedValue.applyDimension(COMPLEX_UNIT_DIP, size.toFloat(), resources.displayMetrics).toInt()
    val raw = BitmapFactory.decodeByteArray(buffer, 0, buffer.size) ?: return null
    val scaled = raw.scale(iconSize, iconSize)

    val drawable = RoundedBitmapDrawableFactory.create(resources, scaled).apply {
        cornerRadius = TypedValue.applyDimension(COMPLEX_UNIT_DIP, corners.toFloat(), resources.displayMetrics)
    }
    return drawable
}

/* -------------------------------------------------------------- */
/* Helper functions                                               */
/* -------------------------------------------------------------- */

private fun calculateInSampleSize(origW: Int, origH: Int, maxEdge: Int): Int {
    var size = 1
    val larger = maxOf(origW, origH)
    if (larger > maxEdge) {
        val half = larger / 2
        while (half / size >= maxEdge) size *= 2
    }
    return size
}

private fun rotateBitmapAccordingToExif(bitmap: Bitmap, uri: Uri, contentResolver: ContentResolver): Bitmap {
    val exif = try {
        contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (_: Throwable) {
        return bitmap
    }

    val orientation = exif?.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    ) ?: ExifInterface.ORIENTATION_NORMAL

    val matrix = Matrix()
    val angle = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    return if (angle == 0f) bitmap else {
        matrix.postRotate(angle)
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it != bitmap) bitmap.recycle() }
    }
}

/**
 * Copies picture file to app directory, creates preview
 *
 * @return Hash of file, null if some error occurs
 */
fun prepareFileForMessage(context: Context, uri: Uri, imageSize: ImageSize, quality: Int): JSONObject? {
    val tag = "prepareFileForMessage"
    // TODO fix getting size
    var size = uri.length(context)

    val inputStream = if (size <= PICTURE_MAX_SIZE) {
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)
    } else {
        Log.i(tag, "File is too big, will try to resize")
        val resized = uri.resizeAndCompress(context, imageSize, quality)
        Log.i(tag, "Resized from $size to ${resized.size}")
        val newSize = resized.size.toLong()
        if (newSize > size || newSize > PICTURE_MAX_SIZE) return null
        size = newSize
        ByteArrayInputStream(resized)
    }
    val imagesDir = File(context.filesDir, "files")
    if (!imagesDir.exists()) {
        imagesDir.mkdirs()
    }
    val fileName = randomString(16)
    val ext = getMimeType(context, uri)
    val fullName = "$fileName.$ext"
    val outputFile = File(imagesDir, fullName)
    val outputStream = FileOutputStream(outputFile)
    inputStream.use { input ->
        outputStream.use { output ->
            val copied = (input?.copyTo(output) ?: {
                Log.e(tag, "File is not accessible")
                null
            }) as Long
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

/**
 * Returns the image file extension ("jpg", "png", "webp", "gif", "bmp")
 * if the provided ByteArray starts with a supported Android‐compatible
 * image signature, otherwise null.
 */
fun getImageExtensionOrNull(data: ByteArray): String? {
    if (data.isEmpty()) return null

    return when {
        data.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        data.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        data.startsWith(0x52, 0x49, 0x46, 0x46) &&
                data.size >= 12 &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> "webp"
        data.startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
        data.startsWith(0x42, 0x4D) -> "bmp"
        else -> null
    }
}

private fun ByteArray.startsWith(vararg magics: Int): Boolean {
    if (size < magics.size) return false
    return magics.indices.all { this[it] == magics[it].toByte() }
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

fun showQrCodeDialog(context: Context, name: String, public: String) {
    val view = LayoutInflater.from(context).inflate(R.layout.qr_code_dialog, null)
    val imageView = view.findViewById<AppCompatImageView>(R.id.image_view)
    updateQrCode(name, public, imageView)
    val wrapper = ContextThemeWrapper(context, R.style.MimirDialog)
    AlertDialog.Builder(wrapper)
        .setTitle(context.getString(R.string.my_qr_code))
        .setView(view)
        .setIcon(R.drawable.ic_qrcode)
        .setPositiveButton(context.getString(android.R.string.ok)) { dialog, _ ->
            dialog.cancel()
        }
        .show()
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
    return lightColors[abs(hashCode) % lightColors.size].toInt()
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

private val lightColors = arrayOf(
    0xFF6F8F8F, // light slate gray
    0xFF8FB2D4, // light steel blue
    0xFF95AB5F, // light olive green
    0xFFFDF7BB, // light khaki
    0xFFCFFCCF, // light sea green
    0xFFA6EDE6, // light aquamarine
    0xFF5151FF, // light medium blue
    0xFFD3A0FF, // light medium purple
    0xFF7CD7A1, // light sea green
    0xFFAB98FF, // light slate blue
    0xFF52FFBA, // light spring green
    0xFF8AF1EC, // light turquoise
    0xFFABCE63, // light olive drab
    0xFFD8FFD8, // light pale green
    0xFFCFFFFF, // light pale turquoise
    0xFFF8C64B, // light goldenrod
    0xFF4E9E4E, // light dark green
    0xFFE9E9E9, // light grey
    0xFFFFBD4D, // light orange
    0xFFBD6CFF, // light orchid
    0xFFFFD6BB, // light salmon
    0xFF52EFF1, // light turquoise
    0xFFD44CFF, // light violet
    0xFF52DFFF, // light sky blue
    0xFFA9A9A9, // light dim gray
    0xFF62BB62, // light forest green
    0xFFFFE755, // light gold
    0xFFCDFF6F, // light green yellow
    0xFFCDE8FF, // lighter blue
    0xFFB0FFB0  // lighter green
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
    Offline, Online;
}

fun Uri.length(context: Context): Long {

    val TAG = "Uri.length"

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
            val contentResolver = context.contentResolver
            val stream = contentResolver.openInputStream(this)
            val size = (stream?.available() ?: 0).toLong()
            stream?.close()
            size
        }
        ContentResolver.SCHEME_ANDROID_RESOURCE -> {
            fromAssetFileDescriptor()
        }
        else -> {
            -1
        }
    }
}

fun haveNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // is there still a network that is alive
    val activeNetwork = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(activeNetwork)
    val aliveNetwork = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    return aliveNetwork
}

fun isGoogleOnline(): Boolean {
    return try {
        val url = URL("https://www.google.com/generate_204")   // 0-byte body
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            instanceFollowRedirects = false
            connectTimeout = 3_000
            readTimeout    = 3_000
            requestMethod  = "HEAD"
        }
        conn.responseCode == 204          // Google returns 204 No-Content
    } catch (ignored: IOException) {
        false
    }
}