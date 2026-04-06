package com.openclaw.native_app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.openclaw.native_app.MainViewModel

data class PermissionItem(
    val permission: String?,        // null = special permission (battery opt)
    val label: String,
    val description: String,
    val critical: Boolean = true
)

private val ALL_PERMISSIONS = listOf(
    PermissionItem(Manifest.permission.CAMERA,            "Camera",               "Capture photos and video"),
    PermissionItem(Manifest.permission.RECORD_AUDIO,      "Microphone",           "Voice input & audio recording"),
    PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION, "Precise Location",  "GPS coordinates"),
    PermissionItem(Manifest.permission.READ_CONTACTS,     "Contacts",             "Read your contact list"),
    PermissionItem(Manifest.permission.READ_CALENDAR,     "Calendar (Read)",      "List upcoming events"),
    PermissionItem(Manifest.permission.WRITE_CALENDAR,    "Calendar (Write)",     "Create calendar events"),
    PermissionItem(Manifest.permission.SEND_SMS,          "Send SMS",             "Send text messages"),
    PermissionItem(Manifest.permission.READ_SMS,          "Read SMS",             "Read text messages"),
    PermissionItem(Manifest.permission.READ_MEDIA_IMAGES, "Media/Photos",         "Access your photo library"),
    PermissionItem(Manifest.permission.POST_NOTIFICATIONS,"Notifications",        "Show status notifications"),
    PermissionItem(null, "Battery Optimization",          "Prevent Samsung from killing the gateway", true)
)

@Composable
fun PermissionsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) } // force recompose after grants

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    val runtimePerms = ALL_PERMISSIONS.filter { it.permission != null }.map { it.permission!! }

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
            Text("Permissions", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Button(
                onClick = { multiLauncher.launch(runtimePerms.toTypedArray()) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
            ) { Text("Grant All", color = Color.White) }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                val grantedCount = ALL_PERMISSIONS.count { item ->
                    if (item.permission != null)
                        ContextCompat.checkSelfPermission(context, item.permission) == PackageManager.PERMISSION_GRANTED
                    else
                        context.getSystemService(PowerManager::class.java)
                            .isIgnoringBatteryOptimizations(context.packageName)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            progress = { grantedCount.toFloat() / ALL_PERMISSIONS.size },
                            color = if (grantedCount == ALL_PERMISSIONS.size) Color(0xFF3FB950) else Color(0xFFFFAB00),
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("$grantedCount / ${ALL_PERMISSIONS.size} granted",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                if (grantedCount == ALL_PERMISSIONS.size) "All permissions granted"
                                else "Some permissions missing — tap to grant",
                                fontSize = 12.sp, color = Color(0xFF8B949E)
                            )
                        }
                    }
                }
                // force recompose on tick change
                if (tick < 0) Text("")
            }

            items(ALL_PERMISSIONS) { item ->
                val granted = if (item.permission != null) {
                    ContextCompat.checkSelfPermission(context, item.permission) == PackageManager.PERMISSION_GRANTED
                } else {
                    context.getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName)
                }

                PermissionRow(
                    item = item,
                    granted = granted,
                    onGrant = {
                        if (item.permission != null) {
                            multiLauncher.launch(arrayOf(item.permission))
                        } else {
                            vm.requestBatteryOptimizationExemption()
                            tick++
                        }
                    }
                )
                // force recompose
                if (tick < 0) Text("")
            }
        }
    }
}

@Composable
fun PermissionRow(item: PermissionItem, granted: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (granted) Color(0xFF3FB950) else if (item.critical) Color(0xFFD50000) else Color(0xFF8B949E),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(item.description, fontSize = 12.sp, color = Color(0xFF8B949E))
            }
            if (!granted) {
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}
