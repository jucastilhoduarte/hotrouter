#!/bin/sh
# Teste de ciclo de vida com mocks para o gerenciamento de regras iptables/ip do starlinkrouter.sh.
# Verifica: sem acúmulo de regras entre ciclos, e o teardown completo não deixa NENHUM resíduo
# (sem regras fantasmas que possam criar buracos negros no hotspot).

set -u
ASSET="${1:?usage: rule_lifecycle_test.sh /path/to/starlinkrouter.sh}"
TMP="$(mktemp -d)"
STORE="$TMP/iptables"      # linhas: TABLE|CHAIN|SPEC
IPSTORE="$TMP/iprules"     # linhas: rule|<spec>
: > "$STORE"; : > "$IPSTORE"

TC_EXISTS=0          # as chains tetherctrl_* do sistema existem nesta execução?
SL_TABLE_DEFAULT=1   # a tabela wlan0 tem uma rota padrão?

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1 ($2)"; }
asserteq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "want=$3 got=$2"; fi; }

# ---- mock iptables ----
iptables() {
  tbl=filter; op=""; chain=""; spec=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -t) tbl="$2"; shift 2 ;;
      -C|-I|-A|-D|-S|-nL|-L)
        op="$1"; chain="$2"; shift 2
        case "$op" in
          -I|-A) case "${1:-}" in ''|*[!0-9]*) : ;; *) shift ;; esac ;;
        esac
        spec="$*"; shift $# 2>/dev/null || set -- ;;
      *) shift ;;
    esac
  done
  key="$tbl|$chain|$spec"
  case "$op" in
    -nL|-L)
      case "$chain" in
        tetherctrl_nat_POSTROUTING|tetherctrl_FORWARD|tetherctrl_counters)
          [ "$TC_EXISTS" = 1 ] ; return $? ;;
        *) return 0 ;;
      esac ;;
    -C) grep -qxF "$key" "$STORE" ; return $? ;;
    -I|-A) echo "$key" >> "$STORE" ; return 0 ;;   # o iptables real sempre insere
    -D) awk -v k="$key" 'BEGIN{d=0} $0==k && !d {d=1; next} {print}' "$STORE" > "$STORE.t"
        mv "$STORE.t" "$STORE" ; return 0 ;;
    *) return 0 ;;
  esac
}

# ---- mock ip ----
ip() {
  sub="$1"; shift
  case "$sub" in
    rule)
      case "${1:-}" in
        add) shift; echo "rule|$*" >> "$IPSTORE" ;;
        del) shift
             awk -v k="rule|$*" 'BEGIN{d=0} $0==k && !d {d=1; next} {print}' "$IPSTORE" > "$IPSTORE.t"
             mv "$IPSTORE.t" "$IPSTORE" ;;
        ''|*)  # listar
             while IFS= read -r l; do
               case "$l" in rule\|*) printf '17999:\t%s\n' "${l#rule|}" ;; esac
             done < "$IPSTORE" ;;
      esac ;;
    route)
      case "${1:-}" in
        show) if [ "${2:-}" = table ]; then
                [ "$SL_TABLE_DEFAULT" = 1 ] && echo "default via 100.64.0.1 dev wlan0"
              fi ;;
        flush) : ;;
      esac ;;
    link) return 0 ;;
  esac
}
ping() { return 0; }   # simula Starlink acessível quando sondado diretamente

# ---- carrega as funções do daemon (tudo antes do despacho CMD) ----
awk '/^CMD=/{exit} {print}' "$ASSET" > "$TMP/funcs.sh"
. "$TMP/funcs.sh"

# silencia helpers com efeitos colaterais (escrevem em /data/local/tmp & /proc)
log() { :; }
logblock() { cat >/dev/null; }
write_state() { :; }
trim_log() { :; }
dump_diag() { :; }

total() { echo $(( $(wc -l < "$STORE") + $(wc -l < "$IPSTORE") )); }
nrules() { grep -cxF "$1" "$STORE"; }
niprule() { grep -c "iif wlan2 lookup wlan0" "$IPSTORE"; }

SELF_NAT="nat|POSTROUTING|-o wlan0 -j MASQUERADE"
SELF_FWD1="filter|FORWARD|-i wlan2 -o wlan0 -j ACCEPT"
SELF_FWD2="filter|FORWARD|-i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT"

echo "== Cenário 1: apenas self-managed (sem tetherctrl) =="
TC_EXISTS=0
apply_starlink 2>/dev/null
asserteq "ip rule presente uma vez"     "$(niprule)"        1
asserteq "nat MASQUERADE uma vez"      "$(nrules "$SELF_NAT")"  1
asserteq "fwd wlan2->wlan0 uma vez"    "$(nrules "$SELF_FWD1")" 1
asserteq "fwd wlan0->wlan2 uma vez"    "$(nrules "$SELF_FWD2")" 1
asserteq "zero regras tetherctrl"      "$(grep -c tetherctrl "$STORE")" 0
asserteq "total = 4"                "$(total)"          4

echo "== Cenário 2: 50 passagens de keepalive NÃO devem acumular =="
i=0; while [ $i -lt 50 ]; do keepalive_starlink 2>/dev/null; i=$((i+1)); done
asserteq "ip rule ainda uma vez"       "$(niprule)"        1
asserteq "nat ainda uma vez"           "$(nrules "$SELF_NAT")"  1
asserteq "total ainda 4"            "$(total)"          4

echo "== Cenário 3: 10 re-transições para starlink NÃO devem acumular =="
i=0; while [ $i -lt 10 ]; do apply_starlink 2>/dev/null; i=$((i+1)); done
asserteq "ip rule ainda uma vez"       "$(niprule)"        1
asserteq "total ainda 4"            "$(total)"          4

echo "== Cenário 4: fallback para 4G remove tudo =="
apply_4g
asserteq "zero resíduo após 4G"    "$(total)"          0

echo "== Cenário 5: chains tetherctrl existem, mas o caminho Starlink NÃO deve tocá-las =="
TC_EXISTS=1
apply_starlink 2>/dev/null
asserteq "ainda apenas 4 regras self"     "$(total)"  4
asserteq "zero regras tetherctrl adicionadas" "$(grep -c tetherctrl "$STORE")" 0
i=0; while [ $i -lt 30 ]; do keepalive_starlink 2>/dev/null; i=$((i+1)); done
asserteq "ainda 4 após 30 keepalives" "$(total)"  4
apply_4g
asserteq "4G zera tudo"           "$(total)"  0

echo "== Cenário 6: recuperação de crash — fantasmas self + ip rule, depois baseline de inicialização =="
: > "$STORE"; : > "$IPSTORE"
apply_starlink 2>/dev/null
# simula uma execução encerrada com SIGKILL que deixou regras self duplicadas + ip rules de desvio extras
echo "$SELF_NAT" >> "$STORE"
echo "$SELF_FWD1" >> "$STORE"
echo "rule|from all iif wlan2 lookup wlan0 priority 17999" >> "$IPSTORE"
echo "rule|from all iif wlan2 lookup wlan0 priority 17999" >> "$IPSTORE"
[ "$(total)" -gt 4 ] && ok "fantasmas presentes antes do baseline ($(total))" || bad "configuração de fantasmas" "got $(total)"
apply_4g    # o que o baseline de inicialização executa
asserteq "baseline remove self + ip fantasmas e zera" "$(total)"  0

echo "== Cenário 7: purge estilo do_stop a partir de um estado starlink limpo =="
TC_EXISTS=0
apply_starlink 2>/dev/null
purge_footprint
asserteq "stop não deixa resíduo" "$(total)"          0

echo
echo "$PASS passou(aram), $FAIL falhou(aram)"
rm -rf "$TMP"
[ "$FAIL" -eq 0 ]
