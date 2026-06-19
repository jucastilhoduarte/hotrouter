#!/system/bin/sh

# starlinkrouter.sh
#
# Daemon de roteamento automático para o hotspot multimídia.
#
# Objetivo:
# - Preferir o uplink externo Starlink (wlan0) para clientes do hotspot.
# - Usar a rota 4G OEM (vlan13) como fallback quando o Starlink estiver indisponível.
#
# Notas de design (veja docs/DESIGN.md):
# - O caminho Starlink é totalmente autogerenciado: ip_forward + um `ip rule` de desvio + nossas próprias
#   regras FORWARD/MASQUERADE. NÃO toca nas chains tetherctrl_* do sistema.
#   (A abordagem legada dependia das tetherctrl_*, que o Android só popula enquanto um
#   upstream celular está ativo — então o Starlink quebrava sempre que o 4G caía a zero.) O
#   fallback 4G ainda usa o NAT tetherctrl do sistema, presente sempre que o celular está ativo.
# - A troca é debouncada (histerese) e o roteamento só é reaplicado em uma transição real,
#   para que breves falhas de ping no Starlink não causem flap de rota e resetem conexões ativas
#   (o que costumava matar o CarPlay durante a condução).
# - A cada transição, despeja um bloco DIAG (regras, rotas, iptables, ping) para que falhas
#   em campo possam ser diagnosticadas posteriormente.
#
# Uso:
#   sh starlinkrouter.sh start   # executa o loop de roteamento (padrão)
#   sh starlinkrouter.sh stop    # encerra o daemon e remove todas as regras

BASE="/data/local/tmp"
NAME="starlinkrouter"
LOG="$BASE/$NAME.log"
PIDFILE="$BASE/$NAME.pid"
STATEFILE="$BASE/$NAME.state"
HOTSPOT_IF="wlan2"
STARLINK_IF="wlan0"
STARLINK_TABLE="wlan0"
RULE_PRIO="17999"
CHECK_HOSTS="8.8.8.8 1.1.1.1"
INTERVAL_SEC=5
MAX_LOG_LINES=1200

# Histerese: quantas amostras consecutivas (separadas por INTERVAL_SEC) antes de trocar.
UP_THRESHOLD=2     # ~10s de Starlink estável antes de desviar para ele
DOWN_THRESHOLD=4   # ~20s de Starlink ruim antes de cair para 4G
HEARTBEAT_EVERY=24

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2" >> "$LOG"
}

# Prefixia cada linha do stdin com uma tag DIAG (para saída de comandos multilinha).
logblock() {
  _t="$1"
  while IFS= read -r _l; do
    log DIAG "$_t| $_l"
  done
}

write_state() {
  echo "$1|$(date +%s)" > "$STATEFILE"
}

trim_log() {
  [ -f "$LOG" ] || return
  lines="$(wc -l < "$LOG" 2>/dev/null)"
  [ "$lines" -gt "$MAX_LOG_LINES" ] || return
  tail -n "$MAX_LOG_LINES" "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}

kill_old_starlinkrouters() {
  self="$$"
  # Mata primeiro o pid registrado do daemon. O `ps` do Toybox não exibe args de script, então uma
  # varredura com `ps | grep starlinkrouter.sh` nunca encontra o daemon com setsid — o pidfile e uma
  # leitura de /proc/<pid>/cmdline são as únicas formas confiáveis de encontrá-lo.
  if [ -f "$PIDFILE" ]; then
    oldpid="$(cat "$PIDFILE" 2>/dev/null)"
    if [ -n "$oldpid" ] && [ "$oldpid" != "$self" ]; then
      kill -9 "$oldpid" 2>/dev/null
    fi
  fi
  for p in /proc/[0-9]*; do
    pid="${p#/proc/}"
    [ "$pid" = "$self" ] && continue
    # Lê via cat para que um processo encerrando durante a varredura (cmdline desaparecido) seja
    # silenciado pelo 2>/dev/null em vez de vazar um erro de shell "can't open" para stderr.
    cmd="$(cat "$p/cmdline" 2>/dev/null | tr '\0' ' ')"
    case "$cmd" in
      *starlinkrouter.sh*start*) kill -9 "$pid" 2>/dev/null ;;
    esac
  done
  rm -f "$PIDFILE"
}

# ---- regra de roteamento (desviar ingresso do hotspot para a tabela Starlink) ----

cleanup_duplicate_rules() {
  while ip rule | grep -q "iif $HOTSPOT_IF lookup $STARLINK_TABLE"; do
    ip rule del from all iif "$HOTSPOT_IF" lookup "$STARLINK_TABLE" priority "$RULE_PRIO" 2>/dev/null || break
  done
}

# Idempotente: adiciona a regra de desvio apenas se ausente (sem delete-then-add que
# removeria a regra momentaneamente a cada passagem em estado estável).
ensure_rule_once() {
  ip rule | grep -q "iif $HOTSPOT_IF lookup $STARLINK_TABLE" && return 0
  ip rule add from all iif "$HOTSPOT_IF" lookup "$STARLINK_TABLE" priority "$RULE_PRIO" 2>/dev/null
}

# ---- NAT/forward autogerenciado (independente das chains tetherctrl do sistema) ----

ensure_iptables_self() {
  iptables -t nat -C POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null || \
    iptables -t nat -I POSTROUTING 1 -o "$STARLINK_IF" -j MASQUERADE

  iptables -C FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null || \
    iptables -I FORWARD 1 -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT

  iptables -C FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    iptables -I FORWARD 1 -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT
}

teardown_iptables_self() {
  while iptables -t nat -C POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null; do
    iptables -t nat -D POSTROUTING -o "$STARLINK_IF" -j MASQUERADE 2>/dev/null || break
  done
  while iptables -C FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null; do
    iptables -D FORWARD -i "$HOTSPOT_IF" -o "$STARLINK_IF" -j ACCEPT 2>/dev/null || break
  done
  while iptables -C FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; do
    iptables -D FORWARD -i "$STARLINK_IF" -o "$HOTSPOT_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || break
  done
}

# ---- sonda de alcançabilidade do Starlink ----

starlink_has_ping() {
  ip link show "$HOTSPOT_IF" >/dev/null 2>&1 || return 1
  ip link show "$STARLINK_IF" >/dev/null 2>&1 || return 1
  ip route show table "$STARLINK_TABLE" | grep -q "^default" || return 1

  for host in $CHECK_HOSTS; do
    ping -I "$STARLINK_IF" -c 1 -W 2 "$host" >/dev/null 2>&1 && return 0
  done

  return 1
}

# ---- aplicar / remover um modo completo ----

apply_starlink() {
  echo 1 > /proc/sys/net/ipv4/ip_forward
  cleanup_duplicate_rules
  ensure_rule_once
  ensure_iptables_self
}

# Mantém as regras Starlink saudáveis entre transições SEM limpar o cache de rotas
# (limpar reseta conexões ativas). Cada chamada aqui é um no-op idempotente quando já presente.
keepalive_starlink() {
  ensure_rule_once
  ensure_iptables_self
}

# Remove cada regra que este daemon adiciona — a ip rule de desvio e o
# NAT/forward autogerenciado. Cada remoção é um loop `while -C ... ; do -D`, então qualquer
# duplicata deixada por uma execução travada é totalmente removida, não apenas uma. Fonte única
# de verdade para "nossa pegada", usada pelo caminho 4G, stop, o trap de sinal e o baseline
# inicial — para que nenhum caminho de saída possa deixar uma regra fantasma que bloqueie o
# tráfego do hotspot. (A perigosa é a ip rule de desvio; se ela sumir, regras ACCEPT/MASQUERADE
# restantes não roteiam nada.)
purge_footprint() {
  cleanup_duplicate_rules
  teardown_iptables_self
}

apply_4g() {
  purge_footprint
}

dump_diag() {
  log DIAG "===== início do diag ($1) ====="
  ip rule 2>&1 | logblock "iprule"
  ip route show table "$STARLINK_TABLE" 2>&1 | logblock "sltable"
  ip route show 2>&1 | logblock "main"
  iptables -t nat -S POSTROUTING 2>&1 | logblock "natPOST"
  iptables -t nat -S tetherctrl_nat_POSTROUTING 2>&1 | logblock "natTC"
  iptables -S FORWARD 2>&1 | logblock "fwd"
  iptables -S tetherctrl_FORWARD 2>&1 | logblock "fwdTC"
  for h in $CHECK_HOSTS; do
    if ping -I "$STARLINK_IF" -c 1 -W 2 "$h" >/dev/null 2>&1; then
      log DIAG "ping| $h via $STARLINK_IF OK"
    else
      log DIAG "ping| $h via $STARLINK_IF FAIL"
    fi
  done
  log DIAG "===== fim do diag ====="
}

do_stop() {
  # kill_old_starlinkrouters mata o daemon via pidfile + varredura de /proc cmdline (o ps do toybox
  # não mostra args de script, então uma varredura por ps não encontra o daemon com setsid).
  kill_old_starlinkrouters
  purge_footprint
  ip route flush cache
  write_state "OFF"
  log INFO "Serviço parado + remoção concluída"
}

CMD="${1:-start}"

case "$CMD" in
  stop)
    do_stop
    exit 0
    ;;
  start)
    ;;
  *)
    echo "uso: $0 {start|stop}"
    exit 1
    ;;
esac

kill_old_starlinkrouters

echo $$ > "$PIDFILE"

# Em um kill gracioso (TERM/INT), remove cada regra que adicionamos antes de sair, para que nunca
# fique uma desvio ativo que bloqueie o hotspot. (Um SIGKILL não pode ser capturado — esse caso
# é coberto pela purga de baseline na próxima inicialização.) O trap EXIT simples é uma
# limpeza de pidfile de último recurso para qualquer outra saída.
trap 'purge_footprint; ip route flush cache; rm -f "$PIDFILE"; write_state "OFF"; log INFO "Serviço parado (pegada removida)"; exit 0' INT TERM
trap 'rm -f "$PIDFILE"' EXIT

echo 1 > /proc/sys/net/ipv4/ip_forward

log INFO "Serviço iniciado forçadamente"
log INFO "Hotspot=$HOTSPOT_IF | Starlink=$STARLINK_IF | Table=$STARLINK_TABLE | Ping=$CHECK_HOSTS"
log INFO "Histerese up=$UP_THRESHOLD down=$DOWN_THRESHOLD intervalo=${INTERVAL_SEC}s"

# Baseline limpo: inicia em 4G, sem desvio. A histerese governa as trocas a partir daqui.
apply_4g
current="4g"
write_state "4G"
log INFO "Modo baseline=4G (limpo)"
dump_diag "startup"

ok=0
fail=0
tick=0

while true; do
  trim_log

  if starlink_has_ping; then
    ok=$((ok + 1))
    fail=0
  else
    fail=$((fail + 1))
    ok=0
  fi

  want="$current"
  if [ "$current" != "starlink" ] && [ "$ok" -ge "$UP_THRESHOLD" ]; then
    want="starlink"
  fi
  if [ "$current" = "starlink" ] && [ "$fail" -ge "$DOWN_THRESHOLD" ]; then
    want="4g"
  fi

  if [ "$want" != "$current" ]; then
    if [ "$want" = "starlink" ]; then
      log INFO "Transição 4G -> STARLINK (ok_streak=$ok). Hotspot desviado por $STARLINK_IF."
      apply_starlink
      current="starlink"
      ip route flush cache
      write_state "STARLINK"
      dump_diag "to-starlink"
    else
      log WARN "Transição STARLINK -> 4G (fail_streak=$fail). Ping Starlink perdido."
      apply_4g
      current="4g"
      ip route flush cache
      write_state "4G"
      dump_diag "to-4g"
    fi
  else
    # Estado estável: mantém as regras saudáveis, sem flush de cache, sem churn de rota.
    if [ "$current" = "starlink" ]; then
      keepalive_starlink
      write_state "STARLINK"
    else
      write_state "4G"
    fi
  fi

  tick=$((tick + 1))
  if [ "$tick" -ge "$HEARTBEAT_EVERY" ]; then
    log INFO "Heartbeat: mode=$current ok=$ok fail=$fail"
    tick=0
  fi

  sleep "$INTERVAL_SEC"
done
