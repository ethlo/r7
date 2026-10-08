# Quickstart Guide

All r7 needs is a `routes.yaml` that says where to send traffic. Ports, limits, timeouts and
where journals go all have safe defaults, so there is no server configuration to write before
your first request. This guide runs r7 with Docker Compose in front of a small echo server. In
five minutes you route a request, see the headers r7 added, and read back the record r7 kept of
it, with the secret in it replaced by a fingerprint.

## Directory Structure

Create a new directory with two files:

```text
r7-quickstart/
├── docker-compose.yaml
└── routes.yaml
```

## Docker Compose Setup

Create `docker-compose.yaml`:

```yaml title="docker-compose.yaml"
services:
  r7:
    image: ghcr.io/ethlo/r7-gateway:main
    ports:
      - "9999:8888"              # the gateway
      - "127.0.0.1:19999:18888"  # the dashboard, on this machine only (see below)
    environment:
      # The key behind the fingerprints r7 journals in place of secrets; see below.
      R7_FINGERPRINT_KEY: ${R7_FINGERPRINT_KEY:?set it in .env}
    volumes:
      - ./routes.yaml:/app/config/routes.yaml:ro
      - r7-journals:/journals
    depends_on:
      - echo-server

  # Reads the journals and prints one JSON line per exchange; bodies go to WARC files.
  r7-tailer:
    image: ghcr.io/ethlo/r7-tailer:main
    volumes:
      - r7-journals:/journals:ro
      - r7-warc:/warc:rw
      - r7-tailer-checkpoints:/checkpoints:rw

  echo-server:
    image: ealen/echo-server:latest
    environment:
      - PORT=8080

volumes:
  r7-journals:
  r7-warc:
  r7-tailer-checkpoints:
```

Next to it, create the fingerprint key once, and keep the file:

```bash
(umask 077; set -C; echo "R7_FINGERPRINT_KEY=$(openssl rand -base64 32)" > .env)
```

r7 does not start without the key: the journal stores
[fingerprints](journaling.md#redacted-header-and-query-parameter-values) made with it in place of
values that may be secrets. Compose reads `.env` on its own. The `umask` makes the file readable
by you alone, and `set -C` refuses to overwrite a key you already have; keep it out of version
control.

Journals go to a named volume rather than a host directory: Docker prepares a new named volume
so the image's non-root user can write to it, which a directory you create yourself would not be.

The dashboard has no authentication, so `127.0.0.1:19999:18888` keeps it on this machine: a bare
`19999:18888` would publish it on every host interface, past host firewalls such as ufw. Before
exposing it, read [Management Configuration](config.md#management-configuration-management).

## Routes Configuration

Create `routes.yaml`. This routes all incoming traffic to the echo server, injects a correlation ID, adds a custom response header, and records every request and response in full.

```yaml title="routes.yaml"
global_filters:
  - AddCorrelationId
  - SimpleMetrics
routes:
  - id: quickstart-echo-route
    upstream:
      targets:
        - url: http://echo-server:8080
    match:
      - PathPrefix:
          prefix: /
    filters:
      - AddResponseHeader:
          name: X-Proxied-By
          value: Ethlo R7
    journal:
      request:
        level: FULL
      response:
        level: FULL

```

## Running and Testing

Start the stack using Docker Compose:

```bash
docker compose up -d

```

Once the containers are up, test the gateway by sending a request to the mapped port (`9999`):

```bash
curl -i http://localhost:9999/api/test

```

**Expected Output:**

You should see an HTTP 200 OK response originating from the `echo-server`, decorated with the headers injected by r7:

```http
HTTP/1.1 200 OK
X-Proxied-By: Ethlo R7
X-Correlation-Id: <generated-uuid>
Date: ...
Content-Type: application/json; charset=utf-8
...

{
  "host": {
    "hostname": "echo-server",
    "ip": "::ffff:172.21.0.2",
    "ips": []
  },
  "http": {
    "method": "GET",
    "baseUrl": "",
    "originalUrl": "/api/test",
    "protocol": "http"
  },
  "request": {
    "params": {
      "0": "/api/test"
    },
    "query": {},
    "cookies": {},
    "body": {},
    "headers": {
      "host": "localhost:9999",
      "user-agent": "curl/...",
      "accept": "*/*",
      "x-correlation-id": "<generated-uuid>"
    }
  }
}

```

Open [http://localhost:19999](http://localhost:19999) to see the route, its traffic and response
times on the dashboard.

## See what r7 recorded

Send a request that carries a secret:

```bash
curl -s -o /dev/null http://localhost:9999/api/orders -H "Cookie: session=my-secret-session"
```

The tailer prints each exchange as one JSON line. Show the latest:

```bash
docker compose logs --no-log-prefix r7-tailer | grep '"request_id"' | tail -n 1
```

Abbreviated and pretty-printed, it looks like this:

```json
{
  "request_id": "01JA0Q6R8X4V2N7M3K5T9B1C0D",
  "route_id": "quickstart-echo-route",
  "duration": 0.003120,
  "client_request": {"method": "GET", "path": "/api/orders",
                     "headers": {"host": ["localhost:9999"], "cookie": ["fp:R5XU2m_VAik"]}},
  "client_response": {"status": 200, "headers": {"content-type": ["application/json; charset=utf-8"]}, "body_bytes": 612},
  "warc": {"file": "r7-1759320000000-6f1c….warc.zst", "offset": 0, "length": 1873}
}
```

The session never reached the journal: `cookie` is not on the safe list, so it is stored as
a [fingerprint](journaling.md#redacted-header-and-query-parameter-values). The same value always
gives the same fingerprint, so you can still tell which requests carried it. The response body
is in the WARC file the `warc` field points to, in the `r7-warc` volume.

That is the record r7 keeps of every request on this route, with no extra system to run.

## Next steps

* Add routes, predicates and filters: see the [config reference](config.md).
* Decide what each route records, and ship the JSON lines to ClickHouse, Loki or any OpenTelemetry backend: see [Journaling](journaling.md) and [Where r7 fits](where-r7-fits.md).
* Change ports, connection limits, timeouts, trusted proxies or the journal location only when you
  need to, in an optional `server.yaml` mounted beside it at `/app/config/server.yaml`: see [Server Configuration](config.md#9-server-configuration).
* For production JVM flags and journal storage, see [Performance tuning](performance_tuning.md).
