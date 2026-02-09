package com.ethlo.venturi.core.model;

public record RealmUser(String realm, String principal) {
    // Zero-allocation singleton for unauthenticated paths
    public static final RealmUser ANONYMOUS = new RealmUser("default", "anonymous");
}