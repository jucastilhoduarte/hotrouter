# Starlink Router — contexto para o Claude

## O que é isso

Aplicativo Android de propósito único para o sistema de entretenimento (head unit) pessoal de um carro Haval/GWM. Executa um daemon de roteamento que faz a ponte entre o hotspot Wi-Fi do carro e um uplink Starlink (`wlan0`), com fallback para o 4G OEM (`vlan13`). Não está em nenhuma loja.

## Regras absolutas — nunca quebre estas

- **Zero dependências de terceiros.** Apenas Android SDK. Apenas Java. Sem AndroidX, sem Compose,
  sem Kotlin, sem Shizuku, sem commons-net, sem nada do Jetpack.
- `android.useAndroidX=false` em `gradle.properties` — deve permanecer assim.
- `minSdk = targetSdk = 28` — deliberado; necessário para leniência legada de boot/FGS/background.
- `compileSdk = 35`, AGP 8.7.3, Gradle 8.14.3.
- PR → somente build debug. Merge em `main` → release assinada + `gh release create`.

## Modelo de privilégios (crítico)

O head unit expõe um **shell telnet root em `127.0.0.1:23`** (prompt `:/ #`).
O app só consegue acessá-lo **se uid ≤ 10999**, concedido ao instalar durante a janela de
injeção Frida no `system_server` (`scripts/install.sh` fases 1–3).

Todas as operações privilegiadas passam por `TelnetRoot.java` — um cliente de socket raw de ~100 linhas
(sem biblioteca telnet). Nunca adicione Shizuku ou ADB-over-network; telnet:23 já é root.

## Arquivos principais

| Caminho | O que é |
|---------|---------|
| `app/src/main/java/com/castilhoduarte/starlinkrouter/TelnetRoot.java` | Cliente telnet via socket raw. Negociação IAC, delimitação por sentinel (`__HR_BEG__`/`__HR_END__$?`). |
| `app/src/main/java/com/castilhoduarte/starlinkrouter/StarlinkRouter.java` | Gerenciador singleton. `enableAndStart()`, `stop()`, `readStatus()` → `OFF/STARTING/STARLINK/4G/ERROR`. Dono do watchdog. |
| `app/src/main/java/com/castilhoduarte/starlinkrouter/MainActivity.java` | Uma tela. Consulta o status a cada 3s. |
| `app/src/main/java/com/castilhoduarte/starlinkrouter/LogActivity.java` | Visualização de log com scroll. |
| `app/src/main/java/com/castilhoduarte/starlinkrouter/BootService.java` | Serviço em foreground, `directBootAware`. Inicia o daemon no boot se o toggle estiver ON. |
| `app/src/main/java/com/castilhoduarte/starlinkrouter/BootReceiver.java` | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → inicia o `BootService`. |
| `app/src/main/assets/starlinkrouter.sh` | O daemon de roteamento. Script shell autocontido. Enviado para `/data/local/tmp` pelo app. |
| `scripts/install.sh` | Script de instalação executado no head unit. Gerencia as fases do exploit Frida + instalação do APK. |
| `scripts/test/rule_lifecycle_test.sh` | Teste com mock: prova que não há acúmulo de regras iptables/ip rule + resíduo zero após teardown. 19/19. |
| `scripts/test/TelnetRootTest.java` | Testes unitários do parser para TelnetRoot. 15/15. |
| `docs/DESIGN.md` | Arquitetura completa e decisões de design. |
| `docs/ui-mockup.svg` | Mockup da UI (tela de carro 21:9). |

## Daemon de roteamento (`starlinkrouter.sh`) — fatos essenciais

Interfaces: `HOTSPOT_IF=wlan2`, `STARLINK_IF=wlan0`, tabela `wlan0`.

**Caminho Starlink** = `ip_forward=1` + uma regra `ip rule` de desvio (iif wlan2 → lookup wlan0) +
três regras iptables autogerenciadas (POSTROUTING MASQUERADE + FORWARD wlan2↔wlan0).
**Não toca nas chains `tetherctrl_*` de forma alguma.**

**Fallback 4G** = remove tudo acima; o NAT tetherctrl do próprio sistema assume.

Histerese: `UP_THRESHOLD=2` pings consecutivos bons para mudar para Starlink,
`DOWN_THRESHOLD=4` para fazer fallback. O roteamento é reaplicado apenas em transições reais.

`purge_footprint` é executado em: fallback 4G, `stop`, trap TERM/INT e baseline de inicialização
(recuperação de crash). Não há possibilidade de regras fantasma.

## Arquivos de estado (no dispositivo, `/data/local/tmp/`)

- `starlinkrouter.state` — `STARLINK|4G|OFF` + timestamp epoch
- `starlinkrouter.pid` — PID do daemon
- `starlinkrouter.log` — log DIAG, truncado em 2000 linhas

## Fluxo de instalação

```sh
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/starlinkrouter/main/scripts/install.sh | sh
```

Fases:
1. Baixar binários Frida da release GitHub `exploit-bins` (com cache)
2. Iniciar `fridaserver`
3. Injetar `system_server.js` no PID do `system_server`
4. Baixar + instalar APK da release GitHub mais recente

Binários do exploit em: `https://github.com/jucastilhoduarte/starlinkrouter/releases/tag/exploit-bins`

## CI (`github/workflows/build.yml`)

- **PR**: `assembleDebug` + executar `rule_lifecycle_test.sh` + `TelnetRootTest`
- **Push em main**: mesmos testes + `assembleRelease` assinado + `gh release create`

Secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## Design da UI

Tema escuro, pt-BR, landscape, 21:9. Sem ActionBar (`Theme.Material.NoActionBar`).
Cores: verde = Starlink/ON, azul = 4G, âmbar = STARTING, vermelho = ERROR, cinza = OFF.
Ícone do launcher com arcos Wi-Fi em vetor drawable (adaptativo).

## Pacote / assinatura

- `applicationId = com.castilhoduarte.starlinkrouter`
- Assinado com a chave pessoal do dono (nunca commitada). Keystore em `~/Desktop/haval-actions-secrets`.
- APKs de release: `isMinifyEnabled = false` (sem dependências para enxugar).
