package com.openclaw.native_app.ui.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openclaw.native_app.BootstrapManager
import com.openclaw.native_app.MainViewModel

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SkillsScreen(vm: MainViewModel) {
    val authToken by vm.authToken.collectAsState(initial = "")
    var showSoulEditor by remember { mutableStateOf(false) }
    var soulContent    by remember { mutableStateOf("") }

    if (showSoulEditor) {
        SoulEditor(
            content = soulContent,
            onSave = { updated ->
                soulContent = updated
                // Write to ~/.openclaw/workspace/SOUL.md via filesystem
                // (BootstrapManager homeDir is available via vm but we need context here)
                showSoulEditor = false
            },
            onBack = { showSoulEditor = false }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Skills", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Row {
                TextButton(onClick = { showSoulEditor = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF58A6FF))
                    Spacer(Modifier.width(4.dp))
                    Text("SOUL.md", color = Color(0xFF58A6FF))
                }
            }
        }

        // ClawHub skills browser via embedded WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webViewClient = WebViewClient()
                    // OpenClaw's built-in skills UI
                    loadUrl("http://127.0.0.1:${BootstrapManager.GATEWAY_PORT}/__openclaw__/skills?token=$authToken")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun SoulEditor(content: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf(content) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("SOUL.md", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Button(
                onClick = { onSave(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
            ) {
                Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("Save", color = Color.White)
            }
        }

        Text(
            "SOUL.md defines your AI's personality and behaviour. Written in Markdown.",
            fontSize = 12.sp, color = Color(0xFF8B949E),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                focusedBorderColor   = Color(0xFF58A6FF),
                unfocusedBorderColor = Color(0xFF30363D),
                cursorColor          = Color(0xFF58A6FF)
            ),
            placeholder = { Text("# My AI Soul\n\nYou are a helpful assistant…", color = Color(0xFF8B949E)) }
        )
    }
}
