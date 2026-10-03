# Tory Access

An Android SSH hub that reaches your VPSes **through Tor**. It bundles a real `tor`
daemon, a Termux-grade terminal, and per-host keys/passwords encrypted in the Android
Keystore. It's built so you can develop and test it from macOS *or* from Termux on the
phone itself, with Shizuku standing in for adb.

## Features

- **Built-in Tor.** tor-android's `libtor.so` runs as a child process, with a bootstrap
  progress UI, "new circuits" (NEWNYM), a circuit viewer that labels Guard/Middle/Exit or
  Rendezvous, and vanilla bridges. You can also point it at an external SOCKS proxy such
  as Orbot.
- **Onion-first routing.** Every host goes through Tor unless you mark it *Direct*.
  `.onion` hosts are always forced through Tor. Tor's extended SOCKS errors are turned
  into readable messages ("descriptor not found", "intro timed out", …).
- **Hub UI.** Grouped host cards with a route badge (onion / Tor / clearnet), live
  session dots, a filter, and quick connect (`user@host.onion:22`).
- **Terminal.** Termux's `terminal-emulator` (xterm-256color, truecolor, alt screen,
  mouse wheel) feeds a custom renderer. You get tabs, Termux's extra-keys rows (sticky
  CTRL/ALT, auto-repeating arrows), pinch-to-zoom, scrollback, paste, and copy of the
  screen or scrollback.
- **Keys.** Generate Ed25519 or RSA-4096 keys, or import OpenSSH/PEM/PuTTY keys
  (passphrases supported). Share the public key, or use **Install public key…** to push
  it over Tor like `ssh-copy-id` and switch the host to key auth.
- **Host-key trust.** TOFU with a SHA256 fingerprint prompt. A changed key shows a
  red warning and the connection is aborted unless you explicitly replace the key.
- **Auth.** Password, key, and keyboard-interactive (2FA prompts appear as dialogs).
- **Background sessions.** A `specialUse` foreground service keeps tor and SSH alive in
  the background, with a "Stop all" action in the notification.
- **Termux handoff.** *Open in Termux* starts Termux's own `ssh` through our SOCKS port
  (`nc -X 5`) using the `RUN_COMMAND` intent. *Copy ssh command* gives you the same line.

## Layout

```
app/src/main/java/com/joshreimer/toryaccess/
  ToryApp.kt              AppGraph: hand-wired singletons
  MainActivity.kt         Compose host, back stack, debug `am start` hooks
  tor/                    TorDaemon (process + torrc), control-port client, bridges
  ssh/                    SshConnector (JSch + SOCKS5), SshTerminalSession, SessionManager,
                          TrustingHostKeyRepository, PromptGate, KeyTools
  terminal/               TerminalCanvasView: renders the emulator, IME/hardware keys
  service/HubService.kt   foreground service + notification
  termux/TermuxBridge.kt  RUN_COMMAND handoff
  data/                   JSON stores (no Room/KSP: keeps Termux builds simple), SecretBox
  ui/                     Hub, Terminal, Host editor, Keys, Tor, Settings
app/src/debug/            AutomationReceiver (debug builds only)
scripts/                  build.sh, device.sh, rish-template.sh
```

The toolchain is pinned to the same versions as `anon-browser`, which are known to build
inside Termux + proot-distro: AGP 8.6.1, Kotlin 2.0.21, Gradle 8.14.3, JDK 17, and
compileSdk 35.

## Build

Run everything with `bash` / `sh`. Never use `./script`: on FUSE-backed `/sdcard`,
`chmod +x` silently does nothing.

**macOS**
```bash
brew install openjdk@17          # Gradle 8.14 can't run on Android Studio's JDK 25
bash scripts/build.sh            # → app/build/outputs/apk/debug/app-debug.apk
bash scripts/build.sh test       # JVM unit tests
bash scripts/device.sh install   # via adb (USB or wireless debugging)
```
For quick device or emulator iterations, `sh gradlew assembleDebug -Ptory.abis=arm64-v8a` builds a
single-ABI APK of about 25 MB instead of 37 MB.

**Termux / proot-distro (on the phone)**
```bash
pkg install aapt2                # Termux's Bionic aapt2; Maven's is glibc-only
proot-distro login debian -b /storage/emulated/0/coding:/root/coding -- \
  bash /root/coding/tory-access/scripts/build.sh
```
`build.sh` detects Termux and passes `-Pandroid.aapt2FromMavenOverride=…` on the command
line, so `gradle.properties` stays portable. It also sets `PATH`/`JAVA_HOME` itself,
because `proot-distro login … -- cmd` doesn't source your dotfiles. Remember the
`-b` bind mount: without it, the project path doesn't exist inside the container.

**Release signing.** Put a `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) at `../tory-access-keys/`, or set
`TORY_ACCESS_KEYSTORE_PROPERTIES`. It never lives in the repo.

## Testing on a device (adb or Shizuku)

`scripts/device.sh` uses adb when a device is attached. Otherwise it uses Shizuku
through `scripts/rish.local.sh`: copy `scripts/rish-template.sh` there and set your
`RISH_APPLICATION_ID` and the path to `rish_shizuku.dex`. That file is git-ignored. The
template calls `app_process64` directly because the stock `rish` script breaks inside
nested shells.

```bash
bash scripts/device.sh install                 # streams APK → /data/local/tmp, pm install,
                                               # then checks lastUpdateTime really moved
bash scripts/device.sh launch
bash scripts/device.sh add-host vps abcd…xyz.onion 22 root 'hunter2'
bash scripts/device.sh connect vps             # opens a tab (am start → foreground-safe)
bash scripts/device.sh dump                    # {"tor":{"phase":"READY",…},"prompt":"hostkey …"}
bash scripts/device.sh answer yes              # accept the host-key dialog
bash scripts/device.sh send 'uname -a'
bash scripts/device.sh screen                  # terminal contents as text
bash scripts/device.sh ui                      # uiautomator: Compose testTags → resource-ids
bash scripts/device.sh shot /sdcard/Download/tory.png
bash scripts/device.sh logs
```

How the automation hooks are built (debug builds only):

| Hook | Mechanism | Why |
|---|---|---|
| `tory.DUMP / SEND / SCREEN / ANSWER / ADD_HOST / REMOVE_HOST / NEWNYM` | `am broadcast -n …/.debug.AutomationReceiver` | Results come back in `am broadcast`'s `data=` line, so no screenshots are needed. The receiver requires `android.permission.DUMP`: the shell user (adb, Shizuku) has it, but other apps can't type into your sessions. |
| `--es tory.cmd start-tor\|stop-tor\|restart-tor\|hub\|terminal\|close-all`, `--es tory.connect <label>` | `am start` on MainActivity | Android 12+ blocks starting foreground services from a broadcast, but `am start` brings the app to the front first. |
| `testTag`s (`quick_connect`, `host_<label>`, `tor_start`, `hostkey_accept`, `xkey_ESC`, …) | `testTagsAsResourceId = true` | Lets `uiautomator dump` find them. The terminal is drawn on a canvas, so use `screen` for its text. |

Gotchas, carried over from the Shizuku/Termux skills:
- Shizuku runs as uid 2000, not root. Use `run-as com.joshreimer.toryaccess.debug` to read
  the debug build's private files (torrc, logs).
- If rish calls start timing out, Shizuku's service has gone stale. Reopen the Shizuku app.
- The screen locks between commands. `input keyevent KEYCODE_WAKEUP` handles a simple
  sleep, but a secure lockscreen needs a person to unlock it.

## Using it with Termux's own ssh

In Termux, once:
```bash
pkg install openssh netcat-openbsd
echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings
```
Then grant the RUN_COMMAND permission in Settings → Termux. Each host's ⋮ menu now has
**Open in Termux**. Any Termux tool can also use the SOCKS port shown on the Tor screen
(default 9160).

## Security notes

- Passwords, private keys and passphrases are AES-256-GCM encrypted with a
  non-exportable Android Keystore key. `allowBackup=false`.
- torrc: `SocksPolicy accept 127.0.0.1` / `reject *`, `IsolateDestAddr` (one circuit
  per destination), and `__OwningControllerProcess` so tor exits if the app dies and
  never squats its ports.
- A remote OSC 52 *paste* request is ignored, so a server can't read your clipboard.
  OSC 52 *copy* is allowed.
- `usesCleartextTraffic` only covers the loopback hop to tor's SOCKS port.

## Not yet

- Pluggable-transport bridges (obfs4/snowflake/webtunnel). Use Orbot in External mode for
  now. IPtProxy is the library to add, as in anon-browser.
- Port forwarding, SFTP, text selection by drag, and biometric unlock.
