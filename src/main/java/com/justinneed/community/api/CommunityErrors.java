package com.justinneed.community.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CommunityErrors {
    private CommunityErrors() { }
    // ResponseStatusException 그대로 둔다: 404 를 잡아 건너뛰는 곳(Interaction · PublicDirectory · Trending)이 이 타입을 본다
    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다.");
    }
    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
