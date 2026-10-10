package dev.monet.app.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming
import retrofit2.http.Path

internal interface MonetService {
    @GET("v1/health") suspend fun health(): Health
    @GET("v1/me") suspend fun me(): Me
    @POST("v1/annotate") suspend fun annotate(@Body request: AnnotateRequest): Annotation
    @POST("v1/annotate/lines") suspend fun annotateLines(@Body request: AnnotateLinesRequest): AnnotateLinesResponse
    @POST("v1/feedback") suspend fun sendFeedback(@Body request: FeedbackRequest)
    @GET("v1/documents") suspend fun listDocuments(): DocumentList
    @GET("v1/documents/{id}") suspend fun getDocument(@Path("id") id: String): Document
    @DELETE("v1/documents/{id}") suspend fun deleteDocument(@Path("id") id: String)
    @Multipart
    @POST("v1/documents")
    suspend fun uploadDocument(
        @Part file: MultipartBody.Part,
        @Part("target_lang") targetLang: RequestBody,
        @Part("source_lang") sourceLang: RequestBody,
        @Part("title") title: RequestBody?,
    ): Document

    @Streaming
    @GET("v1/documents/{id}/file")
    suspend fun getDocumentFile(@Path("id") id: String): ResponseBody

    @GET("v1/documents/{id}/pages/{index}")
    suspend fun getDocumentPage(@Path("id") id: String, @Path("index") index: Int): DocumentPage
}

class RemoteMonetApi(
    baseUrl: String,
    private val token: () -> String,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : MonetApi {

    private val service: MonetService = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            client.newBuilder()
                .addInterceptor(Interceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer ${token()}")
                            .header("Accept", "application/json, application/problem+json")
                            .build(),
                    )
                })
                .build(),
        )
        .addConverterFactory(MonetJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MonetService::class.java)

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val body = e.response()?.errorBody()?.string()
        val problem = body?.let { runCatching { MonetJson.decodeFromString(Problem.serializer(), it) }.getOrNull() }
            ?: Problem(title = e.message(), status = e.code())
        throw ApiException(problem, e)
    } catch (e: IOException) {
        throw ApiException(null, e)
    }

    override suspend fun health() = call { service.health() }
    override suspend fun me() = call { service.me() }
    override suspend fun annotate(request: AnnotateRequest) = call { service.annotate(request) }
    override suspend fun annotateLines(request: AnnotateLinesRequest) = call { service.annotateLines(request) }
    override suspend fun sendFeedback(request: FeedbackRequest) = call { service.sendFeedback(request) }
    override suspend fun listDocuments() = call { service.listDocuments() }
    override suspend fun getDocument(id: String) = call { service.getDocument(id) }
    override suspend fun deleteDocument(id: String) = call { service.deleteDocument(id) }
    override suspend fun getDocumentPage(id: String, index: Int) = call { service.getDocumentPage(id, index) }

    override suspend fun uploadDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        targetLang: Lang,
        sourceLang: SourceLang,
        title: String?,
    ) = call {
        val text = "text/plain".toMediaType()
        val file = MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
        service.uploadDocument(
            file = file,
            targetLang = targetLang.wire().toRequestBody(text),
            sourceLang = sourceLang.wire().toRequestBody(text),
            title = title?.toRequestBody(text),
        )
    }

    override suspend fun downloadDocument(id: String): ByteArray = call { service.getDocumentFile(id).use { it.bytes() } }
}

/** Wire value of an enum as declared by its @SerialName. */
private fun Lang.wire() = name.lowercase()

private fun SourceLang.wire() = name.lowercase()
