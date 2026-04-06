package com.openclaw.native_app.ui.screens

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openclaw.native_app.MainViewModel
import com.openclaw.native_app.data.db.TokenEntity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TokenUsageScreen(vm: MainViewModel) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val totalInput    by vm.totalInputTokens.collectAsState()
    val totalOutput   by vm.totalOutputTokens.collectAsState()
    val totalCost     by vm.totalCostUsd.collectAsState()
    val recentRows    by vm.recentUsage.collectAsState()
    var showClearDlg  by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Token Usage", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Row {
                // Export CSV
                IconButton(onClick = {
                    scope.launch {
                        val csv  = vm.exportCsv()
                        val file = File(context.cacheDir, "openclaw_tokens_${System.currentTimeMillis()}.csv")
                        file.writeText(csv)
                        val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Export CSV"))
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Export CSV", tint = Color(0xFF58A6FF))
                }
                // New session
                IconButton(onClick = { vm.newSession() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "New Session", tint = Color(0xFF3FB950))
                }
                // Clear all
                IconButton(onClick = { showClearDlg = true }) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Clear", tint = Color(0xFFD50000))
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary cards
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TokenSummaryCard(Modifier.weight(1f), "Input", totalInput.toString(), Color(0xFF58A6FF))
                    TokenSummaryCard(Modifier.weight(1f), "Output", totalOutput.toString(), Color(0xFF3FB950))
                }
            }
            item {
                TokenSummaryCard(
                    Modifier.fillMaxWidth(),
                    label = "Cumulative Cost",
                    value = "\$${"%.6f".format(totalCost)}",
                    color = Color(0xFFFFAB00)
                )
            }

            item {
                Text(
                    "Recent Calls",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (recentRows.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No API calls recorded yet", color = Color(0xFF8B949E))
                    }
                }
            } else {
                items(recentRows) { row -> TokenRowCard(row) }
            }
        }
    }

    if (showClearDlg) {
        AlertDialog(
            onDismissRequest = { showClearDlg = false },
            title = { Text("Clear all token history?") },
            text  = { Text("This permanently deletes all recorded API call data.") },
            confirmButton = {
                TextButton(onClick = { vm.clearTokenHistory(); showClearDlg = false }) {
                    Text("Clear", color = Color(0xFFD50000))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDlg = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF161B22),
            titleContentColor = Color.White,
            textContentColor = Color(0xFF8B949E)
        )
    }
}

@Composable
fun TokenSummaryCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, color = Color(0xFF8B949E))
        }
    }
}

@Composable
fun TokenRowCard(row: TokenEntity) {
    val dateStr = remember(row.timestamp) {
        SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(row.timestamp))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.model, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(dateStr, fontSize = 11.sp, color = Color(0xFF8B949E))
                if (row.prompt.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(row.prompt.take(60) + "…", fontSize = 11.sp, color = Color(0xFF58A6FF))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("↑${row.inputTokens}", fontSize = 12.sp, color = Color(0xFF58A6FF))
                Text("↓${row.outputTokens}", fontSize = 12.sp, color = Color(0xFF3FB950))
                Text("\$${"%.5f".format(row.costUsd)}", fontSize = 11.sp, color = Color(0xFFFFAB00))
            }
        }
    }
}
