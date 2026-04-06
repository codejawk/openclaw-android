#!/usr/bin/env bash
# compile-koffi-android.sh
#
# Cross-compile koffi native addon for Android ARM64 (aarch64-linux-android31)
# using the Android NDK Clang toolchain.
#
# Prerequisites (run on Linux/macOS PC):
#   - Android SDK with NDK r26+ installed (sdk manager: ndk;26.1.10909125)
#   - Node.js 22 installed on host
#   - npm / node-gyp available
#
# Usage:
#   export ANDROID_SDK_ROOT=$HOME/Android/Sdk   # or wherever your SDK lives
#   bash scripts/compile-koffi-android.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"

# ── Android NDK ──────────────────────────────────────────────────
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK_VERSION="26.1.10909125"
ANDROID_NDK="${ANDROID_NDK:-$ANDROID_SDK_ROOT/ndk/$NDK_VERSION}"

if [ ! -d "$ANDROID_NDK" ]; then
  echo "ERROR: Android NDK not found at $ANDROID_NDK"
  echo "  Install via: sdkmanager 'ndk;$NDK_VERSION'"
  exit 1
fi

# Detect host OS
HOST_TAG="linux-x86_64"
if [[ "$OSTYPE" == "darwin"* ]]; then
  HOST_TAG="darwin-x86_64"
  if [[ "$(uname -m)" == "arm64" ]]; then
    HOST_TAG="darwin-arm64"
  fi
fi

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TAG"
API_LEVEL=31

export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export LDFLAGS="-fuse-ld=lld"

echo "==> NDK: $ANDROID_NDK"
echo "==> CC:  $CC"
echo "==> CXX: $CXX"

# ── Prepare koffi build ──────────────────────────────────────────
WORK_DIR="$SCRIPT_DIR/.koffi-build"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

echo "==> Installing koffi npm package..."
npm init -y > /dev/null
npm install koffi

cd "$WORK_DIR/node_modules/koffi"

echo "==> Cross-compiling koffi for android-arm64..."
npm run build -- \
  --target_arch=arm64 \
  --target_platform=linux \
  --target_libc=bionic \
  --CC="$CC" \
  --CXX="$CXX" \
  --AR="$AR" \
  --LDFLAGS="$LDFLAGS" || \
npx node-gyp rebuild \
  --arch=arm64 \
  --target_platform=linux \
  -- \
  -Dtarget_arch=arm64

KOFFI_NODE="$WORK_DIR/node_modules/koffi/build/Release/koffi.node"
if [ ! -f "$KOFFI_NODE" ]; then
  # Try alternate output path
  KOFFI_NODE=$(find "$WORK_DIR" -name "koffi.node" | head -1)
fi

if [ ! -f "$KOFFI_NODE" ]; then
  echo "ERROR: koffi.node not found after build"
  exit 1
fi

# Strip debug symbols to reduce size
"$STRIP" --strip-unneeded "$KOFFI_NODE" 2>/dev/null || true

echo "==> Copying koffi.node to assets..."
cp "$KOFFI_NODE" "$ASSET_DIR/koffi.node"
echo "==> Done: $ASSET_DIR/koffi.node ($(du -sh "$ASSET_DIR/koffi.node" | cut -f1))"
