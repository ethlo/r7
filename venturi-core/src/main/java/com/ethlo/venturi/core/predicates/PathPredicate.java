package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

public record PathPredicate(String path, boolean exact) implements GatewayPredicate {

    @Override
    public boolean test(GatewayRequest request, GatewayAttributes attributes) {
        final CharSequence uri = request.uri();
        
        if (exact) {
            return equals(uri, path);
        }
        
        return startsWith(uri, path);
    }

    private boolean startsWith(CharSequence seq, String prefix) {
        if (seq.length() < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (seq.charAt(i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    private boolean equals(CharSequence seq, String target) {
        if (seq.length() != target.length()) return false;
        for (int i = 0; i < target.length(); i++) {
            if (seq.charAt(i) != target.charAt(i)) return false;
        }
        return true;
    }
}