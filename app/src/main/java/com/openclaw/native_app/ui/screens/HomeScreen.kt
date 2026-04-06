package com.openclaw.native_app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.native_app.MainViewModel
import com.openclaw.native_app.NodeState

@Composable
fun HomeScreen(vm: MainViewModel) {
    val gatewayState by vm.gatewayState.collectAsState()
    val totalInput   by vm.totalInputTokens.collectAsState()
    val totalOutput  by vm.totalOutputTokens.collectAsState()
    val totalCost    by vm.totalCostUsd.collectAsState()
    val provider     by vm.selectedProvider.collectAsState(initial = "anthropic")
    val model        by vm.selectedModel.collectAsState(initial = "claude-sonnet-4-20250514")

    val isRunning  = gatewayState == NodeState.RUNNING
    val isStarting = gatewayState == NodeState.STARTING
    val isCrashed  = gatewayState == NodeState.CRASHED

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.4f, targetValue = 1f, label = "alpha",
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        )
    )

    val statusColor by animateColorAsState(
        when (gatewayState) {
            NodeState.RUNNING  -> Color(0xFF00C853)
            NodeState.STARTING -> Color(0xFFFFAB00)
            NodeState.CRASHED  -> Color(0xFFD50000)
            NodeState.STOPPED  -> Color(0xFF757575)
        }, label = "statusColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        // Logo / title
        Text("⚡ OpenClaw", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Native Android Gateway", fontSize = 14.sp, color = Color(0xFF8B949E))

        Spacer(Modifier.height(48.dp))

        // Status indicator
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isStarting) pulseAlpha * 0.2f else 0.15f))
            )
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isRunning) pulseAlpha * 0.3f else 0.2f))
            )
            Icon(
                imageVector = if (isRunning) Icons.Default.Bolt else Icons.Default.PowerSettingsNew,
                contentDescription = "status",
                tint = statusColor,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = when (gatewayState) {
                NodeState.RUNNING  -> "Gateway Running"
                NodeState.STARTING -> "Starting…"
                NodeState.CRASHED  -> "Crashed — Retrying"
                NodeState.STOPPED  -> "Gateway Stopped"
            },
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = statusColor
        )
        Text("localhost:18789", fontSize = 13.sp, color = Color(0xFF8B949E))

        Spacer(Modifier.height(8.dp))
        Text("$provider · $model", fontSize = 12.sp, color = Color(0xFF58A6FF))

        Spacer(Modifier.height(40.dp))

        // Start / Stop button
        Button(
            onClick = { if (isRunning || isStarting) vm.stopGateway() else vm.startGateway() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning || isStarting) Color(0xFF8B0000) else Color(0xFF238636)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = if (isRunning || isStarting) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null, tint = Color.White
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning || isStarting) "Stop Gateway" else "Start Gateway",
                fontSize = 16.sp, color = Color.White
            )
        }

        Spacer(Modifier.height(32.dp))

        // Token stats cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(modifier = Modifier.weight(1f), label = "Input Tokens", value = totalInput.toString(), color = Color(0xFF58A6FF))
            StatCard(modifier = Modifier.weight(1f), label = "Output Tokens", value = totalOutput.toString(), color = Color(0xFF3FB950))
        }
        Spacer(Modifier.height(12.dp))
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            label = "Total Cost (session)",
            value = "\$${"%.4f".format(totalCost)}",
            color = Color(0xFFFFAB00)
        )

        Spacer(Modifier.height(24.dp))

        // Battery optimization warning
        if (isCrashed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1B00)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFAB00))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Gateway crashed. Check Settings → Permissions to ensure battery optimizations are disabled.",
                        fontSize = 12.sp, color = Color(0xFFFFAB00)
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, color = Color(0xFF8B949E))
        }
    }
}
