# Testes

Sem framework, sem dependências — execute-os diretamente. O CI roda ambos em cada PR e antes
de cada release.

## `rule_lifecycle_test.sh`
Teste com mocks do ciclo de vida do iptables / `ip rule` do daemon. Ele substitui `iptables` e
`ip` por mocks respaldados em arquivo de estado que modelam a semântica de `-C/-I/-D` e `ip rule add/del/list`,
e então conduz as funções reais de `starlinkrouter.sh` pelo fluxo apply → keepalive →
fallback 4G → recuperação de crash → stop. Verifica **ausência de acúmulo de regras** e **resíduo zero
após o teardown** (ou seja, sem regras fantasmas que poderiam criar um buraco negro no hotspot).

```sh
sh scripts/test/rule_lifecycle_test.sh app/src/main/assets/starlinkrouter.sh
```

## `TelnetRootTest.java`
Teste puro em JDK do parsing do `TelnetRoot`: negociação de opções IAC, framing de sentinel,
leituras fragmentadas (chunked), remoção de ANSI, desambiguação de entrada ecoada.

```sh
javac -d /tmp/tout \
  app/src/main/java/com/castilhoduarte/starlinkrouter/TelnetRoot.java \
  scripts/test/TelnetRootTest.java
java -cp /tmp/tout com.castilhoduarte.starlinkrouter.TelnetRootTest
```
