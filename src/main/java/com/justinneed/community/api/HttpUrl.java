package com.justinneed.community.api;

import java.net.URI;

public final class HttpUrl {
    private HttpUrl() { }
    public static boolean isValid(String value) {
        if (value == null) return false;
        try {
            var uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException error) { return false; }
    }
}
