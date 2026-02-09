package com.ethlo.venturi.core.model;

public record RealmUser(String realm, String principal) {
    public static final RealmUser ANONYMOUS = new RealmUser("", "");
}