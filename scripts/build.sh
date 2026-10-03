#!/usr/bin/env bash
# Build Tory Access on macOS/Linux *or* inside Termux / proot-distro.
#
#   bash scripts/build.sh            # debug APK
#   bash scripts/build.sh release    # release APK (signed if keystore.properties exists)
#   bash scripts/build.sh test       # JVM unit tests
#
# Always invoked via `bash`, never ./build.sh: on FUSE-backed /sdcard, chmod +x is a
# silent no-op, so nothing in this repo relies on its own exec bit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TASK="assembleDebug"
case "${1:-debug}" in
  debug) TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  test) TASK="testDebugUnitTest" ;;
  *) echo "usage: bash scripts/build.sh [debug|release|test]" >&2; exit 2 ;;
esac

EXTRA=()
TERMUX_AAPT2="/data/data/com.termux/files/usr/bin/aapt2"

if [ -e "$TERMUX_AAPT2" ] || [ -n "${TERMUX_VERSION:-}" ]; then
  # --- Termux / proot-distro -------------------------------------------------
  # Non-login entry points (RUN_COMMAND, `proot-distro login x -- cmd`) don't source
  # dotfiles, so set PATH/JAVA_HOME here instead of trusting ~/.bashrc.
  export PATH="$HOME/.local/bin:/data/data/com.termux/files/usr/bin:$PATH"
  if [ -z "${JAVA_HOME:-}" ]; then
    for c in /root/toolchain/jdk-17* /usr/lib/jvm/java-17-openjdk* /data/data/com.termux/files/usr/lib/jvm/java-17-openjdk; do
      [ -d "$c" ] && JAVA_HOME="$c" && break
    done
  fi
  [ -e "$TERMUX_AAPT2" ] || { echo "Termux aapt2 missing: pkg install aapt2" >&2; exit 1; }
  # Maven's aapt2 is glibc-only; Termux is Bionic. Passed on the command line so the
  # repo's gradle.properties stays portable to macOS/CI.
  EXTRA+=("-Pandroid.aapt2FromMavenOverride=$TERMUX_AAPT2")
  # Phones have little RAM to spare; one worker, no daemon left behind.
  EXTRA+=("--no-daemon" "--max-workers=2" "-Dorg.gradle.jvmargs=-Xmx1536m")
  if [ ! -f local.properties ] && [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
  fi
elif [ "$(uname)" = "Darwin" ]; then
  # --- macOS ------------------------------------------------------------------
  # Gradle 8.14 can't run on JDK 25 (Android Studio's bundled JBR), so prefer 17.
  if [ -z "${JAVA_HOME:-}" ]; then
    for c in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
      [ -n "$c" ] && [ -x "$c/bin/java" ] && JAVA_HOME="$c" && break
    done
  fi
  [ -n "${JAVA_HOME:-}" ] || { echo "Need JDK 17: brew install openjdk@17" >&2; exit 1; }
  if [ ! -f local.properties ]; then
    echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > local.properties
  fi
fi

export JAVA_HOME
echo "› JAVA_HOME=${JAVA_HOME:-<system>}  task=$TASK"
sh gradlew "$TASK" ${EXTRA[@]+"${EXTRA[@]}"}

if [ "$TASK" != "testDebugUnitTest" ]; then
  find app/build/outputs/apk -name '*.apk' -newer gradlew -print 2>/dev/null || true
fi
