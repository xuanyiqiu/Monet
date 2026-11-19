package dev.monet.app.core.settings

fun formatVersion(name: String, code: Int): String {
    val effectiveName = name.ifBlank { "dev" }
    return "$effectiveName ($code)"
}
