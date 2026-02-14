package com.ethlo.venturi.undertow;

import java.net.URI;
import java.util.List;

import com.ethlo.venturi.undertow.config.ServerConfig;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;

public class ThreadLocalProxyHandler implements HttpHandler
{
    private final ThreadLocal<ProxyHandler> localHandler;

    public ThreadLocalProxyHandler(ServerConfig.ProxyConfig config, List<URI> backendUris)
    {
        this.localHandler = ThreadLocal.withInitial(() -> {
            // Each IO thread creates its OWN independent client and pool
            LoadBalancingProxyClient client = new LoadBalancingProxyClient()
                    .setConnectionsPerThread(config.connectionsPerThread())
                    .setSoftMaxConnectionsPerThread(config.connectionsPerThread() - 10)
                    .setMaxQueueSize(config.maxQueueSize());

            // Add backends to this thread's private client
            for (URI uri : backendUris)
            {
                client.addHost(uri);
            }

            // Return a handler bound to this private client
            return new ProxyHandler(client, config.maxRequestTime(), ResponseCodeHandler.HANDLE_404);
        });
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception
    {
        // This stays on the same IO thread, pulling the thread-local pool
        localHandler.get().handleRequest(exchange);
    }
}