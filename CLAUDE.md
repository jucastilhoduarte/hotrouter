# JLH6 — contexto para o Claude

## Superpowers

**Sempre use superpowers** para qualquer tarefa não trivial neste projeto:

- Nova feature ou mudança de comportamento → `superpowers:brainstorming` primeiro
- Implementação com múltiplas tarefas → `superpowers:subagent-driven-development`
- Só implementação direta (plano já claro) → `superpowers:writing-plans`

## O que é isso

Aplicativo Android para a head unit pessoal de um carro Haval/GWM. Uma tela, dois botões:

1. **Starlink Router** — roteia tráfego do hotspot (`wlan2`) via Starlink (`wlan0`) usando iptables + ip rule via telnet root
2. **Configurações** — abre `com.android.settings/.Settings`

## Regras absolutas — nunca quebre estas

- **Zero dependências de terceiros.** Apenas Android SDK. Apenas Java. Sem AndroidX, sem Compose, sem Kotlin, sem Shizuku, sem nada do Jetpack.
- `android.useAndroidX=false` em `gradle.properties` — deve permanecer assim.
- `minSdk = targetSdk = 28` — deliberado.
- `compileSdk = 35`, AGP 8.7.3, Gradle 8.14.3.
- PR → somente build debug. Merge em `main` → release assinada + `gh release create`.

## Arquivos principais

| Caminho | O que é |
|---------|---------|
| `app/src/main/java/com/castilhoduarte/jlh6/MainActivity.java` | Única Activity. Poll de estado a cada 500ms durante transições. |
| `app/src/main/java/com/castilhoduarte/jlh6/RouterManager.java` | Singleton. State machine: DISABLED/STARTING/ACTIVE/PURGING. HandlerThread para background. |
| `app/src/main/java/com/castilhoduarte/jlh6/TelnetRoot.java` | Cliente telnet mínimo para `127.0.0.1:23`. Sentinelas `__HR_BEG__`/`__HR_END__$?`. |
| `app/src/main/java/com/castilhoduarte/jlh6/RouterService.java` | Service START_NOT_STICKY. Mantém processo vivo durante STARTING no boot. |
| `app/src/main/java/com/castilhoduarte/jlh6/BootReceiver.java` | BOOT_COMPLETED → inicia RouterService se `enabled=true`. |
| `scripts/install-app.sh` | Instala o JLH6 via exploit Frida (bypass de pm install). |
| `scripts/install-apk.sh` | Instala qualquer APK via exploit Frida. |
| `scripts/test/TelnetRootTest.java` | 9 testes unitários do TelnetRoot. JDK puro, sem Gradle. |

## Topologia de rede

| Interface | Papel |
|-----------|-------|
| `wlan2` | Hotspot da multimídia (clientes do carro) |
| `wlan0` | Starlink (uplink externo) |
| tabela `wlan0` | Tabela de roteamento separada com rota default via Starlink |
| prioridade `17999` | Prioridade da ip rule de desvio |

## State machine do RouterManager

```
DISABLED → [tap] → STARTING → [ping wlan0 OK] → ACTIVE
STARTING → [10min sem ping] → DISABLED (salva enabled=false)
STARTING → [tap] → PURGING → DISABLED
ACTIVE   → [tap] → PURGING → DISABLED
```

- Ping loop: `ping -I wlan0 -c 1 -W 2 8.8.8.8` a cada 5s
- Timeout STARTING: 10 minutos
- Estado persistido: `SharedPreferences("router", "enabled")`
- No boot: se `enabled=true`, sempre entra STARTING (nunca aplica regras sem ping)
- Tap durante PURGING: ignorado

## Comandos telnet executados

### Apply (ativar roteamento)
```sh
echo 1 > /proc/sys/net/ipv4/ip_forward
ip rule del from all iif wlan2 lookup wlan0 priority 17999 2>/dev/null; true
ip rule add from all iif wlan2 lookup wlan0 priority 17999
iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -o wlan0 -j MASQUERADE
iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan2 -o wlan0 -j ACCEPT
iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT
```

### Purge (desativar — loop até limpo)
```sh
while ip rule | grep -q "iif wlan2 lookup wlan0"; do ip rule del ...; done
while iptables -t nat -C POSTROUTING ...; do iptables -t nat -D POSTROUTING ...; done
while iptables -C FORWARD -i wlan2 ...; do iptables -D FORWARD ...; done
while iptables -C FORWARD -i wlan0 ...; do iptables -D FORWARD ...; done
```

## Exploit Frida (por que existe)

A head unit bloqueia `pm install` de APKs externos. Os scripts injetam no `system_server` via Frida para remover essa restrição durante a instalação.

Binários do exploit em: `https://github.com/jucastilhoduarte/jlh6/releases/tag/exploit-bins`

## CI (`.github/workflows/build.yml`)

- **PR**: `assembleDebug`
- **Push em main**: `assembleRelease` assinado + `gh release create`

Secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## Design da UI

Tema escuro, landscape, 21:9. Sem ActionBar. Dois botões retangulares empilhados verticalmente, centralizados. Ícone wifi + texto no botão router; ícone engrenagem + texto no botão configurações.

## Pacote / assinatura

- `applicationId = com.castilhoduarte.jlh6`
- Assinado com chave pessoal do dono. Keystore em `~/Desktop/haval-actions-secrets`.
