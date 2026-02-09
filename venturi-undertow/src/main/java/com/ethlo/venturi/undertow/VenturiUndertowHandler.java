public class VenturiUndertowHandler implements HttpHandler {
    
    // Java 25 Scoped Values for zero-allocation propagation
    public static final ScopedValue<CharSequence> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<GatewayAttributes> ATTRS = ScopedValue.newInstance();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // 1. Wrap Undertow exchange into your API interfaces
        var request = new UndertowGatewayRequest(exchange);
        var response = new UndertowGatewayResponse(exchange);
        var attributes = new ScopedAttributes(); // Lazy internal map

        // 2. Setup context
        String id = idGenerator.generate(request);
        attributes.put("request_id", id);

        // 3. Bind and Execute in Virtual Thread
        ScopedValue.where(REQUEST_ID, id)
                   .where(ATTRS, attributes)
                   .run(() -> {
                       // The filter chain runs here. 
                       // Any 'addStreamListener' calls will hook into Undertow's Conduits.
                       filterChain.execute(request, response, attributes);
                   });
    }
}