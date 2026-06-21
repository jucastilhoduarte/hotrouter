# JLH6

Aplicativo Android para **minha head unit Haval/GWM**. Uma tela, dois botões: roteamento Starlink e atalho para configurações do Android.

Não está em nenhuma loja. Instalado apenas no meu carro.

## O que faz

### Botão Starlink Router

Roteia o tráfego do hotspot (`wlan2`) via Starlink (`wlan0`) usando iptables + ip rule, tudo executado via telnet root (`127.0.0.1:23`).

Estados do botão:

| Estado | Cor | Significado |
|--------|-----|-------------|
| DESATIVADO | cinza | roteamento desligado |
| ATIVANDO... | laranja | aguardando ping bem-sucedido via `wlan0` |
| ATIVADO | verde | roteamento ativo |
| DESATIVANDO... | laranja | removendo regras |

O roteamento só é aplicado após o primeiro `ping -I wlan0 8.8.8.8` bem-sucedido (loop de 5s). Se após 10 minutos nenhum ping funcionar, volta para DESATIVADO automaticamente.

Estado persistido em SharedPreferences (`router/enabled`). Na inicialização do sistema (`BOOT_COMPLETED`), se estava ativo, reinicia o loop de ping.

### Botão Configurações

Abre `com.android.settings/.Settings`.

## Como instalar

Via telnet na head unit (`telnet <ip-da-multimidia> 23`):

```sh
# Instalar o JLH6 (baixa o último release automaticamente)
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-app.sh | sh

# Instalar qualquer APK
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-apk.sh | sh -s <url-do-apk>
```

Os scripts fazem o bypass do bloqueio de `pm install` via injeção Frida no `system_server`.
Os binários do exploit estão em: `https://github.com/jucastilhoduarte/jlh6/releases/tag/exploit-bins`

## Build / release

- **Pull request → `assembleDebug`**
- **Merge para `main` → `assembleRelease` assinado** → publicado como release do GitHub com APK

Segredos de assinatura no Actions: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.
