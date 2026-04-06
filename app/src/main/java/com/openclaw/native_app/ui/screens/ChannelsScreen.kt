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

data class Channel(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val configPath: String   // path relative to openclaw gateway UI
)

private val CHANNELS = listOf(
    Channel("whatsapp",  "WhatsApp",  "💬", "WhatsApp Business / Web bridge", "/__openclaw__/channels/whatsapp"),
    Channel("telegram",  "Telegram",  "✈️", "Telegram Bot API",               "/__openclaw__/channels/telegram"),
    Channel("slack",     "Slack",     "🔷", "Slack App integration",          "/__openclaw__/channels/slack"),
    Channel("discord",   "Discord",   "🎮", "Discord bot",                    "/__openclaw__/channels/discord"),
    Channel("sms",       "SMS",       "📱", "Native Android SMS",             "/__openclaw__/channels/sms"),
    Channel("email",     "Email",     "📧", "IMAP/SMTP email integration",    "/__openclaw__/channels/email"),
    Channel("web",       "Web Widget","🌐", "Embeddable web chat widget",     "/__openclaw__/channels/web")
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChannelsScreen(vm: MainViewModel) {
    val authToken by vm.authToken.collectAsState(initial = "")
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }

    if (selectedChannel != null) {
        val ch = selectedChannel!!
        Column(Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedChannel = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("${ch.icon} ${ch.name}", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            // WebView showing channel config inside the openclaw dashboard
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
                        loadUrl("http://127.0.0.1:${BootstrapManager.GATEWAY_PORT}${ch.configPath}?token=$authToken")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
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
                .padding(16.dp)
        ) {
            Text("Channels", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Connect messaging channels — all running inside the Node.js gateway, no extra apps needed.",
                    fontSize = 13.sp, color = Color(0xFF8B949E)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(CHANNELS) { ch ->
                ChannelCard(channel = ch, onClick = { selectedChannel = ch })
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channel.icon, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(channel.description, fontSize = 12.sp, color = Color(0xFF8B949E))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF8B949E))
        }
    }
}
