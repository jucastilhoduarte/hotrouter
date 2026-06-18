# Tests

No framework, no dependencies — run them directly. CI runs both on every PR and before
every release.

## `rule_lifecycle_test.sh`
Mock-backed test of the daemon's iptables / `ip rule` lifecycle. It stubs `iptables` and
`ip` with state-file-backed mocks that model `-C/-I/-D` and `ip rule add/del/list`
semantics, then drives the real functions from `hotrouter.sh` through apply → keepalive →
4G fallback → crash-recovery → stop. Asserts **no rule accumulation** and **zero residue
after teardown** (i.e. no ghost rules that could black-hole the hotspot).

```sh
sh scripts/test/rule_lifecycle_test.sh app/src/main/assets/hotrouter.sh
```

## `TelnetRootTest.java`
Pure-JDK test of `TelnetRoot`'s parsing: IAC option negotiation, sentinel framing,
chunked reads, ANSI stripping, echoed-input disambiguation.

```sh
javac -d /tmp/tout \
  app/src/main/java/com/castilhoduarte/hotrouter/TelnetRoot.java \
  scripts/test/TelnetRootTest.java
java -cp /tmp/tout com.castilhoduarte.hotrouter.TelnetRootTest
```
