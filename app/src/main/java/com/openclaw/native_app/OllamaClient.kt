package com.openclaw.native_app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class OllamaModel(
    @SerializedName("name")    val name: String,
    @SerializedName("size")    val size: Long = 0,
    @SerializedName("digest")  val digest: String = "",
    @SerializedName("details") val details: Map<String, Any?> = emptyMap()
)

data class OllamaResponse(
    val model:    String,
    val response: String,
    val done:     Boolean,
    @SerializedName("eval_count")   val evalCount:   Int = 0,
    @SerializedName("prompt_eval_count") val promptEvalCount: Int = 0
)

/**
 * OllamaClient
 *
 * Communicates directly with a local Ollama instance (default: http://localhost:11434).
 * Used to list available models and test connectivity for the Settings screen.
 * Actual inference routing goes through the OpenClaw gateway config.
 */
@Singleton
class OllamaClient @Inject constructor() {
    companion object {
        private const val TAG = "OllamaClient"
    }

    private val gson   = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    var baseUrl: String = "http://localhost:11434"

    /** Returns true if Ollama is reachable. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/tags").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** List all locally installed models. */
    suspend fun listModels(): List<OllamaModel> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/tags").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val arr  = json.optJSONArray("models") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    OllamaModel(
                        name   = m.optString("name"),
                        size   = m.optLong("size"),
                        digest = m.optString("digest")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listModels failed: ${e.message}")
            emptyList()
        }
    }

    /** Pull a model by name. Calls the progress callback with 0..100 percent. */
    suspend fun pullModel(modelName: String, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", modelName).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/api/pull").post(body).build()
        try {
            client.newCall(req).execute().use { response ->
                val source = response.body?.source() ?: return@withContext
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    try {
                        val json     = JSONObject(line)
                        val total    = json.optLong("total", 0)
                        val completed = json.optLong("completed", 0)
                        if (total > 0) {
                            onProgress(((completed.toDouble() / total) * 100).toInt())
                        }
                        if (json.optString("status") == "success") onProgress(100)
                    } catch (e: Exception) { /* skip malformed lines */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullModel failed: ${e.message}")
        }
    }

    /** Quick chat — used for testing a model is functional. */
    suspend fun chat(model: String, prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("stream", false)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url("$baseUrl/api/generate").post(body).build()
        try {
            client.newCall(req).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optString("response", "No response")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Write an Ollama provider block into openclaw's config so the gateway
     * routes requests to the local model.
     */
    fun buildOpenClawConfig(modelName: String, baseUrl: String = this.baseUrl): String {
        return """
            providers:
              - name: ollama
                type: ollama
                endpoint: $baseUrl
                default_model: $modelName
                models:
                  - $modelName
        """.trimIndent()
    }
}
