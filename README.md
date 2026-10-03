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

## Server setup: SSH as an onion service

You don't have to change anything on your VPS. Tory Access can reach any normal SSH server
by IP or domain through Tor. Giving the server its own `.onion` address is the most private
option, though:
- The connection never leaves the Tor network. There's no exit relay, so nobody along the
  way sees a connection to your server's IP.
- You can close port 22 to the internet entirely, which also ends the background noise of
  bots guessing passwords.
- It keeps working when the server has no public IP, or sits behind NAT or CGNAT.

The steps below work on any Linux server running systemd. Run them as root, or with `sudo`.

### 1. Install Tor

```bash
# Debian / Ubuntu
sudo apt update && sudo apt install -y tor

# Fedora / RHEL / Rocky / Alma (EPEL needed on RHEL-likes)
sudo dnf install -y tor

# Arch
sudo pacman -S tor
```

Debian and Ubuntu ship an older Tor. That's fine for this setup, but if you want the latest
stable release, use the
[Tor Project's own repository](https://support.torproject.org/apt/tor-deb-repo/).

### 2. Publish sshd as an onion service

Append this to `/etc/tor/torrc`:

```bash
sudo tee -a /etc/tor/torrc >/dev/null <<'EOF'

## SSH onion service for Tory Access
HiddenServiceDir /var/lib/tor/ssh/
HiddenServicePort 22 127.0.0.1:22
EOF
```

`HiddenServicePort 22 127.0.0.1:22` means "port 22 on the onion address goes to sshd on this
machine's loopback". If sshd runs on another port, change the second number, for example
`HiddenServicePort 22 127.0.0.1:2222`.

Restart Tor and enable it at boot:

```bash
sudo systemctl enable --now tor
sudo systemctl restart tor
```

### 3. Get the onion address

```bash
sudo cat /var/lib/tor/ssh/hostname
# e.g. abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvw.onion
```

The first time, Tor takes a minute or two to publish the address. Check that it's up:

```bash
sudo journalctl -u tor -u tor@default --since "5 min ago" | grep -iE "bootstrapped 100|warn|err"
```

### 4. Note the server's host-key fingerprint

The first time the app connects, it asks you to confirm the server's fingerprint. Get the
real values now, over a channel you already trust, such as your current SSH session or the
provider's web console:

```bash
for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$f"; done
```

Only accept the prompt in the app if its `SHA256:…` matches one of these lines.

### 5. Add it in Tory Access

**Add host**, then fill in:
- **Host:** the `.onion` address. The app always routes `.onion` hosts through Tor.
- **Port:** `22`, the onion-side port from `HiddenServicePort`.
- **Username:** your login.
- **Auth:** a password for now.

Connect. The first connection to a cold onion service can take 10–30 seconds.

Then switch to key login: **Keys → New key → Ed25519**, and on the host card use
**⋮ → Install public key…**. This adds the key to `~/.ssh/authorized_keys` over Tor and
switches the host to key login.

### 6. Optional: close the public SSH port

Only do this after you've logged in over the onion address *with a key*, and while you still
have another way in, such as your provider's web or serial console. If the onion stops
working, the console is your only way back.

First, disable password logins. Edit `/etc/ssh/sshd_config`, or create a file in
`/etc/ssh/sshd_config.d/`:

```
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
```

Then make sshd listen only on loopback, which Tor still reaches:

```
ListenAddress 127.0.0.1
```

Apply and check:

```bash
# sshd -t validates the config first; nothing restarts if it fails
sudo sshd -t && { sudo systemctl restart ssh 2>/dev/null || sudo systemctl restart sshd; }
sudo ss -tlnp | grep ':22 '     # should show 127.0.0.1:22 only
```

On **Ubuntu 22.10 and later**, sshd is started by `ssh.socket`, which ignores `ListenAddress`.
Either run `sudo systemctl disable --now ssh.socket && sudo systemctl enable --now ssh.service`
and then restart `ssh` as above, or use a firewall instead. With `ufw`, run
`sudo ufw delete allow OpenSSH` (or `sudo ufw delete allow 22/tcp`), keeping ufw's default
deny for incoming traffic.

From now on, `ssh you@<server-ip>` from the internet is refused, while Tory Access keeps
working through the onion address.

### Keep the onion keys safe

`/var/lib/tor/ssh/` holds the onion service's private key (`hs_ed25519_secret_key`):
- **Back it up** if you want to keep the same address after a reinstall. Restore the whole
  directory, owned by Tor's user (`debian-tor` on Debian/Ubuntu, `tor` or `toranon`
  elsewhere) with mode `700`.
- **Keep it secret.** Anyone who has that key can run a server at your address. Your SSH
  host-key check would still catch that, which is one more reason to compare fingerprints in
  step 4.

### Troubleshooting

| What the app says | Likely cause |
|---|---|
| "Onion service descriptor not found" | Tor on the server isn't running, hasn't published the address yet (wait 1–2 min after a restart), or the address is mistyped. |
| "Couldn't reach the onion service's introduction points" / "introduction timed out" | The server's Tor is unhealthy or its clock is wrong. Check `journalctl -u tor@default` and run `timedatectl`. |
| "Connection refused by the server" | Tor is up but nothing is listening at the `HiddenServicePort` target. Check `ss -tlnp` and the port in torrc. |
| "Onion service requires client authorization" | You enabled `authorized_clients` client auth. Tory Access doesn't support that yet, so remove the files from `/var/lib/tor/ssh/authorized_clients/` and restart Tor. |
| Tor on the server won't start: "Permission on directory … are too permissive" | Run `sudo chmod 700 /var/lib/tor/ssh` and make sure Tor's user owns it. |

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
