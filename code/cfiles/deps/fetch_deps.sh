#!/data/data/com.termux/files/usr/bin/bash
# Run this FROM Termux (needs network). Vendors libvncclient source only --
# no build artifacts, per the "no already compiled stuff" rule.
#
# Usage: bash fetch_deps.sh
set -e

DEPS_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION="LibVNCServer-0.9.15"   # pinned -- includes fixes for CVE-2026-32853/32854
DEST="$DEPS_DIR/libvncclient"

if [ -d "$DEST" ]; then
    echo "libvncclient already present at $DEST -- skipping clone, just re-cleaning."
else
    echo "Cloning LibVNC/libvncserver @ $VERSION ..."
    git clone --branch "$VERSION" --depth 1 \
        https://github.com/LibVNC/libvncserver.git "$DEST"
fi

# Strip everything that isn't source we actually build: git metadata, CI
# config, tests, docs, the bundled webclients/examples we don't need.
# Runs every time (not just on fresh clone) so re-running this script
# also cleans up an existing checkout.
rm -rf "$DEST/.git" "$DEST/.github" "$DEST/test" "$DEST/tests" "$DEST/docs" \
       "$DEST/.appveyor.yml" "$DEST/webclients" "$DEST/Doxyfile" \
       "$DEST/.clang-format" "$DEST/.gitmodules" "$DEST/examples"

echo "Done. libvncclient source is at: $DEST"
echo "Note the pinned version in your own commit message/README -- there's"
echo "no .git left in there to tell you what you're building later."
