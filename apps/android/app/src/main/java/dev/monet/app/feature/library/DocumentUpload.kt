package dev.monet.app.feature.library

import android.content.ContentResolver
import android.net.Uri
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

fun extensionFor(kind: DocumentKind): String = when (kind) {
    DocumentKind.PDF -> "pdf"
    DocumentKind.DOCX -> "docx"
    DocumentKind.TXT -> "txt"
}

/** File name to re-upload a document under: the title, with the kind's extension if it lacks one. */
fun retryFileName(doc: Document): String {
    val ext = extensionFor(doc.kind)
    return if (doc.title.endsWith(".$ext", ignoreCase = true)) doc.title else "${doc.title}.$ext"
}

/** Only failed documents can be retried. */
fun canRetry(doc: Document): Boolean = doc.status == DocumentStatus.FAILED

private fun apiMessage(e: ApiException, fallback: String) =
    if (e.isOffline) "Can't reach the server." else e.problem?.detail ?: e.problem?.title ?: fallback

/** Uploads a file the user picked or shared. Returns null on success, otherwise a short message. */
suspend fun uploadFromUri(api: MonetApi, settings: SettingsRepository, resolver: ContentResolver, uri: Uri): String? =
    try {
        val picked = withContext(Dispatchers.IO) { readPickedFile(resolver, uri) }
        val kind = picked?.let { kindFromFile(it.name, it.mime) }
        when {
            picked == null -> "Could not open that file."
            kind == null -> "Choose a PDF, DOCX or TXT file."
            else -> {
                val prefs = settings.settings.first()
                api.uploadDocument(
                    fileName = picked.name,
                    mimeType = mimeFor(kind),
                    bytes = picked.bytes,
                    targetLang = prefs.targetLang,
                    sourceLang = prefs.sourceLang,
                )
                null
            }
        }
    } catch (e: FileTooLargeException) {
        e.message ?: "The file is too large."
    } catch (e: ApiException) {
        apiMessage(e, "Upload failed.")
    } catch (e: SecurityException) {
        "Could not open that file."
    }

/**
 * The contract has no reprocess endpoint, so a retry downloads the original, uploads it again with
 * the same title and languages, then removes the failed one.
 */
suspend fun retryDocument(api: MonetApi, doc: Document): String? =
    try {
        val bytes = api.downloadDocument(doc.id)
        api.uploadDocument(
            fileName = retryFileName(doc),
            mimeType = mimeFor(doc.kind),
            bytes = bytes,
            targetLang = doc.targetLang,
            sourceLang = doc.sourceLang,
            title = doc.title,
        )
        runCatching { api.deleteDocument(doc.id) }
        null
    } catch (e: ApiException) {
        apiMessage(e, "Could not retry the document.")
    }
