#!/usr/bin/env bash
# Shizuku shell bridge for agents running inside Termux / proot-distro.
#
# Copy to scripts/rish.local.sh (git-ignored — it's device-specific plumbing) and set
# the two paths below. Usage: bash scripts/rish.local.sh '<command>'
#
# Why not Shizuku's own `rish` script? Its shebang execs /system/bin/sh directly (often
# blocked from a nested/seccomp'd shell) and it needs its own exec bit (a silent no-op on
# FUSE storage). Calling app_process64 ourselves sidesteps both.
#
# Runs as uid 2000 (shell), NOT root: pm/am/dumpsys/input/uiautomator/screencap work;
# other apps' private dirs don't (use `run-as com.joshreimer.toryaccess.debug` for ours).
# If calls start timing out, Shizuku's service went stale — reopen the Shizuku app.
set -euo pipefail

# The package Shizuku actually authorized for rish on *your* device (often com.termux).
RISH_APPLICATION_ID="${RISH_APPLICATION_ID:-com.termux}"
# Exported from the Shizuku app: "Use Shizuku in terminal apps" → export files.
RISH_DEX="${RISH_DEX:-$HOME/rish_shizuku.dex}"

[ -f "$RISH_DEX" ] || { echo "rish dex not found at $RISH_DEX" >&2; exit 1; }

RISH_APPLICATION_ID="$RISH_APPLICATION_ID" exec /system/bin/app_process64 \
  -Djava.class.path="$RISH_DEX" \
  /system/bin --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader \
  -c "$1"
