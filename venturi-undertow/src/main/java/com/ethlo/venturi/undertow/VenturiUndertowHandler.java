package com.ethlo.venturi.undertow;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.chronograph.ChronographData;
import com.ethlo.chronograph.OutputConfig;
import com.ethlo.chronograph.output.table.TableOutputFormatter;
import com.ethlo.chronograph.output.table.TableThemes;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.StandardErrorHandler;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class VenturiUndertowHandler implements HttpHandler {
    public static final ScopedValue<CharSequence> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<GatewayAttributes> ATTRS = ScopedValue.newInstance();
    private final GatewayRoute route;
    private final ProxyHandler proxyHandler;
    private final GatewayErrorHandler errorHandler = new StandardErrorHandler();
    private final Executor executor = Executors.newFixedThreadPool(100); //VirtualThreadPerTaskExecutor();
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final AtomicLong requestNumber = new AtomicLong(0);

    public VenturiUndertowHandler(final GatewayRoute route, final ProxyHandler proxyHandler) {
        this.route = route;
        this.proxyHandler = proxyHandler;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) {
        exchange.dispatch(executor, () -> {

            final var req = new UndertowGatewayRequest(exchange);
            final var res = new UndertowGatewayResponse(exchange);
            final var attrs = new MapGatewayAttributes();
            final var id = requestIdGenerator.generate();
            attrs.put("request_id", id);

            ScopedValue.where(REQUEST_ID, id).where(ATTRS, attrs).run(() ->
            {
                //final Chronograph c = POOL.poll();
                //final Chronograph chronograph = c == null ? Chronograph.create() : c;
                final long reqNum = requestNumber.incrementAndGet();

                try {
                    // Monitor for "Silent" errors (503s from saturated ProxyClient)
                    exchange.addDefaultResponseListener(ex -> {
                        if (ex.getStatusCode() >= 400 && !ex.isResponseStarted()) {
                            errorHandler.handleError(req, res, attrs, new RuntimeException("Proxy status " + ex.getStatusCode()));
                            return true;
                        }
                        return false;
                    });

                    final boolean predicateMatch = route.predicate().test(req, attrs);
                    if (predicateMatch) {
                        for (final GatewayFilter filter : route.filters()) {
                            filter.beforeUpstream(req, res, attrs);
                            if (exchange.isResponseStarted()) {
                                return;
                            }
                        }
                    }

                    proxyHandler.handleRequest(exchange);
                } catch (Throwable t) {
                    errorHandler.handleError(req, res, attrs, t);
                }
            });
        });
    }

    private void checkStatus(long reqNum) {
        if (reqNum % 100_000 == 0) {
            final List<Chronograph> snapshot = new ArrayList<>();
            final ChronographData merged = snapshot.stream()
                    .map(Chronograph::getTaskData)
                    .reduce(new ChronographData(null, List.of()), (d1, d2) -> d1.merge(null, d2));
            System.out.println(reqNum + "\n" + new TableOutputFormatter(TableThemes.RED_HERRING, OutputConfig.DEFAULT).format(merged));
        }
    }
}