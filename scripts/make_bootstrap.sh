#!/usr/bin/env bash
#
# Package a headless qemu-system-ia64 build as an IA64-Emu bootstrap ZIP.
#
# Usage:
#   scripts/make_bootstrap.sh <build-dir> <abi> <arch> <out-dir> \
#       <qemu-src-dir> [firmware-dir]
#
#   abi   arm64-v8a | x86_64
#   arch  aarch64 | x86_64
#
# The result is a ZIP that can be imported from the app's Settings screen or
# used as a release asset for in-app download:
#   bootstrap-<abi>.zip
#   bootstrap-<abi>.zip.sha256
#
# Requirements: bash, coreutils, findutils, ldd/readelf, zip, ldconfig.

set -euo pipefail

BUILD_DIR=${1:?build directory}
ABI=${2:?abi (arm64-v8a or x86_64)}
ARCH=${3:?arch (aarch64 or x86_64)}
OUT_DIR=${4:?output directory}
QEMU_SRC=${5:?QEMU source directory}
FIRMWARE_DIR=${6:-}

case "$ABI/$ARCH" in
    arm64-v8a/aarch64|x86_64/x86_64) ;;
    *)
        echo "unsupported abi/arch combination: $ABI/$ARCH" >&2
        exit 1
        ;;
esac

BUILD_DIR="$(cd "$BUILD_DIR" && pwd)"
QEMU_SRC="$(cd "$QEMU_SRC" && pwd)"
if [ -n "$FIRMWARE_DIR" ]; then
    FIRMWARE_DIR="$(cd "$FIRMWARE_DIR" && pwd)"
fi
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

QEMU_BIN="$BUILD_DIR/qemu-system-ia64"
QEMU_IMG="$BUILD_DIR/qemu-img"
VERSION_FILE="$QEMU_SRC/VERSION"
QEMU_VERSION="$(tr -d '\r\n' < "$VERSION_FILE")"

test -x "$QEMU_BIN" || { echo "missing qemu-system-ia64 in $BUILD_DIR" >&2; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
ROOTFS="$STAGE/rootfs"
mkdir -p \
    "$ROOTFS/usr/local/bin" \
    "$ROOTFS/usr/local/share/qemu/keymaps" \
    "$ROOTFS/etc" \
    "$ROOTFS/root" \
    "$ROOTFS/tmp" \
    "$ROOTFS/dev" \
    "$ROOTFS/proc" \
    "$ROOTFS/sys" \
    "$ROOTFS/lib" \
    "$ROOTFS/lib64" \
    "$ROOTFS/usr/lib" \
    "$ROOTFS/usr/lib64"
chmod 1777 "$ROOTFS/tmp"

install -m 0755 "$QEMU_BIN" "$ROOTFS/usr/local/bin/qemu-system-ia64"
if [ -x "$QEMU_IMG" ]; then
    install -m 0755 "$QEMU_IMG" "$ROOTFS/usr/local/bin/qemu-img"
fi

# ---------------------------------------------------------------- firmware --
FIRMWARE=""
for candidate in \
    "$FIRMWARE_DIR/ia64-firmware.bin" \
    "$BUILD_DIR/roms/ia64-firmware/ia64-firmware.bin" \
    "$QEMU_SRC/roms/ia64-firmware/ia64-firmware.bin"; do
    if [ -n "$candidate" ] && [ -s "$candidate" ]; then
        FIRMWARE=$candidate
        break
    fi
done
if [ -z "$FIRMWARE" ]; then
    echo "ia64-firmware.bin not found (pass its directory as the 6th argument)" >&2
    exit 1
fi
install -m 0644 "$FIRMWARE" "$ROOTFS/usr/local/share/qemu/ia64-firmware.bin"

# ------------------------------------------------------------------- qemu --
copy_bios() {
    local name=$1
    for base in "$QEMU_SRC/pc-bios" "$BUILD_DIR/pc-bios"; do
        if [ -f "$base/$name" ]; then
            install -m 0644 "$base/$name" "$ROOTFS/usr/local/share/qemu/$name"
            return 0
        fi
    done
    return 1
}

for rom in vgabios-ati.bin vgabios-stdvga.bin efi-e1000.rom; do
    copy_bios "$rom" || echo "warning: $rom not found" >&2
done

if [ -d "$QEMU_SRC/pc-bios/keymaps" ]; then
    cp -L "$QEMU_SRC/pc-bios/keymaps/"* "$ROOTFS/usr/local/share/qemu/keymaps/"
fi

# --------------------------------------------------------------- libraries --
collect_libs() {
    local binary=$1
    local output
    output="$(ldd "$binary")"
    if grep -q 'not found' <<<"$output"; then
        echo "unresolved shared libraries for $binary:" >&2
        grep 'not found' <<<"$output" >&2
        exit 1
    fi
    awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^\//) print $i }' <<<"$output"
}

INTERP="$(readelf -l "$QEMU_BIN" \
    | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p' | head -n1)"
if [ -n "$INTERP" ]; then
    mkdir -p "$ROOTFS$(dirname "$INTERP")"
    cp -L "$INTERP" "$ROOTFS$INTERP"
fi

{
    collect_libs "$QEMU_BIN"
    if [ -x "$QEMU_IMG" ]; then collect_libs "$QEMU_IMG"; fi
} | sort -u | while IFS= read -r lib; do
    case "$lib" in
        *linux-vdso.so*|*ld-linux*) continue ;;
    esac
    [ -f "$lib" ] || continue
    dest="$ROOTFS$lib"
    mkdir -p "$(dirname "$dest")"
    cp -L "$lib" "$dest"
done

# Some distributions keep every 64-bit library in /usr/lib64. Make sure the
# loader also searches /lib64 in the packaged root filesystem.
cat > "$ROOTFS/etc/ld.so.conf" <<'EOF'
/usr/local/lib
/usr/lib
/usr/lib64
/lib
/lib64
EOF
if command -v ldconfig >/dev/null 2>&1; then
    ldconfig -r "$ROOTFS" 2>/dev/null || \
        sudo -n ldconfig -r "$ROOTFS" 2>/dev/null || true
fi

# ------------------------------------------------------------ rootfs files --
cat > "$ROOTFS/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/sh
EOF
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost
::1 localhost
EOF
cat > "$ROOTFS/etc/resolv.conf" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

# --------------------------------------------------------------- manifest --
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$STAGE/manifest.json" <<EOF
{
  "format": 1,
  "abi": "$ABI",
  "qemuVersion": "$QEMU_VERSION",
  "builtAt": "$BUILT_AT",
  "entry": "/usr/local/bin/qemu-system-ia64",
  "datadir": "/usr/local/share/qemu",
  "firmware": "/usr/local/share/qemu/ia64-firmware.bin"
}
EOF

# ------------------------------------------------------------------ archive --
mkdir -p "$OUT_DIR"
ZIP_NAME="bootstrap-$ABI.zip"
(
    cd "$STAGE"
    rm -f "$OUT_DIR/$ZIP_NAME"
    zip -q -r -9 "$OUT_DIR/$ZIP_NAME" manifest.json rootfs
)
(
    cd "$OUT_DIR"
    sha256sum "$ZIP_NAME" > "$ZIP_NAME.sha256"
)

echo "created $OUT_DIR/$ZIP_NAME"
du -h "$OUT_DIR/$ZIP_NAME"
