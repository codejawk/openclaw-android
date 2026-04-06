#!/usr/bin/env bash
# get-node-binary.sh
#
# Download a pre-built Node.js 22 binary for Android ARM64 and place it
# in app/src/main/assets/node-arm64
#
# The binary comes from the unofficial nodejs-mobile project which builds
# Node.js for Android without Termux.
#
# Usage: bash scripts/get-node-binary.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets"
OUTPUT="$ASSET_DIR/node-arm64"

# nodejs-mobile release (adjust version as needed)
NODE_MOBILE_VERSION="0.3.3"
NODE_VERSION="22.6.0"

# URL for the arm64 Android binary from nodejs-mobile-react-native releases
# or build from source: https://github.com/nodejs-mobile/nodejs-mobile
DOWNLOAD_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v${NODE_MOBILE_VERSION}/nodejs-mobile-v${NODE_MOBILE_VERSION}-android-arm64.tar.gz"

TMP_DIR="$SCRIPT_DIR/.node-download"
mkdir -p "$TMP_DIR"

echo "==> Downloading nodejs-mobile v$NODE_MOBILE_VERSION (Android ARM64)..."
curl -L "$DOWNLOAD_URL" -o "$TMP_DIR/node.tar.gz" --progress-bar

echo "==> Extracting..."
cd "$TMP_DIR"
tar xzf node.tar.gz

# Find the node binary in the extracted archive
NODE_BIN=$(find "$TMP_DIR" -name "node" -type f -executable | head -1)
if [ -z "$NODE_BIN" ]; then
  # Try alternate structure
  NODE_BIN=$(find "$TMP_DIR" -name "libnode.so" -type f | head -1)
fi

if [ -z "$NODE_BIN" ]; then
  echo "ERROR: Node binary not found in downloaded archive"
  echo "Please manually download a Node.js ARM64 Android binary and place it at:"
  echo "  $OUTPUT"
  echo ""
  echo "Sources:"
  echo "  - https://github.com/nodejs-mobile/nodejs-mobile/releases"
  echo "  - https://github.com/nicholasgasior/nodejs-android"
  exit 1
fi

echo "==> Copying node binary to assets..."
cp "$NODE_BIN" "$OUTPUT"
chmod +x "$OUTPUT"

SIZE=$(du -sh "$OUTPUT" | cut -f1)
echo "==> Done: $OUTPUT ($SIZE)"

# Cleanup
rm -rf "$TMP_DIR"
