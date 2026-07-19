#!/usr/bin/env bash
# BYOS Gallery — reproducible dev toolchain setup for the OrbStack arm64 container.
#
# Installs / verifies the toolchain at /opt/toolchains and the bits that live outside
# it. Safe to re-run: each step checks before doing work. Run it AFTER cloning when the
# environment was reset, or to top up a partial install.
#
#   bash /opt/toolchains/setup.sh          # verify + install what's missing
#   bash /opt/toolchains/setup.sh --check  # only report status, change nothing
#
# Layout it produces:
#   /opt/toolchains/{env.sh, jdk-17, android-sdk, rustup, cargo, aapt2, x86_64-sysroot}
#   ~/.claude/gradle/gradle.properties   (aapt2 override → /opt/toolchains)
# Rust host builds use the SYSTEM gcc (apt build-essential) — no self-bootstrapped cc.
set -euo pipefail

TC=/opt/toolchains
GRADLE_HOME="$HOME/.claude/gradle"
MODE="${1:-}"
SAY()  { printf '%s\n' "$*"; }
OK()   { printf '  OK   %s\n' "$*"; }
MISS() { printf '  MISS %s\n' "$*"; }

need_cmd() { command -v "$1" >/dev/null 2>&1; }

SAY "=== BYOS toolchain setup (target: $TC) ==="

# ── 1. System packages (root/apt — can't be done from inside the container) ──
SAY ""
SAY "[1] System packages (you install these on the host/container image; we can't):"
SYS_PKGS="build-essential unzip curl ca-certificates"
if [ "$MODE" = "--check" ]; then
  for c in gcc cc make unzip curl; do
    if need_cmd "$c"; then OK "system: $c"; else MISS "system: $c"; fi
  done
else
  MISSING=""
  for c in gcc cc make unzip curl; do need_cmd "$c" || MISSING="$MISSING $c"; done
  if [ -n "$MISSING" ]; then
    SAY "  Install with:  sudo apt-get update && sudo apt-get install -y $SYS_PKGS"
    SAY "  (Rust host builds + Gradle resource tools need a C compiler & unzip.)"
  else
    OK "system gcc/make/unzip/curl present"
  fi
fi

# ── 2. Toolchain dir + env.sh ────────────────────────────────────────────────
SAY ""
SAY "[2] Toolchain layout under $TC:"
[ -d "$TC" ] || mkdir -p "$TC"
for d in jdk-17 android-sdk rustup cargo aapt2 x86_64-sysroot; do
  if [ -d "$TC/$d" ]; then OK "$d"; else MISS "$d (install it under $TC/$d)"; fi
done

# env.sh — always (re)write so paths/system-gcc are current.
if [ "$MODE" != "--check" ]; then
  cat > "$TC/env.sh" <<'EOF'
# BYOS Gallery dev toolchain. Usage: . /opt/toolchains/env.sh
TC=/opt/toolchains
export JAVA_HOME=$TC/jdk-17
export ANDROID_HOME=$TC/android-sdk
export ANDROID_NDK_HOME=$TC/android-sdk/ndk/27.2.12479018
export GRADLE_USER_HOME=$HOME/.claude/gradle
export RUSTUP_HOME=$TC/rustup
export CARGO_HOME=$TC/cargo
# Rust host builds use the SYSTEM gcc (apt build-essential); no self-bootstrapped cc.
export CC=/usr/bin/cc
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=/usr/bin/cc
export PATH="$CARGO_HOME/bin:$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
EOF
  OK "wrote $TC/env.sh"
fi

# ── 3. aapt2 workaround (arm64 host has no official aapt2 → x86_64 via Rosetta) ──
SAY ""
SAY "[3] aapt2 workaround (arm64 host):"
if [ -f "$TC/aapt2/aapt2-x86_64" ]; then
  if [ "$MODE" != "--check" ]; then
    cat > "$TC/aapt2/aapt2" <<'EOF'
#!/bin/sh
# x86_64 aapt2 on arm64 Linux (OrbStack/Rosetta): official binary via a private sysroot.
TC=/opt/toolchains
exec "$TC/x86_64-sysroot/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" \
  --library-path "$TC/x86_64-sysroot/lib/x86_64-linux-gnu:$TC/x86_64-sysroot/usr/lib/x86_64-linux-gnu" \
  "$TC/aapt2/aapt2-x86_64" "$@"
EOF
    chmod +x "$TC/aapt2/aapt2"
  fi
  OK "aapt2 wrapper (x86_64 + sysroot)"
  if [ -d "$TC/x86_64-sysroot" ]; then OK "x86_64-sysroot"; else MISS "x86_64-sysroot (aapt2 needs it)"; fi
else
  MISS "aapt2-x86_64 (download the official linux aapt2 jar, extract aapt2, place it here)"
fi

# ── 4. Gradle override (points aapt2 at the wrapper) ─────────────────────────
SAY ""
SAY "[4] Gradle aapt2 override:"
if [ "$MODE" != "--check" ]; then
  mkdir -p "$GRADLE_HOME"
  cat > "$GRADLE_HOME/gradle.properties" <<'EOF'
# arm64 Linux host: Google ships no linux-aarch64 AAPT2; run the official x86_64
# binary via OrbStack Rosetta + a private glibc sysroot.
android.aapt2FromMavenOverride=/opt/toolchains/aapt2/aapt2
EOF
  OK "wrote $GRADLE_HOME/gradle.properties"
else
  grep -q "aapt2FromMavenOverride=/opt/toolchains" "$GRADLE_HOME/gradle.properties" 2>/dev/null \
    && OK "override → /opt/toolchains" || MISS "aapt2 override (run without --check to write it)"
fi

# ── 5. Rust toolchain ─────────────────────────────────────────────────────────
SAY ""
SAY "[5] Rust (rustup + cargo):"
if [ -x "$TC/cargo/bin/cargo" ]; then
  OK "cargo present"
  RUSTUP_HOME="$TC/rustup" CARGO_HOME="$TC/cargo" "$TC/cargo/bin/rustup" toolchain list 2>/dev/null \
    | sed 's/^/       /' || true
else
  MISS "rustup/cargo — install with RUSTUP_HOME=$TC/rustup CARGO_HOME=$TC/cargo rustup"
fi

# ── 6. Verify (build) ─────────────────────────────────────────────────────────
SAY ""
SAY "[6] Verify:"
if [ "$MODE" = "--check" ]; then
  SAY "  Skipping build checks (--check mode)."
else
  if [ -x "$TC/cargo/bin/cargo" ] && [ -d "$TC/jdk-17" ]; then
    SAY "  Running: source env.sh → cargo check + ./gradlew :app:compileDebugKotlin"
    ( . "$TC/env.sh" \
      && (cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 || true) \
      && cd "${GALLERY_REPO:-/workspace/gallery}" \
      && (cd rust && cargo check >/dev/null && echo "       OK cargo check") \
      && ./gradlew :app:compileDebugKotlin --console=plain >/dev/null && echo "       OK :app:compileDebugKotlin" ) \
      || MISS "build verification failed — inspect above"
  else
    MISS "cannot verify (missing cargo or jdk)"
  fi
fi

SAY ""
SAY "=== done. To use:  . /opt/toolchains/env.sh  ==="
