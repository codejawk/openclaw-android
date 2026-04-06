#!/usr/bin/env bash
# build-on-mac.sh
#
# Run this script on your Mac to complete the APK build.
# Steps 1 (NDK), 4 (koffi compile), and 6 (APK) are done here
# because they need the Android SDK which isn't available in the cloud.
#
# Prerequisites:
#   - Android Studio installed (provides SDK + sdkmanager)
#   - Xcode command-line tools (provides clang)
#   - JDK 17 installed (brew install temurin@17 OR use Android Studio's bundled JDK)
#   - ADB working (phone connected with USB Debugging ON)
#
# Usage:
#   chmod +x scripts/build-on-mac.sh
#   bash scripts/build-on-mac.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"

echo "╔══════════════════════════════════════════════════╗"
echo "║     OpenClaw Native — Mac Build Script           ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Auto-detect Android SDK ──────────────────────────────────────
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "/opt/homebrew/opt/android-sdk" \
    "/usr/local/opt/android-sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: Android SDK not found."
  echo "Install Android Studio from https://developer.android.com/studio"
  echo "Or set ANDROID_HOME manually: export ANDROID_HOME=/path/to/sdk"
  exit 1
fi
echo "✓ Android SDK: $ANDROID_HOME"

# ── Auto-detect JDK 17 ──────────────────────────────────────────
if [ -z "${JAVA_HOME:-}" ]; then
  # Try Android Studio's bundled JDK first
  AS_JDK="$HOME/Library/Application Support/Google/AndroidStudio*/jbr"
  for jdk in $AS_JDK; do
    if [ -d "$jdk" ]; then JAVA_HOME="$jdk"; break; fi
  done
  # Try Homebrew temurin
  if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "")
  fi
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "WARNING: JDK 17 not found. Trying brew install..."
  brew install --cask temurin@17 2>/dev/null || true
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "")
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "✓ Java: $(java -version 2>&1 | head -1)"

# ── Step 1: Install NDK ─────────────────────────────────────────
NDK_VERSION="26.1.10909125"
ANDROID_NDK="$ANDROID_HOME/ndk/$NDK_VERSION"

if [ ! -d "$ANDROID_NDK" ]; then
  echo ""
  echo "==> Installing Android NDK $NDK_VERSION..."
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION" || \
  "$ANDROID_HOME/tools/bin/sdkmanager" "ndk;$NDK_VERSION"
fi
echo "✓ NDK: $ANDROID_NDK"

# ── Step 4: Cross-compile koffi for Android ARM64 ───────────────
echo ""
echo "==> Cross-compiling koffi for Android ARM64..."

# Detect host
HOST_TAG="darwin-x86_64"
if [ "$(uname -m)" = "arm64" ]; then HOST_TAG="darwin-arm64"; fi

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TAG"
API_LEVEL=31

export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP_TOOL="$TOOLCHAIN/bin/llvm-strip"
export LDFLAGS="-fuse-ld=lld"

KOFFI_WORK="$SCRIPT_DIR/.koffi-ndk-build"
rm -rf "$KOFFI_WORK"
mkdir -p "$KOFFI_WORK"
cd "$KOFFI_WORK"

npm init -y > /dev/null
npm install koffi --ignore-scripts  # install without running postinstall (avoids pre-build download)

cd "$KOFFI_WORK/node_modules/koffi"

# Build koffi natively for Android target
node src/cnoke/cnoke.js -P . -D src/koffi \
  --arch arm64 \
  --platform android \
  || npx node-gyp rebuild --arch=arm64 -- -Dtarget_arch=arm64

KOFFI_NODE=$(find "$KOFFI_WORK" -name "koffi.node" -type f | head -1)
if [ -z "$KOFFI_NODE" ]; then
  echo "ERROR: koffi.node not found after NDK build"
  echo "Falling back to linux_arm64 pre-built binary"
  KOFFI_NODE="$KOFFI_WORK/node_modules/koffi/build/koffi/linux_arm64/koffi.node"
fi

# Strip debug symbols
"$STRIP_TOOL" --strip-unneeded "$KOFFI_NODE" 2>/dev/null || true

echo "✓ koffi.node built: $KOFFI_NODE ($(du -sh "$KOFFI_NODE" | cut -f1))"

# Copy to all expected locations in the project
cp "$KOFFI_NODE" "$ASSET_DIR/koffi.node"
KOFFI_BUNDLE_DEST="$ASSET_DIR/openclaw-bundle/node_modules/koffi/build/koffi/linux_arm64"
mkdir -p "$KOFFI_BUNDLE_DEST"
cp "$KOFFI_NODE" "$KOFFI_BUNDLE_DEST/koffi.node"
echo "✓ koffi.node installed to APK assets"

cd "$PROJECT_ROOT"

# ── Install required SDK components ─────────────────────────────
echo ""
echo "==> Installing Android SDK components..."
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -f "$SDKMANAGER" ]; then
  SDKMANAGER="$ANDROID_HOME/tools/bin/sdkmanager"
fi

"$SDKMANAGER" \
  "platform-tools" \
  "build-tools;35.0.0" \
  "platforms;android-35" \
  --channel=0 2>/dev/null | grep -v "^\[" || true

# ── Step 6: Build APK ────────────────────────────────────────────
echo ""
echo "==> Building APK..."
export ANDROID_HOME
chmod +x gradlew
./gradlew assembleDebug \
  -Dorg.gradle.jvmargs="-Xmx4g" \
  --no-daemon \
  --stacktrace 2>&1 | tail -30

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  APK_SIZE=$(du -sh "$APK_PATH" | cut -f1)
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  ✅  BUILD SUCCESSFUL!                           ║"
  echo "║     APK: $APK_SIZE                               ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""

  # ── Step 7: Install to phone ─────────────────────────────────
  if command -v adb >/dev/null 2>&1; then
    DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -1 | cut -f1)
    if [ -n "$DEVICE" ]; then
      echo "==> Installing to device: $DEVICE"
      adb -s "$DEVICE" install -r "$APK_PATH"
      echo "✅ Installed! Launch OpenClaw on your Samsung Galaxy."
    else
      echo "No Android device detected via ADB."
      echo "Connect your phone with USB Debugging ON, then run:"
      echo "  adb install -r $APK_PATH"
    fi
  else
    echo "ADB not found. Install platform-tools and run:"
    echo "  adb install -r $APK_PATH"
  fi
else
  echo "❌ BUILD FAILED — check output above"
  exit 1
fi
