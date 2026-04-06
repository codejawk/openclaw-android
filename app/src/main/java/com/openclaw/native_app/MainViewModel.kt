package com.openclaw.native_app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openclaw_settings")

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodeRunner:    NodeRunner,
    private val tokenTracker:  TokenTracker,
    private val ollamaClient:  OllamaClient,
    private val bootstrap:     BootstrapManager
) : ViewModel() {

    // Keys
    private object Keys {
        val AUTH_TOKEN         = stringPreferencesKey("auth_token")
        val ANTHROPIC_KEY      = stringPreferencesKey("anthropic_key")
        val OPENAI_KEY         = stringPreferencesKey("openai_key")
        val GEMINI_KEY         = stringPreferencesKey("gemini_key")
        val OPENROUTER_KEY     = stringPreferencesKey("openrouter_key")
        val SELECTED_PROVIDER  = stringPreferencesKey("selected_provider")
        val SELECTED_MODEL     = stringPreferencesKey("selected_model")
        val OLLAMA_ENDPOINT    = stringPreferencesKey("ollama_endpoint")
        val WAKE_WORD_ENABLED  = booleanPreferencesKey("wake_word_enabled")
        val AUTO_START         = booleanPreferencesKey("auto_start")
    }

    // Gateway state
    val gatewayState: StateFlow<NodeState> = nodeRunner.state

    // Settings
    val authToken:        Flow<String> = context.dataStore.data.map { it[Keys.AUTH_TOKEN]        ?: generateToken() }
    val anthropicKey:     Flow<String> = context.dataStore.data.map { it[Keys.ANTHROPIC_KEY]     ?: "" }
    val openaiKey:        Flow<String> = context.dataStore.data.map { it[Keys.OPENAI_KEY]        ?: "" }
    val geminiKey:        Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_KEY]        ?: "" }
    val openrouterKey:    Flow<String> = context.dataStore.data.map { it[Keys.OPENROUTER_KEY]    ?: "" }
    val selectedProvider: Flow<String> = context.dataStore.data.map { it[Keys.SELECTED_PROVIDER] ?: "anthropic" }
    val selectedModel:    Flow<String> = context.dataStore.data.map { it[Keys.SELECTED_MODEL]    ?: "claude-sonnet-4-20250514" }
    val ollamaEndpoint:   Flow<String> = context.dataStore.data.map { it[Keys.OLLAMA_ENDPOINT]   ?: "http://localhost:11434" }
    val wakeWordEnabled:  Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_WORD_ENABLED] ?: false }

    // Token stats
    val totalInputTokens  = tokenTracker.totalInputTokens.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val totalOutputTokens = tokenTracker.totalOutputTokens.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val totalCostUsd      = tokenTracker.totalCostUsd.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    val recentUsage       = tokenTracker.observeRecent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Ollama models
    private val _ollamaModels = MutableStateFlow<List<OllamaModel>>(emptyList())
    val ollamaModels: StateFlow<List<OllamaModel>> = _ollamaModels

    private val _ollamaAvailable = MutableStateFlow(false)
    val ollamaAvailable: StateFlow<Boolean> = _ollamaAvailable

    // ──────────────────────────────────────────────────────────────

    fun startGateway() {
        viewModelScope.launch {
            val token = authToken.first()
            val finalToken = token.ifEmpty { generateToken().also { saveAuthToken(it) } }
            writeOpenClawConfig()
            val intent = GatewayService.startIntent(context, finalToken)
            context.startForegroundService(intent)
        }
    }

    fun stopGateway() {
        context.startService(GatewayService.stopIntent(context))
    }

    fun refreshOllamaModels() {
        viewModelScope.launch {
            val endpoint = ollamaEndpoint.first()
            ollamaClient.baseUrl = endpoint
            _ollamaAvailable.value = ollamaClient.isAvailable()
            if (_ollamaAvailable.value) {
                _ollamaModels.value = ollamaClient.listModels()
            }
        }
    }

    fun requestBatteryOptimizationExemption() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun newSession() = tokenTracker.newSession()

    suspend fun exportCsv(): String = tokenTracker.exportCsv()

    fun clearTokenHistory() {
        viewModelScope.launch { tokenTracker.clearAll() }
    }

    // ── Settings savers ──────────────────────────────────────────

    fun saveAnthropicKey(key: String) = savePref { it[Keys.ANTHROPIC_KEY] = key }
    fun saveOpenaiKey(key: String)    = savePref { it[Keys.OPENAI_KEY] = key }
    fun saveGeminiKey(key: String)    = savePref { it[Keys.GEMINI_KEY] = key }
    fun saveOpenrouterKey(key: String)= savePref { it[Keys.OPENROUTER_KEY] = key }
    fun saveSelectedProvider(p: String) = savePref { it[Keys.SELECTED_PROVIDER] = p }
    fun saveSelectedModel(m: String)    = savePref { it[Keys.SELECTED_MODEL] = m }
    fun saveOllamaEndpoint(url: String) = savePref { it[Keys.OLLAMA_ENDPOINT] = url }
    fun saveWakeWordEnabled(b: Boolean) = savePref { it[Keys.WAKE_WORD_ENABLED] = b }
    private fun saveAuthToken(t: String) = savePref { it[Keys.AUTH_TOKEN] = t }

    private fun savePref(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            context.dataStore.edit { block(it) }
        }
    }

    // ── Config writer ─────────────────────────────────────────────

    private suspend fun writeOpenClawConfig() {
        val provider = selectedProvider.first()
        val model    = selectedModel.first()
        val configDir = bootstrap.configDir
        configDir.mkdirs()

        val providerConfig = when (provider) {
            "anthropic" -> {
                val key = anthropicKey.first()
                """
                providers:
                  - name: anthropic
                    type: anthropic
                    api_key: "$key"
                    default_model: $model
                """.trimIndent()
            }
            "openai" -> {
                val key = openaiKey.first()
                """
                providers:
                  - name: openai
                    type: openai
                    api_key: "$key"
                    default_model: $model
                """.trimIndent()
            }
            "gemini" -> {
                val key = geminiKey.first()
                """
                providers:
                  - name: gemini
                    type: google
                    api_key: "$key"
                    default_model: $model
                """.trimIndent()
            }
            "ollama" -> {
                val endpoint = ollamaEndpoint.first()
                ollamaClient.buildOpenClawConfig(model, endpoint)
            }
            "openrouter" -> {
                val key = openrouterKey.first()
                """
                providers:
                  - name: openrouter
                    type: openrouter
                    api_key: "$key"
                    default_model: $model
                """.trimIndent()
            }
            else -> ""
        }

        val fullConfig = """
            gateway:
              bind: "127.0.0.1"
              port: ${BootstrapManager.GATEWAY_PORT}
              mdns:
                enabled: false
            $providerConfig
        """.trimIndent()

        File(configDir, "config.yaml").writeText(fullConfig)
    }

    private fun generateToken() = UUID.randomUUID().toString().replace("-", "")
}
