#!/bin/sh

# install-apk.sh - instala qualquer APK na head unit usando o exploit Frida.
#
# Uso:
#   sh install-apk.sh <url-do-apk>
#
# A URL e obrigatoria. Deve apontar diretamente para um .apk publico.
#
# Por que o exploit e necessario: a multimidia bloqueia pm install de APKs
# externos. A injecao Frida no system_server remove essa restricao durante
# a instalacao.

set -u

REPO="https://github.com/jucastilhoduarte/jlh6"
WORK="/data/local/tmp"
ROLLBACK_ENABLED=true

log() { echo "[$1] $(date +%H:%M:%S) $2"; }
die() { log "ERR" "$1"; exit 1; }

cleanup() {
    rc=$?
    [ "$rc" -eq 0 ] && exit 0
    [ "$ROLLBACK_ENABLED" = false ] && exit 0
    log "INFO" "Rollback..."
    rm -f "$WORK/target.apk" 2>/dev/null || true
    log "INFO" "Rollback concluido"
}
trap cleanup EXIT

download() {
    log "INFO" "Baixando $3..."
    curl -L --fail --progress-bar -o "$2" "$1" || die "Falha no download de $3"
    [ -s "$2" ] || die "$3 vazio/inexistente"
}

download_cached() {
    [ -f "$2" ] && [ -s "$2" ] && { log "INFO" "$3 ja existe (cache)"; return; }
    download "$1" "$2" "$3"
}

main() {
    URL="${1:-}"
    [ -n "$URL" ] || die "Uso: sh install-apk.sh <url-do-apk>"

    cd "$WORK" || die "Falha ao acessar $WORK"

    # --- Fase 1: binarios do exploit (cacheados) ---
    log "INFO" "Fase 1: Downloads do exploit"
    download_cached "$REPO/releases/download/exploit-bins/fridaserver.rar" "fridaserver" "fridaserver"
    download_cached "$REPO/releases/download/exploit-bins/fridainject.rar" "fridainject" "fridainject"
    download_cached "$REPO/releases/download/exploit-bins/system_server.js" "system_server.js" "system_server.js"
    chmod +x fridaserver fridainject || die "Falha nas permissoes"

    # --- Fase 2: fridaserver (idempotente) ---
    log "INFO" "Fase 2: fridaserver"
    if pgrep fridaserver >/dev/null 2>&1; then
        log "INFO" "fridaserver ja rodando"
    else
        [ -x "./fridaserver" ] || die "fridaserver nao executavel"
        setsid ./fridaserver >/dev/null 2>&1 < /dev/null &
        sleep 2
        pgrep fridaserver >/dev/null 2>&1 || die "fridaserver nao iniciou"
        log "INFO" "fridaserver iniciado"
    fi

    # --- Fase 3: injecao no system_server ---
    log "INFO" "Fase 3: Injecao system_server"
    [ -f "system_server.js" ] || die "system_server.js nao encontrado"
    SYSTEM_PID=$(pidof system_server) || die "system_server nao encontrado"
    ./fridainject -p "$SYSTEM_PID" -s system_server.js &
    sleep 2
    log "INFO" "Injecao disparada (system_server pid=$SYSTEM_PID)"

    # --- Fase 4: baixar APK ---
    log "INFO" "Fase 4: Baixar APK ($URL)"
    rm -f target.apk 2>/dev/null || true
    download "$URL" "target.apk" "APK"
    APK="$WORK/target.apk"

    # --- Fase 5: instalar ---
    log "INFO" "Fase 5: Instalando APK"
    pm install -r "$APK" || pm install "$APK" || die "Falha na instalacao"

    # --- limpeza ---
    rm -f target.apk 2>/dev/null || true
    ROLLBACK_ENABLED=false

    log "INFO" "APK instalado com sucesso"
}

main "$@"
