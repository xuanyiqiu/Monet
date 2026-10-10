package dev.monet.app.feature.home

import android.content.Intent

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
