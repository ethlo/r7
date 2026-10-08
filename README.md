![Status](https://img.shields.io/badge/status-pre--release-orange)

# r7: the gateway that remembers every request

When someone asks what a client actually sent you, r7 has the answer. It routes traffic to your
services and records every exchange, with its headers or its full bodies, to local disk, with
secrets stored as fingerprints. A tailer ships the record as WARC and JSON lines into the tools
you already run. One `routes.yaml`, checked before it serves a single request.

```yaml title="routes.yaml"
# routes.yaml
routes:
  - id: api
    match:
      - PathPrefix:
          prefix: /api
    upstream:
      targets:
        - url: http://host.docker.internal:3000
    journal:
      request:
        level: HEADERS
      response:
        level: HEADERS
```

```bash
(umask 077; set -C; echo "R7_FINGERPRINT_KEY=$(openssl rand -base64 32)" > .env)   # once: keep it, and keep it private
docker run --rm -p 8888:8888 -p 127.0.0.1:18888:18888 \
  --add-host=host.docker.internal:host-gateway \
  --env-file .env \
  -v "$PWD/routes.yaml:/app/config/routes.yaml:ro" \
  -v r7-journals:/journals \
  ghcr.io/ethlo/r7-gateway:main
```

That's a running gateway, with every request to `/api` journaled and a live dashboard on
`http://localhost:18888`.

## Why r7

- **It remembers every request.** Record metadata, headers or full bodies per route, and turn up
  the detail on its own when a response fails. Entries go to memory-mapped files on local disk,
  with no network call while the client waits, and a tailer ships them as JSON lines, a WARC
  archive, or both.
- **Your configuration is checked before it runs.** Unknown keys, misspelled filters, bad regular
  expressions and missing variables stop r7 at startup with a precise message, not a silent
  misroute in production. A bad edit to a running gateway is rejected and the old routes keep
  serving.
- **Routing you can read top to bottom.** Routes match in the order you wrote them and the first
  match wins. Each filter does one job. No scripting language, no hidden precedence rules.
- **Extensions in plain Java.** Each request stage has its own filter interface that hands over
  only what may change there, and every request runs on one virtual thread, upstream call
  included: no reactive chain and no worker pools.

r7 does not terminate TLS, and has no scripting language: see
[where r7 fits, and where it does not](https://r7.ethlo.com/#use-something-else-when).

## Learn more

- [Quickstart](https://r7.ethlo.com/quickstart/)
- [Configuration reference](https://r7.ethlo.com/config/)
- [Journaling](https://r7.ethlo.com/journaling/)
- [Licensing](https://r7.ethlo.com/licensing/): BSL 1.1, free for organisations under $25M revenue, converting to Apache 2.0 after three years

r7 is pre-release: configuration and APIs may change before 1.0. Built on Java 25+ and Helidon Níma.
