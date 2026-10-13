package cl.turingforge.odooclient.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/** Guarda bytes (descargas blob: de Odoo) en la carpeta pública Descargas. */
object DownloadSaver {

    fun save(context: Context, bytes: ByteArray, fileName: String, mimeType: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(context, bytes, fileName, mimeType)
        else saveLegacy(context, bytes, fileName, mimeType)

    // Android 10+: MediaStore.Downloads, sin permisos. El sistema renombra duplicados solo.
    private fun saveWithMediaStore(context: Context, bytes: ByteArray, fileName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // Android 8–9: archivo directo en /Download (requiere WRITE_EXTERNAL_STORAGE).
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bytes: ByteArray, fileName: String, mimeType: String): Uri {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val file = uniqueFile(dir, fileName).apply { writeBytes(bytes) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var i = 1
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return file
    }
}

/** Odoo envía `filename*=UTF-8''Factura%20INV-001.pdf`, que URLUtil.guessFileName no entiende. */
object FileNames {

    fun fromDownload(url: String, contentDisposition: String?, mimeType: String?): String {
        val name = contentDisposition?.let(::parse) ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        return withExtension(sanitize(name), mimeType)
    }

    fun withExtension(name: String, mimeType: String?): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
        return "$name.$ext"
    }

    fun sanitize(name: String?): String =
        (name ?: "").replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").trim().take(120).ifBlank { "descarga" }

    private fun parse(header: String): String? {
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), charset) }.getOrNull()?.let { return it }
        }
        return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()
    }
}
