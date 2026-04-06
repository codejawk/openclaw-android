package com.openclaw.native_app.ui.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openclaw.native_app.BootstrapManager
import com.openclaw.native_app.MainViewModel
import com.openclaw.native_app.NodeState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardScreen(vm: MainViewModel) {
    val gatewayState by vm.gatewayState.collectAsState()
    val authToken    by vm.authToken.collectAsState(initial = "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val gatewayUrl = "http://127.0.0.1:${BootstrapManager.GATEWAY_PORT}?token=$authToken"

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Dashboard",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(gatewayState)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color(0xFF58A6FF))
                }
            }
        }

        if (gatewayState != NodeState.RUNNING) {
            // Gateway not running — show placeholder
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚡", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Start the gateway to open the dashboard",
                        color = Color(0xFF8B949E)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.startGateway() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                    ) {
                        Text("Start Gateway", color = Color.White)
                    }
                }
            }
        } else {
            // WebView showing openclaw dashboard
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = false
                                displayZoomControls = false
                                setSupportZoom(true)
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    isLoading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView, req: WebResourceRequest, error: WebResourceError
                                ) {
                                    // Gateway may still be booting — retry after delay
                                }
                            }
                            loadUrl(gatewayUrl)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart),
                        color = Color(0xFF58A6FF)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(state: NodeState) {
    val (text, color) = when (state) {
        NodeState.RUNNING  -> "LIVE" to Color(0xFF00C853)
        NodeState.STARTING -> "STARTING" to Color(0xFFFFAB00)
        NodeState.CRASHED  -> "CRASHED" to Color(0xFFD50000)
        NodeState.STOPPED  -> "OFFLINE" to Color(0xFF757575)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}
