#!/usr/bin/env bash
# setup-android-sdk.sh — OpenClaw Native build script for Mac (no Android Studio needed)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"

echo "╔══════════════════════════════════════════════════╗"
echo "║   OpenClaw — Android SDK Setup + Build           ║"
echo "╚══════════════════════════════════════════════════╝"

# ── Homebrew ─────────────────────────────────────────────────────
command -v brew >/dev/null 2>&1 || { echo "ERROR: Homebrew not installed."; exit 1; }
echo "✓ Homebrew: $(brew --version | head -1)"

# ── Java 17 ──────────────────────────────────────────────────────
echo ""
echo "==> Checking Java 17..."
JAVA17=""
for c in \
  "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
  "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home" \
  "$(brew --prefix 2>/dev/null)/opt/openjdk@17" \
  "/opt/homebrew/opt/openjdk@17" \
  "/usr/local/opt/openjdk@17"; do
  [ -d "$c" ] && { JAVA17="$c"; break; }
done
[ -z "$JAVA17" ] && JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "")
if [ -z "$JAVA17" ]; then
  echo "  Installing temurin@17..."
  brew install --cask temurin@17
  JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "")
fi
[ -z "$JAVA17" ] && { echo "ERROR: JDK 17 not found"; exit 1; }
export JAVA_HOME="$JAVA17"
export PATH="$JAVA_HOME/bin:$PATH"
echo "✓ Java: $(java -version 2>&1 | head -1)"

# ── Android SDK ──────────────────────────────────────────────────
echo ""
echo "==> Checking Android SDK..."
SDK_HOME=""
for c in \
  "$HOME/Library/Android/sdk" \
  "/opt/homebrew/share/android-commandlinetools" \
  "$(brew --prefix 2>/dev/null)/share/android-commandlinetools" \
  "$HOME/android-sdk"; do
  if [ -d "$c/cmdline-tools" ] || [ -d "$c/platform-tools" ] || [ -d "$c/ndk" ]; then
    SDK_HOME="$c"; break
  fi
done
if [ -z "$SDK_HOME" ]; then
  echo "  Installing android-commandlinetools via Homebrew..."
  brew install --cask android-commandlinetools
  SDK_HOME="$(brew --prefix)/share/android-commandlinetools"
fi
export ANDROID_HOME="$SDK_HOME"
echo "✓ ANDROID_HOME: $ANDROID_HOME"

# ── sdkmanager ───────────────────────────────────────────────────
SDKMANAGER=""
for c in \
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" \
  "$(brew --prefix)/bin/sdkmanager"; do
  [ -f "$c" ] && { SDKMANAGER="$c"; break; }
done
[ -z "$SDKMANAGER" ] && { echo "ERROR: sdkmanager not found"; exit 1; }
echo "✓ sdkmanager found"

# ── SDK components + NDK ─────────────────────────────────────────
echo ""
echo "==> Installing SDK components + NDK 26 (may take 5-10 min)..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "build-tools;35.0.0" "platforms;android-35" "ndk;26.1.10909125" 2>&1 \
  | grep -E "Downloading|Installing|done" || true

NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
[ -d "$NDK_HOME" ] || { echo "ERROR: NDK not installed at $NDK_HOME"; exit 1; }
echo "✓ NDK: $NDK_HOME"

export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# ── koffi cross-compile (optional — graceful fallback if it fails) ──
echo ""
echo "==> Cross-compiling koffi for Android ARM64 (Bionic)..."

HOST_ARCH="$(uname -m)"
HOST_TAG="darwin-x86_64"
[ "$HOST_ARCH" = "arm64" ] && HOST_TAG="darwin-arm64"

TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
API=31

KOFFI_CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
KOFFI_CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
STRIP_BIN="$TOOLCHAIN/bin/llvm-strip"

echo "  Toolchain: $TOOLCHAIN"

KOFFI_WORK="$SCRIPT_DIR/.koffi-ndk"
rm -rf "$KOFFI_WORK" && mkdir -p "$KOFFI_WORK"

# Build koffi — all errors are caught, never abort the overall script
KOFFI_SUCCESS=0
(
  set +e  # errors inside this subshell won't kill the parent
  cd "$KOFFI_WORK"
  npm init -y >/dev/null 2>&1
  npm install koffi --ignore-scripts 2>&1 | tail -3

  KOFFI_SRC="$KOFFI_WORK/node_modules/koffi"
  [ -d "$KOFFI_SRC" ] || exit 1
  cd "$KOFFI_SRC"

  # Cross-compile using node-gyp with NDK clang
  export npm_config_CC="$KOFFI_CC"
  export npm_config_CXX="$KOFFI_CXX"
  export npm_config_AR="$TOOLCHAIN/bin/llvm-ar"
  export npm_config_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export npm_config_target_arch="arm64"

  # Run koffi's own build script (cnoke) pointing directly at the compiler
  CC="$KOFFI_CC" CXX="$KOFFI_CXX" \
    node src/cnoke/cnoke.js -P . -D src/koffi 2>&1 | tail -10

  # Find the built binary
  BUILT=$(find "$KOFFI_WORK" -name "koffi.node" -newer "$KOFFI_SRC/package.json" 2>/dev/null | head -1)
  [ -z "$BUILT" ] && BUILT=$(find "$KOFFI_WORK" -name "koffi.node" 2>/dev/null | grep -v "linux_arm64" | head -1)
  [ -f "$BUILT" ] || exit 1

  "$STRIP_BIN" --strip-unneeded "$BUILT" 2>/dev/null || true

  # Copy to both asset locations
  cp "$BUILT" "$ASSET_DIR/koffi.node"
  DEST="$ASSET_DIR/openclaw-bundle/node_modules/koffi/build/koffi/linux_arm64"
  mkdir -p "$DEST"
  cp "$BUILT" "$DEST/koffi.node"

  echo "SUCCESS"
) && KOFFI_SUCCESS=1 || true

if [ "$KOFFI_SUCCESS" = "1" ]; then
  echo "✓ koffi.node cross-compiled for Android Bionic — $(du -sh "$ASSET_DIR/koffi.node" | cut -f1)"
else
  echo "  koffi cross-compile skipped (pre-built linux_arm64 binary will be used)"
  echo "  The app will still work — bionic-bypass.js handles graceful fallback."
fi

cd "$PROJECT_ROOT"

# ── Gradle wrapper ───────────────────────────────────────────────
echo ""
echo "==> Setting up Gradle wrapper..."
WRAPPER_JAR="$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "  Installing Gradle via Homebrew..."
  brew install gradle 2>&1 | grep -E "Installing|installed|already" || true
  cd "$PROJECT_ROOT"
  gradle wrapper --gradle-version 8.9 --distribution-type bin 2>&1 | tail -5
  echo "✓ Gradle wrapper generated"
else
  echo "✓ Gradle wrapper already present"
fi
cd "$PROJECT_ROOT"
chmod +x gradlew

# ── Build APK ────────────────────────────────────────────────────
echo ""
echo "==> Building OpenClaw APK (first build downloads Gradle + deps, ~3-5 min)..."
LOG="/tmp/openclaw-build.log"

set +e
./gradlew assembleDebug \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=512m" \
  -Dorg.gradle.daemon=false \
  2>&1 | tee "$LOG" | grep -E "BUILD|FAILED|error:|warning:|Compiling|Merging|Package"
BUILD_EXIT=$?
set -e

APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK" ] && [ $BUILD_EXIT -eq 0 ]; then
  SIZE=$(du -sh "$APK" | cut -f1)
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  ✅  BUILD SUCCESSFUL  —  APK size: $SIZE        ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo "   APK: $APK"
  echo ""

  # ── Install to phone ─────────────────────────────────────────
  ADB="$ANDROID_HOME/platform-tools/adb"
  [ ! -f "$ADB" ] && ADB="$(which adb 2>/dev/null || echo "")"

  if [ -n "$ADB" ] && [ -f "$ADB" ]; then
    DEVICE=$("$ADB" devices 2>/dev/null | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
    if [ -n "$DEVICE" ]; then
      echo "==> Installing to Samsung Galaxy ($DEVICE)..."
      "$ADB" -s "$DEVICE" install -r "$APK"
      echo ""
      echo "✅  OpenClaw is installed on your phone!"
      echo "    Open it → Permissions → Grant All"
      echo "    Settings → Enter Anthropic API key → Save"
      echo "    Home → Start Gateway"
    else
      echo "⚠️  No device via ADB. Connect phone with USB Debugging ON, then:"
      echo "    $ADB install -r \"$APK\""
    fi
  fi
else
  echo ""
  echo "❌  BUILD FAILED. Checking errors..."
  echo ""
  grep -E "error:|FAILED|Exception" "$LOG" | head -30
  echo ""
  echo "Full log: $LOG"
  echo "Fix any errors above and re-run: bash scripts/setup-android-sdk.sh"
  exit 1
fi
