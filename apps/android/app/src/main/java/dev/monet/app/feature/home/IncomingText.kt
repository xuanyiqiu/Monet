package dev.monet.app.feature.home

import android.content.Intent
import dev.monet.app.feature.library.kindFromFile

/** Contract limit for `/v1/annotate` text. */
const val MAX_TEXT_LENGTH = 2000

/** Normalizes text coming from the user or from other apps; null when there is nothing to explain. */
fun normalizeIncoming(raw: CharSequence?): String? =
    raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_LENGTH)

/**
 * Extracts text from the intents Monet accepts. Pure on its inputs so it can be unit tested.
 * [debugText] is the `--es text` extra used for adb testing.
 */
fun extractIncomingText(
    action: String?,
    type: String?,
    sendText: CharSequence?,
    processText: CharSequence?,
    debugText: CharSequence? = null,
): String? = when (action) {
    Intent.ACTION_SEND -> if (type == null || type.startsWith("text/")) normalizeIncoming(sendText) else null
    Intent.ACTION_PROCESS_TEXT -> normalizeIncoming(processText)
    else -> normalizeIncoming(debugText)
}

fun Intent.incomingText(): String? = extractIncomingText(
    action = action,
    type = type,
    sendText = getCharSequenceExtra(Intent.EXTRA_TEXT),
    processText = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
    debugText = getStringExtra("text"),
)

/** What an incoming intent asks Monet to do. */
sealed interface IncomingShare {
    data object Nothing : IncomingShare
    data class Text(val text: String) : IncomingShare
    /** A shared file to upload; its kind is checked again from the file name once it is read. */
    data class File(val mimeType: String?) : IncomingShare
    data object Unsupported : IncomingShare
}

/**
 * Text wins whenever there is text. A file is only assumed for a SEND that carries a stream and no
 * EXTRA_TEXT, so ordinary text shares behave exactly as before.
 */
fun classifyIncoming(
    action: String?,
    type: String?,
    sendText: CharSequence?,
    hasStream: Boolean,
    processText: CharSequence?,
    debugText: CharSequence? = null,
): IncomingShare {
    val text = extractIncomingText(action, type, sendText, processText, debugText)
    if (text != null) return IncomingShare.Text(text)
    if (action == Intent.ACTION_SEND && hasStream && normalizeIncoming(sendText) == null) {
        val supported = type == null || type == "application/octet-stream" || kindFromFile(null, type) != null
        return if (supported) IncomingShare.File(type) else IncomingShare.Unsupported
    }
    return IncomingShare.Nothing
}

@Suppress("DEPRECATION")
fun Intent.streamUri(): android.net.Uri? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
    else getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri

fun Intent.incomingShare(): IncomingShare = classifyIncoming(
    action = action,
    type = type,
    sendText = getCharSequenceExtra(Intent.EXTRA_TEXT),
    hasStream = streamUri() != null,
    processText = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
    debugText = getStringExtra("text"),
)
