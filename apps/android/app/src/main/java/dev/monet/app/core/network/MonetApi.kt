package dev.monet.app.core.network

import kotlinx.serialization.json.Json

/** Shared JSON config: tolerant of additive contract changes, omits null fields on the wire. */
val MonetJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** An API failure: either a decoded problem+json or a transport error. */
class ApiException(val problem: Problem?, cause: Throwable? = null) :
    Exception(problem?.let { "${it.status} ${it.title}" } ?: cause?.message ?: "Network error", cause) {
    val status: Int? get() = problem?.status
    val isOffline: Boolean get() = problem == null
}

/** The app talks only to this interface; [RemoteMonetApi] is real, [FakeMonetApi] is for UI work and tests. */
interface MonetApi {
    suspend fun health(): Health
    suspend fun me(): Me
    suspend fun annotate(request: AnnotateRequest): Annotation
    suspend fun annotateLines(request: AnnotateLinesRequest): AnnotateLinesResponse
    suspend fun sendFeedback(request: FeedbackRequest)
    suspend fun listDocuments(): DocumentList
    suspend fun getDocument(id: String): Document
    suspend fun deleteDocument(id: String)
    suspend fun getDocumentPage(id: String, index: Int): DocumentPage
    suspend fun uploadDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        targetLang: Lang,
        sourceLang: SourceLang = SourceLang.AUTO,
        title: String? = null,
    ): Document

    /** The original uploaded file (a PDF is rendered on the device). */
    suspend fun downloadDocument(id: String): ByteArray
}
