package com.openclaw.native_app

import android.util.Log

/**
 * NodeBridge
 *
 * Kotlin facade over the native JNI bridge (libnode_launcher.so).
 * Loads libnode.so at runtime via dlopen and calls node::Start()
 * in a detached background pthread.
 *
 * Usage:
 *   NodeBridge.startNode(libPath, nodeDir, env, args)
 *   NodeBridge.isRunning  // poll for liveness
 */
object NodeBridge {
    private const val TAG = "NodeBridge"

    init {
        try {
            System.loadLibrary("node_launcher")
            Log.i(TAG, "libnode_launcher.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libnode_launcher.so: ${e.message}")
        }
    }

    /**
     * Start Node.js embedded in the current process.
     *
     * @param libPath  Absolute path to libnode.so (from nativeLibraryDir)
     * @param cwd      Working directory for node (nodeDir / openclaw bundle)
     * @param env      Environment variables map
     * @param args     argv: args[0] should be "node" (program name),
     *                 followed by actual Node.js arguments
     */
    fun startNode(
        libPath: String,
        cwd: String,
        env: Map<String, String>,
        args: List<String>
    ) {
        val envArray  = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val argsArray = args.toTypedArray()
        Log.i(TAG, "startNode: lib=$libPath cwd=$cwd args=$args")
        nativeStartNode(libPath, cwd, envArray, argsArray)
    }

    /** True while node::Start() is executing on its background thread. */
    val isRunning: Boolean
        get() = try { nativeIsRunning() } catch (_: UnsatisfiedLinkError) { false }

    // ── JNI declarations ──────────────────────────────────────────
    private external fun nativeStartNode(
        libPath: String,
        cwd:     String,
        envVars: Array<String>,
        args:    Array<String>
    )

    private external fun nativeIsRunning(): Boolean
}
