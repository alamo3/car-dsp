package dev.alamo3.cabineq.jam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.security.MessageDigest

/**
 * Renders the jam session URL as a QR image.
 *
 * The car cannot draw a QR itself (templates only take images), so the host fetches ours through
 * [dev.alamo3.cabineq.ArtworkProvider] at whatever resolution it wants — same mechanism as cover
 * art, which avoids binder bitmap size limits.
 */
object QrCodes {

    /** Error correction M survives a windshield's glare and a hand-held phone at an angle. */
    private val ECC = ErrorCorrectionLevel.M
    private const val MAX_DISK_BYTES = 4L * 1024 * 1024

    fun bitmap(text: String, sizePx: Int): Bitmap? = runCatching {
        val size = sizePx.coerceIn(128, 1024)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ECC,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Cached PNG for [text] at [sizePx], generating it on first use. */
    fun fileFor(context: Context, text: String, sizePx: Int): File? {
        val size = sizePx.coerceIn(128, 1024)
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, sha1("$size:$text") + ".png")
        if (file.exists() && file.length() > 0L) return file
        val bitmap = bitmap(text, size) ?: return null
        return try {
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                null
            } else {
                trim(dir)
                file.takeIf { it.length() > 0L }
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.drop(1)) {
            if (total < MAX_DISK_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
