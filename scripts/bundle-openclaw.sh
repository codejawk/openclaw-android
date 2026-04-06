#!/usr/bin/env bash
# bundle-openclaw.sh
#
# Pack the openclaw npm package into the Android APK assets directory.
# This is run on your PC before building the APK.
#
# What it does:
#   1. npm install openclaw (or use a local checkout)
#   2. Copy the installed package into app/src/main/assets/openclaw-bundle/
#   3. Remove dev dependencies and .map files to reduce APK size
#
# Usage:
#   bash scripts/bundle-openclaw.sh
#   bash scripts/bundle-openclaw.sh --local /path/to/openclaw   # use local checkout
#   bash scripts/bundle-openclaw.sh --version 1.2.3              # specific npm version

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"
BUNDLE_DIR="$ASSET_DIR/openclaw-bundle"

LOCAL_PATH=""
NPM_VERSION="latest"

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --local)   LOCAL_PATH="$2"; shift 2 ;;
    --version) NPM_VERSION="$2"; shift 2 ;;
    *) shift ;;
  esac
done

echo "==> Bundling openclaw into APK assets"

# ── Work directory ────────────────────────────────────────────────
WORK_DIR="$SCRIPT_DIR/.openclaw-bundle"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ -n "$LOCAL_PATH" ]; then
  echo "==> Using local openclaw checkout: $LOCAL_PATH"
  npm install "$LOCAL_PATH" --ignore-scripts
else
  echo "==> Installing openclaw@$NPM_VERSION from npm..."
  npm init -y > /dev/null
  npm install "openclaw@$NPM_VERSION" --ignore-scripts --omit=dev || \
  npm install "openclaw" --ignore-scripts --omit=dev
fi

OPENCLAW_INSTALLED="$WORK_DIR/node_modules/openclaw"
if [ ! -d "$OPENCLAW_INSTALLED" ]; then
  echo "ERROR: openclaw package not found after install"
  echo "Make sure 'openclaw' is published on npm, or use --local /path/to/checkout"
  exit 1
fi

echo "==> Copying bundle to assets..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

# Copy entire openclaw package with its node_modules (production only)
cp -r "$OPENCLAW_INSTALLED/." "$BUNDLE_DIR/"

# Also copy all production dependencies
if [ -d "$WORK_DIR/node_modules" ]; then
  mkdir -p "$BUNDLE_DIR/node_modules"
  cp -r "$WORK_DIR/node_modules/." "$BUNDLE_DIR/node_modules/"
  # Remove the openclaw package itself to avoid nesting
  rm -rf "$BUNDLE_DIR/node_modules/openclaw"
fi

# ── Reduce size ────────────────────────────────────────────────────
echo "==> Removing source maps and test files..."
find "$BUNDLE_DIR" -name "*.map" -delete
find "$BUNDLE_DIR" -name "*.md"  -not -name "SOUL.md" -delete
find "$BUNDLE_DIR" -name ".github" -type d -exec rm -rf {} + 2>/dev/null || true
find "$BUNDLE_DIR" -name "test"  -type d -exec rm -rf {} + 2>/dev/null || true
find "$BUNDLE_DIR" -name "tests" -type d -exec rm -rf {} + 2>/dev/null || true
find "$BUNDLE_DIR" -name "__tests__" -type d -exec rm -rf {} + 2>/dev/null || true

# Remove native bindings that won't work (we bundle our own koffi.node)
find "$BUNDLE_DIR" -name "*.node" -not -name "koffi.node" -delete 2>/dev/null || true
find "$BUNDLE_DIR" -name "koffi.node" -delete 2>/dev/null || true

BUNDLE_SIZE=$(du -sh "$BUNDLE_DIR" | cut -f1)
FILE_COUNT=$(find "$BUNDLE_DIR" -type f | wc -l | tr -d ' ')

echo ""
echo "==> Bundle ready!"
echo "    Location: $BUNDLE_DIR"
echo "    Size:     $BUNDLE_SIZE"
echo "    Files:    $FILE_COUNT"
echo ""
echo "Next steps:"
echo "  1. bash scripts/compile-koffi-android.sh   # cross-compile koffi.node"
echo "  2. ./gradlew assembleDebug                  # build APK"
echo "  3. adb install -r app/build/outputs/apk/debug/app-debug.apk"
