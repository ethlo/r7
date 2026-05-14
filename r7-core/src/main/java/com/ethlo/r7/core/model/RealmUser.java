package com.ethlo.r7.core.model;

public record RealmUser(String realm, String principal)
{
    public static final RealmUser ANONYMOUS = new RealmUser("", "");
}