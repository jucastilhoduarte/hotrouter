# HotRouter

Single-purpose Android app for **my own Haval head unit**. Runs the **HotRouter** daemon:
bridges the car's Wi-Fi hotspot traffic out through the external Starlink uplink (`wlan0`)
when reachable, falls back to OEM 4G (`vlan13`) otherwise.

Not on any store. Installed only on my car, signed with my own key.

## UI

![mockup](docs/ui-mockup.svg)

One screen (21:9 landscape):
- Big **LIGADO / DESLIGADO** toggle button
- Chip: `Trafegando via Starlink` or `Trafegando via 4G`
- **Ver logs** button

Auto-starts on boot, restores last on/off state — no need to open the app.

## How it works

- **Zero dependencies** — Android SDK only. Java. No AndroidX, no Compose, no Shizuku, no telnet library.
- Root work (`ip rule`, `iptables`, daemon) runs through the head unit's telnet shell on
  `127.0.0.1:23`, reached by a ~100-line raw-socket client
  ([`TelnetRoot.java`](app/src/main/java/com/castilhoduarte/hotrouter/TelnetRoot.java)).
- Shell reachable only if app uid ≤ 10999 — requires install through Frida exploit window
  (see [`scripts/install.sh`](scripts/install.sh)).
- Daemon: [`hotrouter.sh`](app/src/main/assets/hotrouter.sh) — pushed to `/data/local/tmp`,
  supervised by a 60s watchdog. Self-managed NAT/forwarding, independent of system
  `tetherctrl_*` chains. Hysteresis prevents flapping.

Full design: [`docs/DESIGN.md`](docs/DESIGN.md).

## Build / release

- **Pull request → `assembleDebug`** (compile check, no secrets).
- **Merge to `main` → signed `assembleRelease`** → published as GitHub release with APK.

Signing secrets in Actions: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## Install on the car

Via telnet ou shell da multimídia, de qualquer pasta:

```sh
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/hotrouter/main/scripts/install.sh | sh
```

Baixa os binários do Frida do release `exploit-bins` e o APK do último release automaticamente.
