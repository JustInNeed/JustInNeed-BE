package com.justinneed.community.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CommunityErrors {
    private CommunityErrors() { }
    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Community resource not found");
    }
    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
