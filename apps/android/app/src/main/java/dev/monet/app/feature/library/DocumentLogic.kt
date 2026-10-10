package dev.monet.app.feature.library

import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentStatus
import kotlin.math.roundToInt

/** Server limit from the contract (PDF, DOCX or TXT, max 25 MB). */
const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

/** Decides the document kind from the file name first (pickers often report generic MIME types). */
fun kindFromFile(name: String?, mime: String?): DocumentKind? {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        ext == "pdf" -> DocumentKind.PDF
        ext == "docx" -> DocumentKind.DOCX
        ext == "txt" || ext == "text" -> DocumentKind.TXT
        mime == "application/pdf" -> DocumentKind.PDF
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentKind.DOCX
        mime == "text/plain" -> DocumentKind.TXT
        else -> null
    }
}

fun mimeFor(kind: DocumentKind): String = when (kind) {
    DocumentKind.PDF -> "application/pdf"
    DocumentKind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    DocumentKind.TXT -> "text/plain"
}

/** The status line shown under a library row. */
fun statusLabel(doc: Document): String = when (doc.status) {
    DocumentStatus.QUEUED -> "Queued"
    DocumentStatus.PROCESSING -> "Processing · ${(doc.progress.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
    DocumentStatus.READY -> pagesLabel(doc.pageCount)
    DocumentStatus.FAILED -> doc.error?.takeIf { it.isNotBlank() }?.let { "Failed · $it" } ?: "Failed"
}

fun pagesLabel(pageCount: Int?): String = when (pageCount) {
    null -> "Ready"
    1 -> "1 page"
    else -> "$pageCount pages"
}

/** True while the server is still working, so the list should keep refreshing. */
fun hasActiveDocuments(docs: List<Document>): Boolean =
    docs.any { it.status == DocumentStatus.QUEUED || it.status == DocumentStatus.PROCESSING }

/** Fraction to draw in the row's progress rule, or null when there is nothing in progress. */
fun progressFraction(doc: Document): Float? =
    if (doc.status == DocumentStatus.PROCESSING) doc.progress.coerceIn(0.0, 1.0).toFloat() else null

/** Polling cadence: quick while something is processing, relaxed otherwise. */
fun pollDelayMillis(docs: List<Document>): Long = if (hasActiveDocuments(docs)) 3_000L else 15_000L
