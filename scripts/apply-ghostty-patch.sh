#!/usr/bin/env bash
# Apply the small Android/armv7 fixes on top of Ghostty v1.4.0.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH="$ROOT/patches/ghostty-v1.4.0-android.patch"

cd "$ROOT/libghostty-vt"

if git apply --reverse --check "$PATCH" 2>/dev/null; then
    echo "Ghostty patch already applied"
elif git apply --check "$PATCH"; then
    git apply "$PATCH"
    echo "Applied $(basename "$PATCH")"
else
    echo "Patch does not apply cleanly; expected Ghostty v1.4.0" >&2
    exit 1
fi
