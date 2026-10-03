![Status](https://img.shields.io/badge/status-pre--release-orange)

# r7: the gateway that remembers every request

r7 is an HTTP gateway you configure with one YAML file. It routes traffic to your services, checks
your configuration before it ever runs, and keeps a record of every request that passed through.
It is fast out of the box, with high throughput and a short tail and nothing to tune first.

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
docker run --rm -p 8888:8888 -p 127.0.0.1:18888:18888 \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD/routes.yaml:/app/config/routes.yaml:ro" \
  -v r7-journals:/journals \
  ghcr.io/ethlo/r7-gateway:latest
```

That's a running gateway, with every request to `/api` journaled and a live dashboard on
`http://localhost:18888`.

## Why r7

- **It remembers every request.** Record metadata, headers or full bodies per route, and turn up
  the detail only when a response fails. Journals are written to local disk without slowing the
  request, and a tailer ships them to a JSON pipeline and a WARC archive.
- **Your configuration is checked before it runs.** Unknown keys, misspelled filters, bad regular
  expressions and missing variables stop r7 at startup with a precise message, not a silent
  misroute in production. A bad edit to a running gateway is rejected and the old routes keep
  serving.
- **Routing you can read top to bottom.** Routes match in the order you wrote them and the first
  match wins. Each filter does one job. No scripting language, no hidden precedence rules.

## Learn more

- [Quickstart](https://r7.ethlo.com/quickstart/)
- [Configuration reference](https://r7.ethlo.com/config/)
- [Journaling](https://r7.ethlo.com/journaling/)
- [Licensing](https://r7.ethlo.com/licensing/): BSL 1.1, free for organisations under $25M revenue, converting to Apache 2.0 after three years

r7 is pre-release: configuration and APIs may change before 1.0. Built on Java 25+ and Helidon Níma.
