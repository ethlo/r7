# Custom Filters and Predicates

The r7 proxy is designed to be highly extensible. Because it utilizes Java's native `ServiceLoader` mechanism, you can write custom Predicates and Filters, compile them into a standard JAR file, and drop them into the proxy's classpath. The engine will automatically discover and register them for use in your YAML configuration.

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
            new ValidatorUtils(result).required(PREDICATE_NAME, "header_name", this.headerName());
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
            final CharSequence headerValue = request.headers().getFirst(this.headerName);
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
import com.ethlo.r7.util.FastTerminationGatewayResponse;
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
                    .required(FILTER_NAME, "token_header", this.tokenHeader())
                    .required(FILTER_NAME, "blocked_token", this.blockedToken());
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
            final CharSequence incomingToken = exchange.clientRequest().headers().getFirst(this.tokenHeader);

            if (incomingToken != null) {
                if (incomingToken.toString().equals(this.blockedToken)) {
                    // Short-circuit the request immediately. Do not route upstream.
                    exchange.shortCircuit(new FastTerminationGatewayResponse(
                            HttpStatuses.FORBIDDEN,
                            MediaTypes.TEXT_PLAIN,
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

```yaml
filters:
  - BlockToken:
      token_header: X-Security-Token
      blocked_token: "malicious-token-123"

```

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

To use this with the r7 Docker container, you simply need to mount the JAR into the container and append it to the JVM classpath.

### Docker Compose Example

```yaml
services:
  r7-api:
    image: ghcr.io/ethlo/r7-jvm:latest
    container_name: ethlo-r7-gateway
    ports:
      - "9999:8888"
    volumes:
      - ./config:/app/config:ro
      # 1. Mount your custom jar directory into the container
      - ./plugins:/app/plugins:ro 
    environment:
      # 2. Add the jar to the Java classpath
      - JAVA_TOOL_OPTIONS=-XX:+UseZGC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp "/app/r7.jar:/app/plugins/my-custom-r7-extensions-1.0.jar"

```

The proxy will boot, the ServiceLoader will scan the classpath, and your custom YAML keys (`HasHeader` and `BlockToken`) will be fully operational alongside the native r7 filters.