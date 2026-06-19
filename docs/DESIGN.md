# Starlink Router — Design

## Objetivo

Um app Android de propósito único e inofensivo para **minha própria central multimídia Haval**. Ele faz uma
coisa só: executar o daemon **Starlink Router** que encaminha o tráfego do hotspot Wi-Fi do carro para
fora pelo uplink externo Starlink (`wlan0`) quando disponível, caindo de volta para a rota
4G OEM (`vlan13`) caso contrário.

Não publicado em lugar nenhum. Instalado apenas no meu carro, assinado com minha própria chave.

## Restrições rígidas

- **Sem dependências de terceiros, sem frameworks.** Apenas o Android SDK. Sem Shizuku, sem
  commons-net, sem Jetpack Compose, sem AndroidX, sem Kotlin stdlib.
- **Somente Java.**
- UI bonita, amigável e extremamente simples.
- Auto-inicialização no boot com privilégio suficiente para lançar o daemon, **sem necessidade de
  abrir o app manualmente**. O estado anterior de ligado/desligado é lembrado entre reboots.

## Modelo de privilégio (a parte importante)

A central multimídia executa um **shell telnet root em `127.0.0.1:23`** (prompt `:/ #`). Um app pode
acessá-lo **somente se seu uid ≤ 10999**, o que é concedido instalando o app dentro da janela de
injeção `system_server` do Frida (veja `scripts/install.sh`). Isso não mudou em relação ao app antigo.

O app antigo usava telnet apenas para *inicializar o Shizuku*, e depois executava tudo via Shizuku.
Para o Starlink Router essa indireção é desnecessária: **telnet já é root**, e o daemon
não precisa de nada mais do que um shell root (`ip rule`, `iptables`, `/proc/sys`, escritas de arquivo em
`/data/local/tmp`). Portanto, **abandonamos o Shizuku completamente** e falamos com telnet:23 diretamente via um
cliente de socket raw de ~100 linhas. É isso que torna "sem dependências" viável.

Se telnet:23 estiver inacessível (app instalado sem o exploit → uid muito alto), a UI
exibe uma mensagem amigável "reinstale pelo exploit" em vez de travar.

## Arquitetura

```
Boot ─▶ BootReceiver ─▶ BootService (foreground, directBootAware)
                              │  read persisted toggle (device-protected prefs)
                              │  toggle ON? ──▶ telnet:23 ─▶ push starlinkrouter.sh
                              │                          ─▶ setsid sh starlinkrouter.sh start
                              └──────────────▶ arm 60s watchdog (relaunch if pid dead)

MainActivity ─▶ poll status every 3s via telnet (state file + pid liveness)
            ─▶ big toggle button  /  route chip (Starlink·4G)  /  botões "Logs" + "Configurações"
LogActivity  ─▶ tail starlinkrouter.log via telnet
```

### Componentes

| Arquivo | Responsabilidade |
|---------|------------------|
| `TelnetRoot.java` | `java.net.Socket` raw para `127.0.0.1:23`. Handshake IAC mínimo (recusa todos DO/WILL). `exec(cmd)` envia `cmd; echo __HR_END__$?` e lê até o sentinel, removendo IAC + ANSI. Retorna saída + código de saída. Sem biblioteca. |
| `StarlinkRouter.java` | Singleton em uma `HandlerThread` em background. `enableAndStart()`, `stop()`, `readStatus()` → `OFF/STARTING/STARLINK/4G/ERROR`, `readLog(n)`, `isDaemonAlive()`. Todo trabalho de shell via `TelnetRoot`. Persiste o toggle. Gerencia o watchdog. Espelha a lógica do antigo `StarlinkRouterManager`. |
| `starlinkrouter.sh` | Daemon de roteamento autossuficiente (hysteresis + NAT autogerenciado, independente das chains tetherctrl do sistema; veja "Guardrails de roteamento" abaixo). Asset, enviado em base64 para `/data/local/tmp/starlinkrouter.sh`. Escreve `starlinkrouter.state` (`STARLINK`/`4G`/`OFF` + epoch), `starlinkrouter.pid`, `starlinkrouter.log`. |
| `BootService.java` | Serviço em foreground, `directBootAware`. Ao iniciar: se toggle ON, envia e inicia o daemon, arma o watchdog. Mantém uma notificação persistente discreta. |
| `BootReceiver.java` | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → inicia `BootService`. |
| `MainActivity.java` | A tela única. O toggle salva a preferência e chama o manager. Verifica o status a cada 3s. |
| `LogActivity.java` | Visualização monospace rolável de `tail -n 400 starlinkrouter.log`, com atualização. |

### Persistência de estado

Boolean `enableStarlinkRouter` nas `SharedPreferences` com **proteção de dispositivo** (para que seja legível
durante `LOCKED_BOOT_COMPLETED`, antes de o usuário desbloquear). Esse é o mecanismo de "lembrar estado
anterior entre reboots": definir como ON antes do reboot → daemon inicia automaticamente no próximo boot.

## UI

Uma tela em paisagem, escura, amigável, com card arredondado:

```
        ((•)) Starlink Router

   ┌───────────────────────┐
   │       L I G A D O      │   big button — tap toggles
   │   (toque para desligar)│   green=ON · gray=OFF · amber=STARTING · red=ERROR
   └───────────────────────┘

      ● Trafegando via Starlink           chip: green Starlink / blue 4G / dim "—"

        [ ☰ Logs ]  [ ⚙ Configurações ]    botões horizontais: ícone + texto, compactos
```

- Logo: arcos de sinal Wi-Fi como drawable vetorial; também o ícone do launcher (adaptativo).
- Tema: personalizado, com pai no `Theme.Material.NoActionBar` da plataforma (sem AndroidX).
- Texto de status em pt-BR.

## Stack / build

- `minSdk = 28`, `targetSdk = 28` (leniência legada de background/FGS/boot da qual o fluxo
  de instalação depende), `compileSdk = 35`.
- AGP 8.7.3, Gradle 8.14.3. CI compila com **JDK 17**.
- `app/build.gradle.kts` tem um **`dependencies {}` vazio**. `android.useAndroidX=false`.
- Build de release: `isMinifyEnabled = false` (sem deps para encolher). Assinado a partir de secrets do CI.

### Permissões (mínimas / inofensivas)

`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `INTERNET` (socket localhost),
`WAKE_LOCK`. Nada além disso.

## CI/CD (`.github/workflows/build.yml`)

- **`pull_request` → `assembleDebug`.** Confirma que compila. Sem secrets, sem release.
- **`push` para `main` (= merge) → `assembleRelease` assinado** → incremento automático de versão →
  `gh release create` + upload de `app-release.apk`.
- Toda lógica de branch `preview` / prerelease removida.
- Keystore decodificado do secret `KEYSTORE_BASE64` no momento do build; nunca commitado.

Secrets configurados em `jucastilhoduarte/starlinkrouter`: `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_PASSWORD`, `KEY_ALIAS`.

## Instalação (`scripts/install.sh`)

Adaptado do instalador antigo:
- Mantém as fases do exploit Frida (para que o app seja instalado com uid ≤ 10999 → telnet:23
  acessível).
- **Remove a fase de instalação do Shizuku** (Shizuku não é mais usado).
- Instala `com.castilhoduarte.starlinkrouter` de forma idempotente.

## Decisões

- Remover o antigo `iptables -I INPUT/OUTPUT ACCEPT` — ele servia à conectividade do app grande,
  não ao Starlink Router. O roteamento do hotspot usa `tetherctrl_*` / `FORWARD`, que o script
  gerencia por conta própria.
- Builds de PR em **debug** (sem secrets necessários); release assinado somente no merge para `main`.
- `applicationId = com.castilhoduarte.starlinkrouter`, nome de exibição **Starlink Router**.

## Guardrails de roteamento (daemon)

Sintoma de campo que motivou isso: em estrada aberta com **zero 4G**, o roteamento via Starlink falhava;
com um sinal 3G fraco funcionava. E o CarPlay às vezes caia numa troca de rede.

**Causa raiz (hipótese, confirmada pelos logs `DIAG` no próximo percurso):** o daemon antigo
rodava NAT/forwarding nas chains iptables `tetherctrl_*` do Android, que o sistema só
popula enquanto o hotspot tem um **uplink celular**. Sem celular → essas chains somem →
`ensure_iptables` abortava → caminho Starlink morto, mesmo com o link satelital funcionando.
O `ip route flush cache` de 5s + comutação por amostra única também fazia a rota flutuar e
resetava conexões ativas (CarPlay).

Correções em `starlinkrouter.sh`:

1. **NAT/forward autogerenciado** (`ensure_iptables_self`): instala `POSTROUTING -o wlan0
   MASQUERADE` e `FORWARD wlan2↔wlan0 ACCEPT` diretamente. São apenas regras aditivas
   ACCEPT/MASQUERADE (nunca DROP), então não podem regredir o caso que já funciona. O caminho
   Starlink **não toca nas chains `tetherctrl_*` do sistema de forma alguma** — o roteador
   inteiro é `ip_forward` + um desvio `ip rule` + essas três regras iptables. O fallback 4G
   ainda usa o NAT tetherctrl do próprio sistema (sempre presente quando há celular).
2. **Hysteresis**: troca para Starlink somente após `UP_THRESHOLD` (2) amostras boas consecutivas,
   retorna somente após `DOWN_THRESHOLD` (4) falhas consecutivas. O roteamento é reaplicado
   (e o cache limpo) **somente em uma transição real**; em estado estável apenas atualiza
   regras idempotentes sem perturbação.
3. **Diagnósticos** (`dump_diag`): a cada transição, despeja `ip rule`, as tabelas de rota
   Starlink e main, as chains NAT/`FORWARD`/`tetherctrl_FORWARD`, e ping por host no
   log — para que uma falha em estrada aberta possa ser diagnosticada depois.

### Sem regras fantasma

Uma regra de desvio ou NAT obsoleta deixada por uma execução travada poderia bloquear o hotspot.
Garantias:

- **Adições idempotentes** — cada `ensure_*` é protegido por `-C`, então nada acumula ao longo
  do loop de 5s ou em transições repetidas.
- **Uma única limpeza, em todo caminho de saída** — `purge_footprint` (desvio `ip rule` + self
  NAT/forward) é executado no fallback 4G, no `stop`, na trap TERM/INT, **e** na linha de base
  de inicialização. Então mesmo após um SIGKILL não capturável, o próximo lançamento reseta
  para um estado limpo antes de fazer qualquer coisa. Regras no kernel também desaparecem no reboot.
- **Provado, não apenas afirmado** — `scripts/test/rule_lifecycle_test.sh` executa as funções
  reais contra um `iptables`/`ip` mock passando por apply → keepalive×N → fallback →
  recuperação de crash com ghost injetado → stop, e verifica zero resíduo + sem acúmulo.
  Executa no CI em cada PR e release.

Nota: a reformulação do roteamento **não é testável em bancada** (precisa da rede real do carro). As
regras autogerenciadas são de baixo risco por construção; os diagnósticos existem para confirmar a
causa raiz no próximo percurso.

## Fora do escopo

Tudo que não for Starlink Router. Sem controle do veículo, sem cluster, sem runtime Frida, sem
multi-usuário, sem configurações além do toggle único.
