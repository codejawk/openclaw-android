package com.openclaw.native_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.*
import com.openclaw.native_app.ui.screens.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_CAMERA_BACK   = "com.openclaw.ACTION_CAMERA_BACK"
        const val ACTION_CAMERA_FRONT  = "com.openclaw.ACTION_CAMERA_FRONT"
        const val ACTION_SCREEN_CAPTURE= "com.openclaw.ACTION_SCREEN_CAPTURE"
        const val EXTRA_CAPTURE_URI    = "capture_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenClawTheme {
                OpenClawNavHost()
            }
        }
    }
}

// ── Navigation Destinations ─────────────────────────────────────

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home        : Screen("home",        "Home",        Icons.Default.Home)
    object Dashboard   : Screen("dashboard",   "Dashboard",   Icons.Default.Dashboard)
    object Channels    : Screen("channels",    "Channels",    Icons.Default.Chat)
    object TokenUsage  : Screen("tokens",      "Tokens",      Icons.Default.Analytics)
    object Settings    : Screen("settings",    "Settings",    Icons.Default.Settings)
    object Skills      : Screen("skills",      "Skills",      Icons.Default.Extension)
    object Permissions : Screen("permissions", "Permissions", Icons.Default.Security)
}

private val BOTTOM_NAV = listOf(
    Screen.Home, Screen.Dashboard, Screen.Channels,
    Screen.TokenUsage, Screen.Settings
)

// ── Root Composable ─────────────────────────────────────────────

@Composable
fun OpenClawNavHost() {
    val navController  = rememberNavController()
    val vm: MainViewModel = hiltViewModel()
    val currentEntry   by navController.currentBackStackEntryAsState()
    val currentRoute   = currentEntry?.destination?.route

    Scaffold(
        containerColor = Color(0xFF0D1117),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF161B22),
                contentColor   = Color.White
            ) {
                BOTTOM_NAV.forEach { screen ->
                    NavigationBarItem(
                        selected      = currentRoute == screen.route,
                        onClick       = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Color(0xFF58A6FF),
                            selectedTextColor   = Color(0xFF58A6FF),
                            unselectedIconColor = Color(0xFF8B949E),
                            unselectedTextColor = Color(0xFF8B949E),
                            indicatorColor      = Color(0xFF1F6FEB).copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0D1117))
        ) {
            composable(Screen.Home.route)        { HomeScreen(vm) }
            composable(Screen.Dashboard.route)   { DashboardScreen(vm) }
            composable(Screen.Channels.route)    { ChannelsScreen(vm) }
            composable(Screen.TokenUsage.route)  { TokenUsageScreen(vm) }
            composable(Screen.Settings.route)    { SettingsScreen(vm) }
            composable(Screen.Skills.route)      { SkillsScreen(vm) }
            composable(Screen.Permissions.route) { PermissionsScreen(vm) }
        }
    }
}

// ── Theme ────────────────────────────────────────────────────────

@Composable
fun OpenClawTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary         = Color(0xFF58A6FF),
        secondary       = Color(0xFF3FB950),
        background      = Color(0xFF0D1117),
        surface         = Color(0xFF161B22),
        onPrimary       = Color.White,
        onSecondary     = Color.White,
        onBackground    = Color.White,
        onSurface       = Color.White
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content     = content
    )
}
