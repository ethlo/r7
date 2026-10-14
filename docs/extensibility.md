# Custom Filters and Predicates

A predicate or filter of your own is a Java class, a config record and one line in a
`META-INF/services` file. Drop the jar on the gateway's classpath and its name works in
`routes.yaml` like a built-in one, with the same validation at startup.

## How a request runs

In the gateway (the container images and the jar, on Helidon Níma), each request runs start to
finish on one virtual thread, the upstream call included. There is no reactive chain, no
callback to register and no handoff to a worker pool. A filter is ordinary blocking code: it can
call a database, a cache or another service directly and return when it is done, and a stack
trace points at the line that failed. Embedded in a servlet container (`r7-servlet`), a request
runs the same way on the container's request thread instead, so give the container virtual
threads or size its pool for your upstream and filter latency.

A filter implements the interface of the stage it works in, and each stage hands it only what
may change at that point:

| Stage | Interface | Can read | Can change |
| --- | --- | --- | --- |
| Client request | `ClientRequestGatewayFilter` | the request as the client sent it | nothing on the request; it can answer the client itself (`shortCircuit`) or cap the body size (`limitRequestBody`) |
| Upstream request | `UpstreamRequestGatewayFilter` | the client's request | the request r7 sends upstream: path, query, headers, cookies, method; or answer the client itself |
| Client response | `ClientResponseGatewayFilter` | the client's request, the upstream request and response | the status and headers the client gets |
| Completed | `CompletedGatewayFilter` | the whole exchange, once the response has been sent | nothing that was sent |

The read-only views are separate types (`GatewayRequest`, `GatewayResponse`) from the writable
ones (`MutableGatewayRequest`, `MutableGatewayResponse`), so the compiler enforces the table: no
filter can change the request as the client sent it, and the journal's record of it is what
arrived. Bodies are streamed through and are not changed by filters. A value one filter hands
to a later one, such as an authenticated user, goes in a typed attachment
(`setAttachment` with a `StateKey`); the exchange's attributes are for telemetry and the journal,
and can appear in its output.

One class can implement several of these interfaces when it acts in more than one stage, as the
built-in `RateLimiter` and `CircuitBreaker` do. When each stage runs, and in which order root,
group and route filters run in it, is in [Execution Semantics](config.md#3-execution-semantics).

A filter that needs to know the request is being retried on a fallback route implements
`FallbackAwareGatewayFilter`; `onFallback` is called on the request-phase filters the fallback
inherits (the root's, the first route's group's and its own), before the fallback's own filters run. A `CompletedGatewayFilter`
can read `CompletedGatewayExchange.upstreamOutcome()`: `NOT_ASKED`, `ANSWERED`, `NO_RESPONSE`,
`BODY_FAILED` or `CLIENT_ABORTED`, with the time to the response head. That is what `CircuitBreaker` judges by.

## 1. Implementing a Custom Predicate

A Predicate evaluates an incoming `GatewayRequest` and returns a boolean indicating whether the route should match.

To create a custom predicate, you must implement the `GatewayPredicateFactory` interface. This factory handles configuration validation and instantiates the actual `GatewayPredicate`.

Here is an example of a simple custom predicate that checks if a specific HTTP header is present:

```java
package com.example.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class HasHeaderFactory implements GatewayPredicateFactory<HasHeaderFactory.Config> {
    
    private static final String PREDICATE_NAME = "HasHeader";

    @Override
    public String name() {
        return PREDICATE_NAME;
    }

    @Override
    public Class<Config> configClass() {
        return Config.class;
    }

    @Override
    public GatewayPredicate create(final Config config) {
        return new GP(config);
    }

    // 1. Define the Configuration Record
    public record Config(String headerName) implements ValidatableConfig {
        @Override
        public void validate(final ValidationResult result) {
            new ValidatorUtils(result).required("header_name", this.headerName());
        }
    }

    // 2. Define the execution logic
    private static final class GP implements GatewayPredicate, ShortInfo {
        private final String headerName;

        public GP(final Config config) {
            this.headerName = config.headerName();
        }

        @Override
        public boolean test(final GatewayRequest request) {
            final String headerValue = request.headers().getFirst(this.headerName);
            if (headerValue != null) {
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return PREDICATE_NAME;
        }

        @Override
        public String summary() {
            return PREDICATE_NAME + ": " + this.headerName;
        }
    }
}

```

**YAML Usage:**

<!-- docs-check: skip, the predicate and filter only exist with this page's plugin on the classpath -->
```yaml
match:
  - HasHeader:
      header_name: X-My-Custom-Header

```

### The predicate contract

r7 may test a predicate more than once per exchange, and when routes share a group, once for all of them. A predicate therefore has no side effects and gives the same answer for the same request every time: no counters, no random draws, no calls out. Sampling is `Sample`'s, a node r7 evaluates over the exchange (see [Sample](config.md#sample)).

r7 handles `leg` for every predicate: it takes the parameter out before the configuration binds, and hands the predicate the request of that leg, or answers `false` without asking when the leg has no request yet. A config record with a component named `leg` is refused when the gateway starts, as is a predicate named `and`, `or`, `not` or `Sample`.

### Response predicates

A predicate that tests a response implements `GatewayResponsePredicate` (`boolean test(GatewayResponse response)`) and is created by a `GatewayResponsePredicateFactory`, with the same `name`, `configClass` and `create` as a request predicate's factory. Request and response predicates share one namespace: a name registered as both is refused when the gateway starts. A route's `match` refuses a response predicate, since no response exists when a route is chosen: `[routes.x.match[0].HasStatus] HasStatus tests a response, which does not exist yet when a route is chosen; use it in on_response.`

---

## 2. Implementing a Custom Filter

To create a custom filter, implement `GatewayFilterFactory`. The filter it creates implements the interface of each stage it works in, from the table above.

Here is an example of an advanced filter that blocks traffic based on a custom token and short-circuits the connection with a `403 Forbidden` if validation fails:

```java
package com.example.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class BlockTokenFilterFactory implements GatewayFilterFactory<BlockTokenFilterFactory.Config> {
    
    private static final String FILTER_NAME = "BlockToken";
    private static final byte[] REJECT_PAYLOAD = "Access Denied".getBytes(StandardCharsets.UTF_8);

    @Override
    public String name() {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass() {
        return Config.class;
    }

    @Override
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext) {
        return new GF(config);
    }

    // 1. Configuration
    public record Config(String tokenHeader, String blockedToken) implements ValidatableConfig {
        @Override
        public void validate(final ValidationResult result) {
            new ValidatorUtils(result)
                    .required("token_header", this.tokenHeader())
                    .required("blocked_token", this.blockedToken());
        }
    }

    // 2. Execution Logic
    private static final class GF implements ClientRequestGatewayFilter, ShortInfo {
        private final String tokenHeader;
        private final String blockedToken;

        public GF(final Config config) {
            this.tokenHeader = config.tokenHeader();
            this.blockedToken = config.blockedToken();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange) {
            final String incomingToken = exchange.clientRequest().headers().getFirst(this.tokenHeader);

            if (incomingToken != null) {
                if (incomingToken.toString().equals(this.blockedToken)) {
                    // Short-circuit the request immediately. Do not route upstream.
                    exchange.shortCircuit(new ShortCircuitGatewayResponse(
                            HttpStatuses.FORBIDDEN,
                            MediaTypes.TEXT_PLAIN_UTF8,
                            ByteBuffer.wrap(REJECT_PAYLOAD)
                    ));
                }
            }
        }

        @Override
        public String name() {
            return FILTER_NAME;
        }

        @Override
        public String summary() {
            return FILTER_NAME + " blocking " + this.blockedToken;
        }
    }
}

```

**YAML Usage:**

<!-- docs-check: skip, the predicate and filter only exist with this page's plugin on the classpath -->
```yaml
filters:
  - BlockToken:
      token_header: X-Security-Token
      blocked_token: "malicious-token-123"

```

### Where a filter may be declared

A filter declared in the root's or a group's `filters` is one instance for every route beneath it. When that is wrong for your filter, because its state belongs to one upstream as a `CircuitBreaker`'s does, override `levels()` on the factory:

```java
@Override
public Set<DeclarationLevel> levels() {
    return Set.of(DeclarationLevel.ROUTE);
}
```

The routes then refuse to load with it anywhere else (`[filters[0]] BlockToken can be declared on a route only, not on the root.`), and the editor flags it there. Left out, a filter may be declared on every level.

A predicate factory does the same for legs. A predicate that only means something on the request as the client sent it overrides `legs()`, as `RemoteAddr` does, and `leg: upstream` is then refused for it and left out of its schema:

```java
@Override
public Set<Leg> legs() {
    return Set.of(Leg.CLIENT);
}
```

### Reporting a filter's status

A filter that keeps state an operator should see, such as a breaker that trips or a quota that runs out, can implement `StatusReporting`. Its status then shows on the dashboard, in the management JSON and as the `r7_component_health` and `r7_component_value` metrics (see [Management Configuration](config.md#management-configuration-management)).

```java
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.StatusReporting;

private static final class QuotaFilter implements ClientRequestGatewayFilter, ShortInfo, StatusReporting
{
    private final LongAdder refused = new LongAdder();
    private volatile boolean exhausted;

    // onClientRequest, name() and summary() as above

    @Override
    public ComponentStatus status()
    {
        return new ComponentStatus(
                exhausted ? ComponentStatus.Health.ERROR : ComponentStatus.Health.OK,
                exhausted ? "Quota used up until midnight" : null,
                Map.of("refused_requests", refused.sum()));
    }
}
```

`status()` is called every 2 seconds from the management snapshot, never on a request, and must not block: read the state the filter already keeps. Health is `OK`, `WARN` or `ERROR`; the detail is a short line for the dashboard, never a metric. Value names are lower-case `snake_case`, and each becomes a series, so a name must never carry data such as a client address. A `status()` that throws is reported as `ERROR` with the exception as its detail.

---

## 3. Registering the Extensions (ServiceLoader SPI)

For the r7 engine to discover your custom implementations, you must declare them in the `META-INF/services` directory of your compiled JAR file. The filename must exactly match the fully qualified name of the SPI interface, and the content must be the fully qualified name of your implementation class.

**File 1: Predicate Registration**
Create the file: `src/main/resources/META-INF/services/com.ethlo.r7.spi.GatewayPredicateFactory`

**Content:**

```text
com.example.r7.predicates.HasHeaderFactory

```

A response predicate's factory is listed in `META-INF/services/com.ethlo.r7.spi.GatewayResponsePredicateFactory` instead.

**File 2: Filter Registration**
Create the file: `src/main/resources/META-INF/services/com.ethlo.r7.spi.GatewayFilterFactory`

**Content:**

```text
com.example.r7.filters.BlockTokenFilterFactory

```

---

## 4. Packaging and Deployment

Once your code is written and registered via the SPI files, compile it into a standard Java JAR file (e.g., `my-custom-r7-extensions-1.0.jar`).

r7 finds extensions in its plugins directory: `plugins/` in the working directory, or the directory
`R7_PLUGINS_DIR` names. A plugin is a directory in it, holding the plugin's jar and the libraries
the plugin brings. Jars directly in `plugins/` count as one more plugin. A missing directory means
no extensions, and one that exists but cannot be read stops the start.

```text
plugins/
  auth/auth-plugin-1.2.jar            # one directory per plugin
  auth/some-library-4.0.jar           # a library only the auth plugin uses
  geo/geo-plugin-2.0.jar
  my-custom-r7-extensions-1.0.jar     # jars directly in plugins/ share a plugin
```

Each plugin runs in a class loader of its own. Two plugins can carry different versions of the same
library and cannot see each other's classes, and there is no need to build an uber-jar. The loader
is parent-first: the r7 API and the gateway's own libraries are the gateway's, so a plugin cannot
replace them.

### Docker Compose Example

The image reads `/app/plugins`, so mounting your jars there is all it takes, and the image's AOT
cache applies as it is.

```yaml title="docker-compose.yaml"
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:main
    container_name: ethlo-r7-gateway
    ports:
      - "9999:8888"
    volumes:
      - ./config:/app/config:ro
      - ./plugins:/app/plugins:ro   # your extension jars
```

With `docker run`, add `-v "$PWD/plugins:/app/plugins:ro"`.

Mount a directory per plugin to take each one from a source of its own, such as an image that holds
only the plugin's jars:

```yaml title="docker-compose.yaml"
    volumes:
      - { type: image, source: ghcr.io/you/auth-plugin:1.2, target: /app/plugins/auth, read_only: true }
      - { type: image, source: ghcr.io/you/geo-plugin:2.0, target: /app/plugins/geo, read_only: true }
```

The gateway will boot, the ServiceLoader will scan the plugins directory, and your custom YAML keys (`HasHeader` and `BlockToken`) will be fully operational alongside the native r7 filters.
