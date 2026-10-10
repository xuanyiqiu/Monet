package dev.monet.app.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

class PickedFile(val name: String, val mime: String?, val bytes: ByteArray)

class FileTooLargeException : Exception("The file is larger than 25 MB.")

/** Reads a document chosen with the system picker, refusing files over the server limit before loading them. */
fun readPickedFile(resolver: ContentResolver, uri: Uri): PickedFile? {
    var name: String? = null
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            size = if (c.isNull(1)) -1L else c.getLong(1)
        }
    }
    if (size > MAX_UPLOAD_BYTES) throw FileTooLargeException()
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_UPLOAD_BYTES) throw FileTooLargeException()
    return PickedFile(name ?: "document", resolver.getType(uri), bytes)
}
