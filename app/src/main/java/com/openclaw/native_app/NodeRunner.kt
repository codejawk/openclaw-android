package com.openclaw.native_app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

enum class NodeState { STOPPED, STARTING, RUNNING, CRASHED }

/**
 * NodeRunner
 *
 * Manages the lifecycle of the Node.js subprocess that runs the OpenClaw gateway.
 * - Starts node with the correct binary, environment, and entry point
 * - Streams stdout/stderr to logcat
 * - Auto-restarts on crash via watchdog coroutine
 * - Exposes [state] as a StateFlow for UI observation
 */
@Singleton
class NodeRunner @Inject constructor(
    private val bootstrap: BootstrapManager
) {
    companion object {
        private const val TAG = "NodeRunner"
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_DELAY_MS     = 3_000L
    }

    private val _state = MutableStateFlow(NodeState.STOPPED)
    val state: StateFlow<NodeState> = _state

    private var process: Process? = null
    private var watchdogJob: Job? = null
    private var authToken: String = ""

    /** Start the gateway. Idempotent — no-ops if already running. */
    fun start(token: String) {
        if (_state.value == NodeState.RUNNING || _state.value == NodeState.STARTING) return
        authToken = token
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            runWithWatchdog()
        }
    }

    /** Stop the gateway gracefully. */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        process?.destroy()
        process = null
        _state.value = NodeState.STOPPED
        Log.i(TAG, "Gateway stopped")
    }

    val isRunning: Boolean get() = _state.value == NodeState.RUNNING

    // ──────────────────────────────────────────────────────────────

    private suspend fun runWithWatchdog() {
        var attempts = 0
        while (attempts < MAX_RESTART_ATTEMPTS) {
            _state.value = NodeState.STARTING
            Log.i(TAG, "Starting Node.js gateway (attempt ${attempts + 1})")
            try {
                val exitCode = launchAndWait()
                Log.w(TAG, "Node.js exited with code $exitCode")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Node.js launch failed: ${e.message}")
            }
            _state.value = NodeState.CRASHED
            attempts++
            if (attempts < MAX_RESTART_ATTEMPTS) {
                Log.i(TAG, "Restarting in ${RESTART_DELAY_MS}ms...")
                delay(RESTART_DELAY_MS)
            }
        }
        _state.value = NodeState.CRASHED
        Log.e(TAG, "Gateway failed after $MAX_RESTART_ATTEMPTS attempts — giving up")
    }

    private suspend fun launchAndWait(): Int = withContext(Dispatchers.IO) {
        val nodeBin   = bootstrap.nodeBin
        val nodeDir   = bootstrap.nodeDir
        val env       = bootstrap.buildEnvironment(authToken)

        // Entry point: openclaw-bundle/index.js (or server.js)
        val entryPoint = findEntryPoint(nodeDir)
        Log.i(TAG, "Entry point: ${entryPoint.absolutePath}")

        val cmd = mutableListOf(
            nodeBin.absolutePath,
            "--experimental-specifier-resolution=node",
            entryPoint.absolutePath
        )

        val pb = ProcessBuilder(cmd).apply {
            directory(nodeDir)
            environment().putAll(env)
            redirectErrorStream(false)
        }

        val proc = pb.start()
        process = proc
        _state.value = NodeState.RUNNING
        Log.i(TAG, "Node.js process started")

        // Stream stdout
        CoroutineScope(Dispatchers.IO).launch {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d("$TAG/stdout", line)
                }
            }
        }

        // Stream stderr
        CoroutineScope(Dispatchers.IO).launch {
            BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.w("$TAG/stderr", line)
                }
            }
        }

        proc.waitFor()
    }

    private fun findEntryPoint(nodeDir: File): File {
        // Try common entry points
        val candidates = listOf(
            "index.js",
            "server.js",
            "bin/openclaw.js",
            "bin/gateway.js",
            "dist/index.js",
            "lib/index.js"
        )
        for (candidate in candidates) {
            val f = File(nodeDir, candidate)
            if (f.exists()) return f
        }

        // Parse package.json main field
        val pkgJson = File(nodeDir, "package.json")
        if (pkgJson.exists()) {
            try {
                val json = pkgJson.readText()
                val mainMatch = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"").find(json)
                if (mainMatch != null) {
                    val f = File(nodeDir, mainMatch.groupValues[1])
                    if (f.exists()) return f
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse package.json: ${e.message}")
            }
        }

        // Fallback
        return File(nodeDir, "index.js")
    }
}
