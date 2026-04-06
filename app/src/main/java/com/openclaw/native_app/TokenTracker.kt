package com.openclaw.native_app

import android.util.Log
import com.openclaw.native_app.data.db.TokenDao
import com.openclaw.native_app.data.db.TokenEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TokenTracker
 *
 * Intercepts outbound API calls from the OpenClaw gateway by acting as a
 * transparent HTTP proxy on a loopback port, then records token usage in Room.
 *
 * Pricing (as of claude-sonnet-4-20250514):
 *   Input:  $3.00 / 1M tokens
 *   Output: $15.00 / 1M tokens
 *
 * For Ollama (local), cost = $0.
 */
@Singleton
class TokenTracker @Inject constructor(
    private val tokenDao: TokenDao
) {
    companion object {
        private const val TAG = "TokenTracker"

        // Pricing per 1M tokens (USD)
        private val PRICING = mapOf(
            // Anthropic
            "claude-sonnet-4-20250514"     to Pair(3.00, 15.00),
            "claude-opus-4-5"              to Pair(15.00, 75.00),
            "claude-haiku-4-5"             to Pair(0.80, 4.00),
            // OpenAI
            "gpt-4o"                       to Pair(2.50, 10.00),
            "gpt-4o-mini"                  to Pair(0.15, 0.60),
            // Gemini
            "gemini-1.5-pro"               to Pair(1.25, 5.00),
            "gemini-1.5-flash"             to Pair(0.075, 0.30),
            // Ollama — free
            "ollama"                       to Pair(0.0, 0.0)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    // Live stats flows
    val totalInputTokens:  Flow<Int>    = tokenDao.totalInputTokens().map    { it ?: 0 }
    val totalOutputTokens: Flow<Int>    = tokenDao.totalOutputTokens().map   { it ?: 0 }
    val totalCostUsd:      Flow<Double> = tokenDao.totalCostUsd().map        { it ?: 0.0 }

    fun sessionInputTokens():  Flow<Int>    = tokenDao.sessionInputTokens(currentSessionId).map { it ?: 0 }
    fun sessionOutputTokens(): Flow<Int>    = tokenDao.sessionOutputTokens(currentSessionId).map { it ?: 0 }
    fun sessionCostUsd():      Flow<Double> = tokenDao.sessionCostUsd(currentSessionId).map { it ?: 0.0 }

    fun observeRecent(): Flow<List<TokenEntity>> = tokenDao.observeRecent()

    /** Called by GatewayService when gateway starts. */
    fun startIntercepting() {
        currentSessionId = UUID.randomUUID().toString()
        Log.i(TAG, "Token tracking session started: $currentSessionId")
        // The actual interception is done via OkHttp EventListener / NetworkInterceptor
        // installed in the Hilt-provided OkHttpClient used by the proxy layer.
    }

    fun stopIntercepting() {
        Log.i(TAG, "Token tracking session ended: $currentSessionId")
    }

    fun newSession() {
        currentSessionId = UUID.randomUUID().toString()
    }

    /**
     * Record a completed API call. Called directly by the gateway WebSocket message
     * handler whenever it receives a usage object.
     */
    fun record(
        provider:     String,
        model:        String,
        inputTokens:  Int,
        outputTokens: Int,
        prompt:       String = "",
        response:     String = ""
    ) {
        scope.launch {
            val cost = computeCost(model, provider, inputTokens, outputTokens)
            val entity = TokenEntity(
                sessionId    = currentSessionId,
                timestamp    = System.currentTimeMillis(),
                provider     = provider,
                model        = model,
                inputTokens  = inputTokens,
                outputTokens = outputTokens,
                costUsd      = cost,
                prompt       = prompt.take(300),
                response     = response.take(300)
            )
            tokenDao.insert(entity)
            Log.d(TAG, "Recorded: $inputTokens in / $outputTokens out @ \$${"%.6f".format(cost)}")
        }
    }

    /**
     * Parse OpenClaw gateway log lines and extract token usage.
     * Call this from NodeRunner's stdout reader.
     */
    fun parseGatewayLog(line: String) {
        // OpenClaw logs lines like:
        // [usage] provider=anthropic model=claude-sonnet-4-20250514 in=1234 out=567
        if (!line.contains("[usage]")) return
        try {
            val provider     = Regex("provider=(\\S+)").find(line)?.groupValues?.get(1) ?: "unknown"
            val model        = Regex("model=(\\S+)").find(line)?.groupValues?.get(1) ?: "unknown"
            val inputTokens  = Regex("in=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val outputTokens = Regex("out=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            record(provider, model, inputTokens, outputTokens)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse usage log: ${e.message}")
        }
    }

    /** Export all records as CSV string. */
    suspend fun exportCsv(): String {
        val rows = tokenDao.allForExport()
        val sb = StringBuilder()
        sb.appendLine("id,sessionId,timestamp,provider,model,inputTokens,outputTokens,costUsd,prompt,response")
        rows.forEach { r ->
            sb.appendLine(
                "${r.id},${r.sessionId},${r.timestamp},${r.provider},${r.model}," +
                "${r.inputTokens},${r.outputTokens},${"%.8f".format(r.costUsd)}," +
                "\"${r.prompt.replace("\"","'")}\",\"${r.response.replace("\"","'")}\""
            )
        }
        return sb.toString()
    }

    suspend fun clearAll() = tokenDao.deleteAll()

    // ──────────────────────────────────────────────────────────────

    private fun computeCost(model: String, provider: String, inputTokens: Int, outputTokens: Int): Double {
        if (provider == "ollama") return 0.0
        val pricing = PRICING[model]
            ?: PRICING.entries.firstOrNull { model.startsWith(it.key.substringBefore("-2")) }?.value
            ?: Pair(3.00, 15.00) // default to claude-sonnet pricing
        val inputCost  = (inputTokens / 1_000_000.0)  * pricing.first
        val outputCost = (outputTokens / 1_000_000.0) * pricing.second
        return inputCost + outputCost
    }
}
