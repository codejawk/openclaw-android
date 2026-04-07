package com.openclaw.native_app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

enum class NodeState { STOPPED, STARTING, RUNNING, CRASHED }

/**
 * NodeRunner
 *
 * Manages the lifecycle of the Node.js gateway embedded via JNI (NodeBridge).
 *
 * Because libnode.so from nodejs-mobile is an Android Bionic shared library
 * (ET_DYN), it cannot be exec()-ed directly.  NodeBridge.startNode() loads
 * it with dlopen and calls node::Start(argc, argv) on a detached pthread.
 *
 * Lifecycle:
 *   start()  → triggers launchAndWait() inside a watchdog coroutine
 *   stop()   → cancels the watchdog; embedded node keeps running (harmless)
 *              until the OS kills the process
 */
@Singleton
class NodeRunner @Inject constructor(
    private val bootstrap: BootstrapManager
) {
    companion object {
        private const val TAG                = "NodeRunner"
        private const val MAX_START_WAIT_SEC = 60    // max seconds to wait for gateway port
        private const val POLL_INTERVAL_MS   = 1_500L
        private const val HEALTH_INTERVAL_MS = 5_000L
    }

    private val _state = MutableStateFlow(NodeState.STOPPED)
    val state: StateFlow<NodeState> = _state

    private var watchdogJob: Job? = null
    private var authToken: String = ""

    /** Start the gateway. Idempotent — no-ops if already running or starting. */
    fun start(token: String) {
        if (_state.value == NodeState.RUNNING || _state.value == NodeState.STARTING) return
        authToken = token
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            runWithWatchdog()
        }
    }

    /** Stop the gateway (marks it as stopped; node thread keeps running). */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        _state.value = NodeState.STOPPED
        Log.i(TAG, "Gateway stopped (embedded node will exit with app)")
    }

    val isRunning: Boolean get() = _state.value == NodeState.RUNNING

    // ──────────────────────────────────────────────────────────────

    private suspend fun runWithWatchdog() {
        _state.value = NodeState.STARTING
        Log.i(TAG, "Starting Node.js gateway via JNI embedding")

        try {
            launchAndMonitor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Node.js gateway error: ${e.message}")
        }

        if (_state.value != NodeState.STOPPED) {
            _state.value = NodeState.CRASHED
            Log.e(TAG, "Node.js gateway exited unexpectedly")
        }
    }

    private suspend fun launchAndMonitor() = withContext(Dispatchers.IO) {
        val nodeBin    = bootstrap.nodeBin        // nativeLibraryDir/libnode.so
        val nodeDir    = bootstrap.nodeDir        // filesDir/openclaw/
        val env        = bootstrap.buildEnvironment(authToken)
        val entryPoint = findEntryPoint(nodeDir)
        val port       = BootstrapManager.GATEWAY_PORT

        Log.i(TAG, "libnode.so : ${nodeBin.absolutePath} (exists=${nodeBin.exists()})")
        Log.i(TAG, "Entry point: ${entryPoint.absolutePath}")

        if (!nodeBin.exists()) {
            throw IllegalStateException(
                "libnode.so not found at ${nodeBin.absolutePath}. " +
                "Ensure jniLibs/arm64-v8a/libnode.so is present and the APK was rebuilt."
            )
        }
        if (!entryPoint.exists()) {
            throw IllegalStateException(
                "Node entry point not found: ${entryPoint.absolutePath}. " +
                "Bootstrap may have failed — try clearing app data."
            )
        }

        // argv[0] = program name ("node"), then real arguments
        val args = listOf(
            "node",
            "--experimental-specifier-resolution=node",
            entryPoint.absolutePath
        )

        // Launch — non-blocking; node runs on a detached pthread
        NodeBridge.startNode(
            libPath = nodeBin.absolutePath,
            cwd     = nodeDir.absolutePath,
            env     = env,
            args    = args
        )
        Log.i(TAG, "NodeBridge.startNode() returned — node thread is running")

        // ── Wait for gateway port to open ──────────────────────────
        Log.i(TAG, "Waiting for gateway port $port to open…")
        var portOpen = false
        repeat(MAX_START_WAIT_SEC) {
            if (!isActive) return@withContext
            delay(POLL_INTERVAL_MS)
            if (isPortOpen(port)) {
                portOpen = true
                return@repeat
            }
        }

        if (!portOpen) {
            throw RuntimeException(
                "Gateway port $port never opened after ${MAX_START_WAIT_SEC}s — " +
                "check logcat for Node.js errors"
            )
        }

        _state.value = NodeState.RUNNING
        Log.i(TAG, "Gateway is UP on port $port")

        // ── Health-check loop ──────────────────────────────────────
        while (isActive) {
            delay(HEALTH_INTERVAL_MS)
            if (!isPortOpen(port) && !NodeBridge.isRunning) {
                Log.w(TAG, "Gateway health check failed — port $port is down")
                break
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun isPortOpen(port: Int, timeoutMs: Int = 1_000): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findEntryPoint(nodeDir: File): File {
        val candidates = listOf(
            "dist/index.js",
            "index.js",
            "server.js",
            "bin/openclaw.js",
            "bin/gateway.js",
            "lib/index.js"
        )
        for (c in candidates) {
            val f = File(nodeDir, c)
            if (f.exists()) return f
        }

        // Try package.json "main"
        val pkgJson = File(nodeDir, "package.json")
        if (pkgJson.exists()) {
            try {
                val json = pkgJson.readText()
                val m = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"").find(json)
                if (m != null) {
                    val f = File(nodeDir, m.groupValues[1])
                    if (f.exists()) return f
                }
            } catch (e: Exception) {
                Log.w(TAG, "package.json parse error: ${e.message}")
            }
        }

        return File(nodeDir, "index.js")
    }
}
