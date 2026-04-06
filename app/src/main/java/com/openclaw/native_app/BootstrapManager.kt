package com.openclaw.native_app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BootstrapManager — first-launch setup.
 *
 * Assets bundled in APK:
 *   - openclaw-bundle.zip  → filesDir/openclaw/  (ZipInputStream, avoids AAPT2 .gz stripping)
 *   - koffi.node           → two locations for koffi resolution
 *   - bionic-bypass.js     → filesDir/
 *   - path-patch.sh        → filesDir/
 *   Node binary lives in nativeLibraryDir as libnode.so (executable on Android 10+)
 */
@Singleton
class BootstrapManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val PREF_BOOTSTRAP_VERSION = "bootstrap_version"
        private const val CURRENT_VERSION = 5 // bumped: zip extraction + nativeLibraryDir node
        const val GATEWAY_PORT = 18789
    }

    val filesDir: File get() = context.filesDir
    val tmpDir:   File get() = File(filesDir, "tmp")
    val homeDir:  File get() = File(filesDir, "home")
    val nodeDir:  File get() = File(filesDir, "openclaw")
    // Node binary is in nativeLibraryDir — Android installs it as executable automatically
    val nodeBin:  File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
    val koffiDir: File get() = File(filesDir, "node_modules/koffi/build/Release")
    val configDir:File get() = File(homeDir, ".openclaw")

    val isBootstrapped: Boolean
        get() {
            val prefs = context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
            return prefs.getInt(PREF_BOOTSTRAP_VERSION, 0) >= CURRENT_VERSION
        }

    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Bootstrap starting (bootstrapped=$isBootstrapped)")
        if (isBootstrapped) return@withContext

        listOf(tmpDir, homeDir, nodeDir, koffiDir, configDir,
            File(filesDir, "captures")).forEach { it.mkdirs() }

        // Node binary is installed by Android into nativeLibraryDir — no extraction needed
        Log.i(TAG, "Node: ${nodeBin.absolutePath} exists=${nodeBin.exists()}")

        // koffi — extract to two locations
        extractAsset("koffi.node", File(koffiDir, "koffi.node"))
        val koffiNativeDir = File(nodeDir, "node_modules/koffi/build/koffi/linux_arm64")
        koffiNativeDir.mkdirs()
        extractAsset("koffi.node", File(koffiNativeDir, "koffi.node"))
        Log.i(TAG, "koffi.node extracted")

        // bionic-bypass.js
        extractAsset("bionic-bypass.js", File(filesDir, "bionic-bypass.js"))

        // path-patch.sh
        val patchScript = File(filesDir, "path-patch.sh")
        extractAsset("path-patch.sh", patchScript)
        patchScript.setExecutable(true, false)

        // openclaw bundle — ZIP avoids AAPT2's .gz stripping behaviour
        Log.i(TAG, "Extracting openclaw-bundle.zip to ${nodeDir.absolutePath}...")
        extractZip("openclaw-bundle.zip", nodeDir)
        Log.i(TAG, "Bundle extracted")

        patchShellPaths(nodeDir)
        writeDefaultConfig()

        context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_BOOTSTRAP_VERSION, CURRENT_VERSION).apply()

        Log.i(TAG, "Bootstrap complete")
    }

    fun buildEnvironment(authToken: String): Map<String, String> = mutableMapOf(
        "TMPDIR"          to tmpDir.absolutePath,
        "HOME"            to homeDir.absolutePath,
        "XDG_RUNTIME_DIR" to tmpDir.absolutePath,
        "NODE_OPTIONS"    to "--require ${filesDir.absolutePath}/bionic-bypass.js",
        "PATH"            to "/system/bin:/system/xbin:${filesDir.absolutePath}",
        "SHELL"           to "/system/bin/sh",
        "OPENCLAW_HOME"   to homeDir.absolutePath,
        "OPENCLAW_PORT"   to GATEWAY_PORT.toString(),
        "OPENCLAW_TOKEN"  to authToken,
        "OPENCLAW_BIND"   to "127.0.0.1",
        "ANDROID_DATA"    to filesDir.absolutePath,
        "LD_LIBRARY_PATH" to koffiDir.absolutePath
    )

    // ── helpers ───────────────────────────────────────────────────

    private fun extractAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { i ->
            FileOutputStream(dest).use { o -> i.copyTo(o) }
        }
    }

    /**
     * Extract a ZIP asset directly from AssetManager using ZipInputStream.
     * No temp file needed — works for any size because AAPT2 stores .zip uncompressed.
     */
    private fun extractZip(assetName: String, destDir: File) {
        destDir.mkdirs()
        var count = 0
        context.assets.open(assetName).buffered().use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out) }
                        count++
                        if (count % 5000 == 0) Log.i(TAG, "Extracted $count files...")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        Log.i(TAG, "ZIP extraction complete: $count files")
    }

    fun patchShellPaths(dir: File) {
        val nodeAbsPath = nodeBin.absolutePath
        val replacements = mapOf(
            "#!/usr/bin/env node" to "#!/system/bin/sh",
            "/usr/bin/env node"   to "/system/bin/sh",
            "#!/usr/bin/env"      to "#!/system/bin/sh",
            "/usr/bin/env"        to "/system/bin/env",
            "/usr/bin/node"       to nodeAbsPath,
            "/usr/local/bin/node" to nodeAbsPath,
            "\"/bin/bash\""       to "\"/system/bin/sh\"",
            "'/bin/bash'"         to "'/system/bin/sh'",
            "\"/bin/sh\""         to "\"/system/bin/sh\"",
            "'/bin/sh'"           to "'/system/bin/sh'"
        )
        dir.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach { file ->
            try {
                var text = file.readText(); var changed = false
                for ((old, new) in replacements) {
                    if (text.contains(old)) { text = text.replace(old, new); changed = true }
                }
                if (changed) file.writeText(text)
            } catch (e: Exception) { Log.w(TAG, "Patch skipped ${file.name}: ${e.message}") }
        }
    }

    private fun writeDefaultConfig() {
        configDir.mkdirs()
        val f = File(configDir, "config.yaml")
        if (!f.exists()) f.writeText("""
            gateway:
              bind: "127.0.0.1"
              port: $GATEWAY_PORT
              mdns:
                enabled: false
              cors:
                enabled: true
                origins:
                  - "http://localhost:$GATEWAY_PORT"
        """.trimIndent())
    }
}
