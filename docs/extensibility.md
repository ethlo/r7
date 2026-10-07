# Custom Filters and Predicates

The r7 gateway is designed to be highly extensible. Because it utilizes Java's native `ServiceLoader` mechanism, you can write custom Predicates and Filters, compile them into a standard JAR file, and drop them into the gateway's classpath. The engine will automatically discover and register them for use in your YAML configuration.

This guide outlines how to implement, expose, and deploy your custom extensions.

---

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

---

## 2. Implementing a Custom Filter

Filters can mutate requests before they are sent upstream, mutate responses before they are returned to the client, or short-circuit the request entirely.

To create a custom filter, implement `GatewayFilterFactory`. The inner filter class can implement `ClientRequestGatewayFilter`, `UpstreamRequestGatewayFilter`, or `ClientResponseGatewayFilter` depending on the lifecycle phase you need to intercept.

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

**File 2: Filter Registration**
Create the file: `src/main/resources/META-INF/services/com.ethlo.r7.spi.GatewayFilterFactory`

**Content:**

```text
com.example.r7.filters.BlockTokenFilterFactory

```

---

## 4. Packaging and Deployment

Once your code is written and registered via the SPI files, compile it into a standard Java JAR file (e.g., `my-custom-r7-extensions-1.0.jar`).

r7 finds extensions on its classpath. The gateway jar starts with `java -jar`, which takes its
classpath from the jar's manifest alone (`r7.jar` plus the jars in `lib/` beside it) and ignores
`-cp` and `CLASSPATH`. To add your jar, start the main class instead, with your jar after
`r7.jar`; `r7.jar`'s manifest still brings in `lib/`:

```bash
java -cp "r7.jar:plugins/*" com.ethlo.r7.helidon.R7Helidon
```

### Docker Compose Example

The image's entrypoint is `java -jar`, so mount your jars and override the entrypoint with the
image's own flags plus the classpath. The image's AOT cache still applies, since your jars come
after the classpath it was built with. Copy the flags from the entrypoint of the image you run
(`docker inspect --format '{{json .Config.Entrypoint}}' ghcr.io/ethlo/r7-gateway:latest`) when
you upgrade.

```yaml title="docker-compose.yaml"
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    container_name: ethlo-r7-gateway
    ports:
      - "9999:8888"
    volumes:
      - ./config:/app/config:ro
      - ./plugins:/app/plugins:ro   # your extension jars
    entrypoint:
      - java
      - -XX:AOTCache=/app/r7.aot
      - --enable-native-access=ALL-UNNAMED
      - --sun-misc-unsafe-memory-access=allow
      - -Djava.security.egd=file:/dev/./urandom
      - -cp
      - /app/r7.jar:/app/plugins/*
      - com.ethlo.r7.helidon.R7Helidon
```

The gateway will boot, the ServiceLoader will scan the classpath, and your custom YAML keys (`HasHeader` and `BlockToken`) will be fully operational alongside the native r7 filters.
