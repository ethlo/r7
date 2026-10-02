# Quickstart Guide

All r7 needs is a `routes.yaml` that says where to send traffic. Ports, limits, timeouts and
where journals go all have safe defaults, so there is no server configuration to write before
your first request. This guide runs r7 with Docker Compose in front of a small echo server, so
you can see routing, header injection and the request journal working.

## Directory Structure

Create a new directory with two files:

```text
r7-quickstart/
├── docker-compose.yaml
└── config/
    └── routes.yaml
```

## Docker Compose Setup

Create `docker-compose.yaml`:

```yaml
services:
  r7:
    image: ghcr.io/ethlo/r7-gateway:latest
    ports:
      - "9999:8888"              # the gateway
      - "127.0.0.1:19999:18888"  # the dashboard, on this machine only (see below)
    volumes:
      - ./config:/app/config:ro
      - r7-journals:/journals
    depends_on:
      - echo-server

  echo-server:
    image: ealen/echo-server:latest
    environment:
      - PORT=8080

volumes:
  r7-journals:
```

Journals go to a named volume rather than a host directory: Docker prepares a new named volume
so the image's non-root user can write to it, which a directory you create yourself would not be.

The dashboard port has no authentication and shows the gateway's configuration.
`127.0.0.1:19999:18888` keeps it on this machine: a bare `19999:18888` would publish it on every
host interface, and Docker's own firewall rules bypass host firewalls such as ufw. Do not route a
path on the gateway port to it either. To reach it from elsewhere, put it behind something that
authenticates, and list the name you use for it under `management.allowed_hosts` (see the
[config reference](config.md#management-configuration-management)).

## Routes Configuration

Create `config/routes.yaml`. This routes all incoming traffic to the echo server, injects a correlation ID, adds a custom response header, and turns on full journaling to demonstrate the I/O logging layer.

```yaml
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

## Next steps

* Add routes, predicates and filters: see the [config reference](config.md).
* Decide what each route records in its journal, and read it back: see [Journaling](journaling.md).
* Change ports, connection limits, timeouts, trusted proxies or the journal location only when you
  need to, in an optional `config/server.yaml`: see [Server Configuration](config.md#9-server-configuration).
* For production JVM flags and journal storage, see [Performance tuning](performance_tuning.md).
