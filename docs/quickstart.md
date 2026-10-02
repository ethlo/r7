# Quickstart Guide

This guide will get you up and running with the r7 gateway using Docker Compose. We will deploy the gateway alongside a simple echo server backend to demonstrate routing, header injection, and traffic journaling in action.

## Directory Structure

Create a new directory for your project and set up the following structure. The `config` directory will hold our YAML configurations, and the `journals` directory will be used for memory-mapped logging.

```text
r7-quickstart/
├── docker-compose.yaml
├── config/
│   ├── server.yaml # optional
│   └── routes.yaml
└── journals/

```

## Docker Compose Setup

Create `docker-compose.yaml`. This includes the r7 gateway configured with ZGC and native memory access, alongside an `echo-server` acting as our dummy backend.

```yaml
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    container_name: ethlo-r7-gateway
    ports:
      - "9999:8888"   # Main gateway port
      - "127.0.0.1:19999:18888" # Status and metrics port, on loopback only (see below)
    volumes:
      - ./config:/app/config:ro
      - ./journals:/journals:rw
    environment:
      # Formatted as a single string to ensure Docker Compose parses it correctly
      - JAVA_TOOL_OPTIONS=-XX:+UseZGC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow
    deploy:
      resources:
        limits:
          memory: 600M
          cpus: "16.0"
        reservations:
          memory: 400M
    # No healthcheck: the image is distroless, with no shell, curl or wget for one to run. Probe
    # the gateway port from outside instead (a TCP connect to 8888, or the orchestrator's check).
    restart: unless-stopped
    depends_on:
      - echo-server

  echo-server:
    image: ealen/echo-server:latest
    container_name: r7-echo-backend
    environment:
      - PORT=8080
    restart: unless-stopped

```

The status and metrics port has no authentication and shows the gateway's configuration. Inside
the container the image listens on all interfaces, which the port mapping needs, so the mapping is
what decides who can reach it. `127.0.0.1:19999:18888` keeps it on this machine: a bare
`19999:18888` would publish it on every host interface, and Docker's own firewall rules bypass
host firewalls such as ufw. Do not route a path on the gateway port to it either. To reach it from
elsewhere, put it behind something that authenticates, and list the name you use for it under
`management.allowed_hosts` (see the [config reference](config.md#management-configuration-management)).

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

Once the containers are healthy, test the gateway by sending a request to the mapped port (`9999`):

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