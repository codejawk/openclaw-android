#!/system/bin/sh
# path-patch.sh
#
# Fix 4: Replace hardcoded POSIX shell paths in extracted JS files.
# Run once after extracting the openclaw-bundle from APK assets.
#
# Usage: sh path-patch.sh <openclaw_dir> <node_bin_path>
#
# Example:
#   sh path-patch.sh /data/user/0/com.openclaw.native_app/files/openclaw \
#                    /data/user/0/com.openclaw.native_app/files/node

OPENCLAW_DIR="${1:-/data/user/0/com.openclaw.native_app/files/openclaw}"
NODE_BIN="${2:-/system/bin/sh}"

echo "[path-patch] Patching shell paths in: $OPENCLAW_DIR"
echo "[path-patch] Node binary path: $NODE_BIN"

count=0

# Find all .js files and patch them
find "$OPENCLAW_DIR" -name "*.js" -type f | while read -r file; do
  # Check if file contains any target patterns before touching it
  if grep -qE '(#!/usr/bin/env|/bin/bash|/bin/sh|/usr/bin/node)' "$file" 2>/dev/null; then
    # Use sed to do in-place replacements
    sed -i \
      -e "s|#!/usr/bin/env node|#!${NODE_BIN}|g" \
      -e "s|/usr/bin/env node|${NODE_BIN}|g" \
      -e "s|#!/usr/bin/env|#!/system/bin/sh|g" \
      -e "s|/usr/bin/env |/system/bin/env |g" \
      -e "s|\"/bin/bash\"|\"/system/bin/sh\"|g" \
      -e "s|'/bin/bash'|'/system/bin/sh'|g" \
      -e "s|\"/bin/sh\"|\"/system/bin/sh\"|g" \
      -e "s|'/bin/sh'|'/system/bin/sh'|g" \
      -e "s|/usr/bin/node|${NODE_BIN}|g" \
      -e "s|/usr/local/bin/node|${NODE_BIN}|g" \
      "$file" 2>/dev/null
    echo "[path-patch] Patched: $file"
    count=$((count + 1))
  fi
done

echo "[path-patch] Done. $count files patched."
