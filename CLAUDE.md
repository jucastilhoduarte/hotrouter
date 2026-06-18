# HotRouter — context for Claude

## What this is

Single-purpose Android app for a personal Haval/GWM car head unit. Runs a routing daemon
that bridges the car's Wi-Fi hotspot through a Starlink uplink (`wlan0`), falling back to
OEM 4G (`vlan13`). Not on any store.

## Hard rules — never break these

- **Zero third-party dependencies.** Android SDK only. Java only. No AndroidX, no Compose,
  no Kotlin, no Shizuku, no commons-net, no Jetpack anything.
- `android.useAndroidX=false` in `gradle.properties` — must stay.
- `minSdk = targetSdk = 28` — deliberate; legacy boot/FGS/background leniency required.
- `compileSdk = 35`, AGP 8.7.3, Gradle 8.14.3.
- PR → debug build only. Merge to `main` → signed release + `gh release create`.

## Privilege model (critical)

Head unit exposes a **root telnet shell on `127.0.0.1:23`** (prompt `:/ #`).
App can reach it **only if uid ≤ 10999**, granted by installing during the Frida
`system_server` injection window (`scripts/install.sh` phases 1–3).

All privileged ops go through `TelnetRoot.java` — a ~100-line raw-socket client
(no telnet library). Never add Shizuku or ADB-over-network; telnet:23 is already root.

## Key files

| Path | What it is |
|------|------------|
| `app/src/main/java/com/castilhoduarte/hotrouter/TelnetRoot.java` | Raw socket telnet client. IAC negotiation, sentinel framing (`__HR_BEG__`/`__HR_END__$?`). |
| `app/src/main/java/com/castilhoduarte/hotrouter/HotRouter.java` | Singleton manager. `enableAndStart()`, `stop()`, `readStatus()` → `OFF/STARTING/STARLINK/4G/ERROR`. Owns watchdog. |
| `app/src/main/java/com/castilhoduarte/hotrouter/MainActivity.java` | One screen. Polls status every 3s. |
| `app/src/main/java/com/castilhoduarte/hotrouter/LogActivity.java` | Scrollable log view. |
| `app/src/main/java/com/castilhoduarte/hotrouter/BootService.java` | Foreground service, `directBootAware`. Starts daemon on boot if toggle ON. |
| `app/src/main/java/com/castilhoduarte/hotrouter/BootReceiver.java` | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → starts `BootService`. |
| `app/src/main/assets/hotrouter.sh` | The routing daemon. Self-contained shell script. Pushed to `/data/local/tmp` by the app. |
| `scripts/install.sh` | Install script run on the head unit. Handles Frida exploit phases + APK install. |
| `scripts/test/rule_lifecycle_test.sh` | Mock-backed test: proves no iptables/ip rule accumulation + zero residue after teardown. 19/19. |
| `scripts/test/TelnetRootTest.java` | Parser unit tests for TelnetRoot. 15/15. |
| `docs/DESIGN.md` | Full architecture and design decisions. |
| `docs/ui-mockup.svg` | UI mockup (21:9 car screen). |

## Routing daemon (`hotrouter.sh`) — key facts

Interfaces: `HOTSPOT_IF=wlan2`, `STARLINK_IF=wlan0`, table `wlan0`.

**Starlink path** = `ip_forward=1` + one `ip rule` diversion (iif wlan2 → lookup wlan0) +
three self-managed iptables rules (POSTROUTING MASQUERADE + FORWARD wlan2↔wlan0).
**Does NOT touch `tetherctrl_*` chains at all.**

**4G fallback** = purge the above; system's own tetherctrl NAT takes over.

Hysteresis: `UP_THRESHOLD=2` consecutive good pings to switch to Starlink,
`DOWN_THRESHOLD=4` to fall back. Routing re-applied only on real transitions.

`purge_footprint` runs on: 4G fallback, `stop`, TERM/INT trap, and startup baseline
(crash recovery). No ghost rules possible.

## State files (on device, `/data/local/tmp/`)

- `hotrouter.state` — `STARLINK|4G|OFF` + epoch timestamp
- `hotrouter.pid` — daemon PID
- `hotrouter.log` — DIAG log, trimmed to 2000 lines

## Install flow

```sh
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/hotrouter/main/scripts/install.sh | sh
```

Phases:
1. Download Frida binaries from `exploit-bins` GitHub release (cached)
2. Start `fridaserver`
3. Inject `system_server.js` into `system_server` PID
4. Download + install APK from latest GitHub release

Exploit binaries live at: `https://github.com/jucastilhoduarte/hotrouter/releases/tag/exploit-bins`

## CI (`github/workflows/build.yml`)

- **PR**: `assembleDebug` + run `rule_lifecycle_test.sh` + `TelnetRootTest`
- **Push to main**: same tests + signed `assembleRelease` + `gh release create`

Secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## UI design

Dark theme, pt-BR, landscape, 21:9. No ActionBar (`Theme.Material.NoActionBar`).
Colors: green = Starlink/ON, blue = 4G, amber = STARTING, red = ERROR, gray = OFF.
Vector drawable Wi-Fi arcs as launcher icon (adaptive).

## Package / signing

- `applicationId = com.castilhoduarte.hotrouter`
- Signed with owner's personal key (never committed). Keystore in `~/Desktop/haval-actions-secrets`.
- Release APKs: `isMinifyEnabled = false` (no deps to shrink).
