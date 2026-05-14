# Server Configuration Reference

The `server.yaml` file controls the foundational infrastructure of the r7 proxy. This includes network binding, thread pool sizing, low-level socket behaviors, upstream connection pooling, and disk-backed storage configurations for journaling.

---

## Root Configuration

Defines the primary listening interfaces and ports for both the proxy and its internal status endpoints.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The IP address or interface the primary proxy binds to (e.g., `0.0.0.0` for all interfaces). |
| `port` | Integer | The primary port the proxy listens on for incoming traffic. |
| `status_host` | String | The interface for the internal status and metrics server. |
| `status_port` | Integer | The port for the internal status and metrics server. |

---

## Worker Options (`worker`)

Controls the threading model and connection watermark levels. Proper tuning here is critical for high-throughput, low-allocation environments.

| Parameter | Type | Description |
| --- | --- | --- |
| `io_threads` | Integer | The number of non-blocking I/O threads. Typically mapped 1:1 with available physical CPU cores. |
| `task_core_threads` | Integer | The minimum number of threads in the blocking task pool. |
| `task_max_threads` | Integer | The maximum number of threads in the blocking task pool. Keeping this close to the core count prevents excessive context switching. |
| `stack_size` | Integer | Thread stack size in bytes (e.g., `262144` for 256KB). |
| `connection_low_water` | Integer | The lower bound watermark for concurrent backend connections. |
| `connection_high_water` | Integer | The upper bound watermark for concurrent backend connections. |

---

## Server Options (`options`)

Configures the HTTP server layer, including buffer management, protocol support, and request parsing limits.

| Parameter | Type | Description |
| --- | --- | --- |
| `buffer_size` | Integer | The size of the byte buffers used for I/O operations (e.g., `8192` for 8KB). Tuning this to fit into L1/L2 cache can improve performance. |
| `direct_buffers` | Boolean | When `true`, uses off-heap memory (Direct Buffers) to minimize JVM garbage collection and eliminate memory copies during socket I/O. |
| `tcp_nodelay` | Boolean | Disables Nagle's algorithm to reduce latency for small packets. |
| `enable_http2` | Boolean | Enables HTTP/2 protocol support. |
| `always_set_keep_alive` | Boolean | Forces the server to send the `Connection: keep-alive` header to maintain persistent connections. |
| `max_header_size` | Integer | The maximum allowed size for a single HTTP header, in bytes. |
| `max_header_count` | Integer | The maximum number of HTTP headers allowed per request. |
| `request_parse_timeout` | Integer | The timeout in milliseconds for parsing an incoming HTTP request. |

---

## Proxy Client (`proxy`)

Configures the behavior of the internal reverse proxy client that connects to upstream targets.

| Parameter | Type | Description |
| --- | --- | --- |
| `ttl` | Integer | The time-to-live in milliseconds for idle upstream connections in the pool. |
| `max_request_time` | Integer | The absolute maximum time in milliseconds a proxy request is allowed to take before timing out. |
| `connections_per_thread` | Integer | The maximum number of pooled upstream connections allowed *per worker thread*. |
| `max_queue_size` | Integer | The maximum number of pending requests allowed to queue while waiting for an available upstream connection. |

---

## Socket Layer (`socket`)

Low-level operating system socket configurations for the listener.

| Parameter | Type | Description |
| --- | --- | --- |
| `tcp_nodelay` | Boolean | Disables Nagle's algorithm at the server socket level. |
| `reuse_addresses` | Boolean | Enables `SO_REUSEADDR`, allowing the server to bind to an address/port that is currently in a `TIME_WAIT` state. |
| `backlog` | Integer | The maximum OS-level queue length for incoming connection requests. |
| `read_timeout` | Integer | The socket read timeout in milliseconds. |

---

## Storage & Journaling (`storage`)

Configures the disk-backed storage mechanism used for high-speed request and response journaling.

| Parameter | Type | Description |
| --- | --- | --- |
| `work_dir` | String | The directory path where the memory-mapped journal files are stored. |
| `shard_count` | Integer | The number of shards (files) to split the journal across to reduce lock contention and manage file sizes. |
| `shard_size` | Integer | The target size limit in bytes for a single journal shard (e.g., `500000000` for 500MB). |
| `pre_fault` | Boolean | When `true`, pre-allocates and forces the OS to fault the memory-mapped pages immediately, trading startup time for reduced runtime latency. |