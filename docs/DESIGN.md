# HotRouter — Design

## Goal

A single-purpose, harmless Android app for **my own Haval head unit**. It does one
thing: run the **HotRouter** daemon that bridges the car's Wi-Fi hotspot traffic out
through an external WLAN (e.g. Starlink on `wlan0`) when reachable, falling back to the
OEM 4G route (`vlan13`) otherwise.

Extracted from the old `haval-app-tool-multimidia` project, keeping **only** the
HotRouter feature. Everything else (Frida hooks, cluster projection, vehicle AIDL,
window/AC automations, Compose UI, Shizuku) is discarded.

Not published anywhere. Installed only on my car, signed with my own key.

## Hard constraints

- **No third-party dependencies, no frameworks.** Android SDK only. No Shizuku, no
  commons-net, no Jetpack Compose, no AndroidX, no Kotlin stdlib.
- **Java only.**
- Pretty, friendly, dead-simple UI.
- Auto-start on boot with enough privilege to launch the daemon, with **no manual app
  open**. Previous on/off state is remembered across reboots.

## Privilege model (the important part)

The head unit runs a **root telnet shell on `127.0.0.1:23`** (prompt `:/ #`). An app can
reach it **only if its uid ≤ 10999**, which is granted by installing the app inside the
Frida `system_server` injection window (see `scripts/install.sh`). This is unchanged from
the old app.

The old app used telnet only to *bootstrap Shizuku*, then ran everything through Shizuku.
For HotRouter that indirection is unnecessary: **telnet is already root**, and the daemon
needs nothing more than a root shell (`ip rule`, `iptables`, `/proc/sys`, file writes in
`/data/local/tmp`). So we **drop Shizuku entirely** and talk to telnet:23 directly via a
~100-line raw-socket client. This is what makes "no dependencies" achievable.

If telnet:23 is unreachable (app installed without the exploit → uid too high), the UI
shows a friendly "reinstale pelo exploit" message instead of crashing.

## Architecture

```
Boot ─▶ BootReceiver ─▶ BootService (foreground, directBootAware)
                              │  read persisted toggle (device-protected prefs)
                              │  toggle ON? ──▶ telnet:23 ─▶ push hotrouter.sh
                              │                          ─▶ setsid sh hotrouter.sh start
                              └──────────────▶ arm 60s watchdog (relaunch if pid dead)

MainActivity ─▶ poll status every 3s via telnet (state file + pid liveness)
            ─▶ big toggle button  /  route chip (WLAN·4G)  /  "Ver logs" button
LogActivity  ─▶ tail hotrouter.log via telnet
```

### Components

| File | Responsibility |
|------|----------------|
| `TelnetRoot.java` | Raw `java.net.Socket` to `127.0.0.1:23`. Minimal IAC handshake (refuse all DO/WILL). `exec(cmd)` sends `cmd; echo __HR_END__$?` and reads until the sentinel, stripping IAC + ANSI. Returns output + exit code. No library. |
| `HotRouter.java` | Singleton on a background `HandlerThread`. `enableAndStart()`, `stop()`, `readStatus()` → `OFF/STARTING/WLAN/4G/ERROR`, `readLog(n)`, `isDaemonAlive()`. All shell work via `TelnetRoot`. Persists the toggle. Owns the watchdog. Mirrors old `HotRouterManager` logic. |
| `hotrouter.sh` | **Reused verbatim** from the old app. Asset, base64-pushed to `/data/local/tmp/hotrouter.sh`. Daemon writes `hotrouter.state` (`WLAN`/`4G`/`OFF` + epoch), `hotrouter.pid`, `hotrouter.log`. |
| `BootService.java` | Foreground service, `directBootAware`. On start: if toggle ON, push+start daemon, arm watchdog. Keeps a quiet persistent notification. |
| `BootReceiver.java` | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → start `BootService`. |
| `MainActivity.java` | The one screen. Toggle writes the pref and calls the manager. Polls status every 3s. |
| `LogActivity.java` | Scrollable monospace view of `tail -n 400 hotrouter.log`, with refresh. |

### State persistence

`enableHotRouter` boolean in **device-protected** `SharedPreferences` (so it is readable
during `LOCKED_BOOT_COMPLETED`, before the user unlocks). This is the "remember previous
state across reboot" mechanism: set ON before reboot → daemon auto-starts on next boot.

## UI

One landscape screen, dark, friendly, rounded card:

```
        ((•)) HotRouter

   ┌───────────────────────┐
   │       L I G A D O      │   big button — tap toggles
   │   (toque para desligar)│   green=ON · gray=OFF · amber=STARTING · red=ERROR
   └───────────────────────┘

      ● Trafegando via WLAN (Starlink)    chip: green WLAN / blue 4G / dim "—"

            [   Ver logs   ]
```

- Logo: Wi-Fi signal arcs as a vector drawable; also the launcher icon (adaptive).
- Theme: custom, parented to platform `Theme.Material.NoActionBar` (no AndroidX).
- Status text in pt-BR.

## Stack / build

- `minSdk = 28`, `targetSdk = 28` (legacy background/FGS/boot leniency the install
  flow relies on), `compileSdk = 35`.
- AGP 8.7.3, Gradle 8.14.3. CI builds on **JDK 17**.
- `app/build.gradle.kts` has an **empty `dependencies {}`**. `android.useAndroidX=false`.
- Release build: `isMinifyEnabled = false` (no deps to shrink). Signed from CI secrets.

### Permissions (minimal / inoffensive)

`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `INTERNET` (localhost socket),
`WAKE_LOCK`. Nothing else.

## CI/CD (`.github/workflows/build.yml`)

- **`pull_request` → `assembleDebug`.** Confirms it compiles. No secrets, no release.
- **`push` to `main` (= merge) → signed `assembleRelease`** → auto-increment version →
  `gh release create` + upload `app-release.apk`.
- All `preview` / prerelease branch logic removed.
- Keystore decoded from `KEYSTORE_BASE64` secret at build time; never committed.

Secrets set on `jucastilhoduarte/haval-hotrouter`: `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_PASSWORD`, `KEY_ALIAS`.

## Install (`scripts/install.sh`)

Adapted from the old installer:
- Keeps the Frida exploit phases (so the app installs with uid ≤ 10999 → telnet:23
  reachable).
- **Drops the Shizuku install phase** (Shizuku no longer used).
- Uninstalls the old `br.com.redesurftank.havalshisuku` and installs the new
  `com.castilhoduarte.hotrouter`.

## Decisions

- Drop the old `iptables -I INPUT/OUTPUT ACCEPT` unlock — that served the big app's own
  connectivity, not HotRouter. Hotspot routing uses `tetherctrl_*` / `FORWARD`, which the
  script manages itself.
- PR builds **debug** (no secrets required); signed release only on merge to `main`.
- `applicationId = com.castilhoduarte.hotrouter`, display name **HotRouter**.

## Out of scope

Anything that isn't HotRouter. No vehicle control, no cluster, no Frida runtime, no
multi-user, no settings beyond the single toggle.
