#!/usr/bin/env bash
# build-now.sh
# Run this after setup-android-sdk.sh has already installed everything.
# Skips all installation — goes straight to koffi compile + APK build + install.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"

echo "╔══════════════════════════════════════╗"
echo "║  OpenClaw — Build + Install APK      ║"
echo "╚══════════════════════════════════════╝"

# ── Paths (already installed) ─────────────────────────────────────
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "✓ Java:    $(java -version 2>&1 | head -1)"
echo "✓ SDK:     $ANDROID_HOME"
echo "✓ NDK:     $NDK_HOME"

# ── koffi cross-compile for Android ARM64 ────────────────────────
echo ""
echo "==> Cross-compiling koffi for Android ARM64..."

KOFFI_WORK="$SCRIPT_DIR/.koffi-ndk"
rm -rf "$KOFFI_WORK" && mkdir -p "$KOFFI_WORK"

KOFFI_BUILT=false
(
  set +e
  cd "$KOFFI_WORK"
  npm init -y >/dev/null 2>&1
  npm install koffi --ignore-scripts >/dev/null 2>&1

  cd "$KOFFI_WORK/node_modules/koffi"

  CC="$TOOLCHAIN/bin/aarch64-linux-android31-clang" \
  CXX="$TOOLCHAIN/bin/aarch64-linux-android31-clang++" \
  AR="$TOOLCHAIN/bin/llvm-ar" \
  RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
    node src/cnoke/cnoke.js -P . -D src/koffi 2>&1 | tail -8

  # Find built binary (exclude pre-downloaded ones)
  BUILT=$(find "$KOFFI_WORK/node_modules/koffi/build" \
    -name "koffi.node" \
    -newer "$KOFFI_WORK/node_modules/koffi/package.json" \
    2>/dev/null | head -1)

  if [ -n "$BUILT" ] && [ -f "$BUILT" ]; then
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$BUILT" 2>/dev/null || true
    cp "$BUILT" "$ASSET_DIR/koffi.node"
    DEST="$ASSET_DIR/openclaw-bundle/node_modules/koffi/build/koffi/linux_arm64"
    mkdir -p "$DEST"
    cp "$BUILT" "$DEST/koffi.node"
    echo "BUILT_OK"
  fi
) | grep -q "BUILT_OK" && KOFFI_BUILT=true || true

if $KOFFI_BUILT; then
  echo "✓ koffi.node built for Android Bionic — $(du -sh "$ASSET_DIR/koffi.node" | cut -f1)"
else
  echo "  koffi cross-compile skipped — using pre-built linux_arm64 binary (fallback active)"
fi

# ── Bundle openclaw as ZIP (AAPT2 stores .zip uncompressed; .gz gets stripped) ─
echo ""
echo "==> Bundling openclaw-bundle → .zip..."
BUNDLE_DIR="$ASSET_DIR/openclaw-bundle"
BUNDLE_ZIP="$ASSET_DIR/openclaw-bundle.zip"
# Remove old tar artifacts if present
rm -f "$ASSET_DIR/openclaw-bundle.tar.gz" "$ASSET_DIR/openclaw-bundle.tar" 2>/dev/null || true

if [ -d "$BUNDLE_DIR" ]; then
  echo "  Zipping 78k files (this takes ~2-3 min)..."
  cd "$BUNDLE_DIR" && zip -r "$BUNDLE_ZIP" . --quiet && cd "$PROJECT_ROOT"
  echo "  ✓ Bundle zip: $(du -sh "$BUNDLE_ZIP" | cut -f1)"
  rm -rf "$BUNDLE_DIR"
  echo "  ✓ Removed openclaw-bundle/ directory from assets"
elif [ -f "$BUNDLE_ZIP" ]; then
  echo "  ✓ openclaw-bundle.zip already exists ($(du -sh "$BUNDLE_ZIP" | cut -f1))"
else
  echo "  ❌ Neither openclaw-bundle/ nor openclaw-bundle.zip found in assets!"
  exit 1
fi

# ── Gradle wrapper JAR ────────────────────────────────────────────
echo ""
echo "==> Gradle wrapper..."
cd "$PROJECT_ROOT"

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
mkdir -p gradle/wrapper

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "  gradle-wrapper.jar not found — generating..."

  # Method 1: Try downloading directly from Gradle's CDN
  WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
  curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR" 2>/dev/null && \
    echo "  ✓ Downloaded gradle-wrapper.jar from GitHub" || true

  # Method 2: Try using locally installed Gradle to generate it
  if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
    GRADLE_BIN=""
    for g in \
      "/opt/homebrew/bin/gradle" \
      "/usr/local/bin/gradle" \
      "$(which gradle 2>/dev/null || echo '')"; do
      [ -x "$g" ] && { GRADLE_BIN="$g"; break; }
    done

    if [ -n "$GRADLE_BIN" ]; then
      echo "  Using local Gradle: $GRADLE_BIN"
      HOMEBREW_NO_AUTO_UPDATE=1 "$GRADLE_BIN" wrapper \
        --gradle-version 8.9 \
        --distribution-type bin \
        2>&1 | tail -5
    else
      # Method 3: Install Gradle via Homebrew then generate
      echo "  Installing Gradle via Homebrew..."
      HOMEBREW_NO_AUTO_UPDATE=1 brew install gradle 2>&1 | grep -E "Installing|already|Pouring" || true
      HOMEBREW_NO_AUTO_UPDATE=1 gradle wrapper --gradle-version 8.9 --distribution-type bin 2>&1 | tail -3
    fi
  fi

  # Method 4: Download wrapper jar from services.gradle.org
  if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
    echo "  Trying direct download from gradle.org..."
    curl -fsSL \
      "https://services.gradle.org/distributions/gradle-8.9-wrapper.jar" \
      -o "$WRAPPER_JAR" 2>/dev/null || true
  fi

  # Method 5: Download full distro and extract jar
  if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
    echo "  Trying to extract jar from full Gradle distribution..."
    TMP_ZIP="/tmp/gradle-8.9-bin.zip"
    curl -fsSL "https://services.gradle.org/distributions/gradle-8.9-bin.zip" \
      -o "$TMP_ZIP" 2>/dev/null || true
    if [ -f "$TMP_ZIP" ] && [ -s "$TMP_ZIP" ]; then
      unzip -p "$TMP_ZIP" "gradle-8.9/lib/plugins/gradle-wrapper-*.jar" > "$WRAPPER_JAR" 2>/dev/null || \
      unzip -j "$TMP_ZIP" "*/gradle/wrapper/gradle-wrapper.jar" -d gradle/wrapper/ 2>/dev/null || true
      rm -f "$TMP_ZIP"
    fi
  fi
fi

if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
  echo "❌  Cannot find gradle-wrapper.jar. Run:"
  echo "    brew install gradle && gradle wrapper --gradle-version 8.9"
  exit 1
fi

chmod +x gradlew
echo "✓ gradlew ready (wrapper jar: $(du -sh "$WRAPPER_JAR" | cut -f1))"

# ── Build APK ─────────────────────────────────────────────────────
echo ""
echo "==> Cleaning stale Gradle asset cache..."
./gradlew cleanMergeDebugAssets 2>/dev/null || ./gradlew clean 2>/dev/null || true

echo "==> Building APK (first run downloads Gradle ~150MB, ~3-5 min)..."
LOG="/tmp/openclaw-build.log"

set +e
./gradlew assembleDebug \
  -Dorg.gradle.jvmargs="-Xmx4g" \
  -Dorg.gradle.daemon=false \
  -PANDROID_HOME="$ANDROID_HOME" \
  2>&1 | tee "$LOG" | grep --line-buffered -E "BUILD|FAILED|error:|> Task|Compiling|Merging|Package"
BUILD_CODE=$?
set -e

APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ $BUILD_CODE -eq 0 ] && [ -f "$APK" ]; then
  SIZE=$(du -sh "$APK" | cut -f1)
  echo ""
  echo "╔════════════════════════════════════════════╗"
  echo "║  ✅  BUILD SUCCESSFUL  —  APK: $SIZE        ║"
  echo "╚════════════════════════════════════════════╝"
  echo "   $APK"
  echo ""

  # ── Install to phone ────────────────────────────────────────
  ADB="$ANDROID_HOME/platform-tools/adb"
  [ -f "$ADB" ] || ADB="$(which adb 2>/dev/null || echo '')"

  if [ -n "$ADB" ] && [ -f "$ADB" ]; then
    DEVICE=$("$ADB" devices | awk '/\tdevice$/{print $1}' | head -1)
    if [ -n "$DEVICE" ]; then
      echo "==> Installing to Samsung Galaxy ($DEVICE)..."
      "$ADB" -s "$DEVICE" install -r "$APK"
      echo ""
      echo "✅  OpenClaw installed! Open it on your phone:"
      echo "    1. Permissions tab → Grant All"
      echo "    2. Settings → Enter Anthropic API key → Save"
      echo "    3. Home → Start Gateway"
    else
      echo "⚠️  Phone not detected. Connect with USB Debugging ON, then:"
      echo "    $ADB install -r \"$APK\""
    fi
  fi
else
  echo ""
  echo "❌  BUILD FAILED. Errors:"
  grep -E "error:|FAILED|Exception" "$LOG" | head -20
  echo ""
  echo "Full log: $LOG"
  exit 1
fi
