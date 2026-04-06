package com.openclaw.native_app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.native_app.MainViewModel

private val PROVIDERS = listOf("anthropic", "openai", "gemini", "ollama", "openrouter")

private val MODELS_BY_PROVIDER = mapOf(
    "anthropic"   to listOf("claude-sonnet-4-20250514", "claude-opus-4-5", "claude-haiku-4-5"),
    "openai"      to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"),
    "gemini"      to listOf("gemini-1.5-pro", "gemini-1.5-flash"),
    "ollama"      to listOf("llama3", "mistral", "phi3", "gemma2", "qwen2.5"),
    "openrouter"  to listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "meta-llama/llama-3.1-70b")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val anthropicKey   by vm.anthropicKey.collectAsState(initial = "")
    val openaiKey      by vm.openaiKey.collectAsState(initial = "")
    val geminiKey      by vm.geminiKey.collectAsState(initial = "")
    val openrouterKey  by vm.openrouterKey.collectAsState(initial = "")
    val selectedProv   by vm.selectedProvider.collectAsState(initial = "anthropic")
    val selectedModel  by vm.selectedModel.collectAsState(initial = "claude-sonnet-4-20250514")
    val ollamaEndpoint by vm.ollamaEndpoint.collectAsState(initial = "http://localhost:11434")
    val wakeWord       by vm.wakeWordEnabled.collectAsState(initial = false)
    val ollamaAvail    by vm.ollamaAvailable.collectAsState()
    val ollamaModels   by vm.ollamaModels.collectAsState()

    var localAnthropicKey   by remember(anthropicKey)  { mutableStateOf(anthropicKey) }
    var localOpenaiKey      by remember(openaiKey)     { mutableStateOf(openaiKey) }
    var localGeminiKey      by remember(geminiKey)     { mutableStateOf(geminiKey) }
    var localOpenrouterKey  by remember(openrouterKey) { mutableStateOf(openrouterKey) }
    var localOllamaEndpoint by remember(ollamaEndpoint){ mutableStateOf(ollamaEndpoint) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SectionHeader("AI Provider") }

        // Provider selector
        item {
            SettingsCard {
                Text("Active Provider", fontSize = 13.sp, color = Color(0xFF8B949E))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROVIDERS.forEach { p ->
                        FilterChip(
                            selected = selectedProv == p,
                            onClick  = { vm.saveSelectedProvider(p) },
                            label    = { Text(p.replaceFirstChar { it.uppercase() }) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor    = Color(0xFF238636),
                                selectedLabelColor        = Color.White,
                                containerColor            = Color(0xFF21262D),
                                labelColor                = Color(0xFF8B949E)
                            )
                        )
                    }
                }
            }
        }

        // Model selector
        item {
            val models = MODELS_BY_PROVIDER[selectedProv] ?: emptyList()
            SettingsCard {
                Text("Model", fontSize = 13.sp, color = Color(0xFF8B949E))
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Selected Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = outlinedFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded, onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Color(0xFF21262D))
                    ) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m, color = Color.White) },
                                onClick = { vm.saveSelectedModel(m); expanded = false }
                            )
                        }
                    }
                }
            }
        }

        // API Keys
        item { SectionHeader("API Keys") }

        if (selectedProv == "anthropic" || selectedProv == "openrouter") {
            item {
                SettingsCard {
                    ApiKeyField(
                        label = "Anthropic API Key",
                        value = localAnthropicKey,
                        onValueChange = { localAnthropicKey = it },
                        onSave = { vm.saveAnthropicKey(localAnthropicKey) }
                    )
                }
            }
        }

        if (selectedProv == "openai") {
            item {
                SettingsCard {
                    ApiKeyField("OpenAI API Key", localOpenaiKey,
                        { localOpenaiKey = it }, { vm.saveOpenaiKey(localOpenaiKey) })
                }
            }
        }

        if (selectedProv == "gemini") {
            item {
                SettingsCard {
                    ApiKeyField("Google Gemini API Key", localGeminiKey,
                        { localGeminiKey = it }, { vm.saveGeminiKey(localGeminiKey) })
                }
            }
        }

        if (selectedProv == "openrouter") {
            item {
                SettingsCard {
                    ApiKeyField("OpenRouter API Key", localOpenrouterKey,
                        { localOpenrouterKey = it }, { vm.saveOpenrouterKey(localOpenrouterKey) })
                }
            }
        }

        // Ollama
        if (selectedProv == "ollama") {
            item { SectionHeader("Local Ollama") }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = localOllamaEndpoint,
                            onValueChange = { localOllamaEndpoint = it },
                            label = { Text("Ollama Endpoint") },
                            modifier = Modifier.weight(1f),
                            colors = outlinedFieldColors()
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                vm.saveOllamaEndpoint(localOllamaEndpoint)
                                vm.refreshOllamaModels()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                        ) { Text("Test") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (ollamaAvail) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (ollamaAvail) Color(0xFF3FB950) else Color(0xFF8B949E),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (ollamaAvail) "Ollama connected — ${ollamaModels.size} models"
                            else "Ollama not reachable",
                            fontSize = 12.sp,
                            color = if (ollamaAvail) Color(0xFF3FB950) else Color(0xFF8B949E)
                        )
                    }
                }
            }
        }

        // Voice
        item { SectionHeader("Voice") }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Wake Word Detection", color = Color.White, fontSize = 14.sp)
                        Text("\"Hey OpenClaw\" triggers voice input", fontSize = 12.sp, color = Color(0xFF8B949E))
                    }
                    Switch(
                        checked = wakeWord,
                        onCheckedChange = { vm.saveWakeWordEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF238636))
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = Color(0xFF58A6FF),
        letterSpacing = 1.sp
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null, tint = Color(0xFF8B949E)
                    )
                }
            },
            modifier = Modifier.weight(1f),
            colors = outlinedFieldColors()
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White)
        }
    }
}

@Composable
fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor        = Color.White,
    unfocusedTextColor      = Color.White,
    focusedBorderColor      = Color(0xFF58A6FF),
    unfocusedBorderColor    = Color(0xFF30363D),
    focusedLabelColor       = Color(0xFF58A6FF),
    unfocusedLabelColor     = Color(0xFF8B949E),
    cursorColor             = Color(0xFF58A6FF)
)
