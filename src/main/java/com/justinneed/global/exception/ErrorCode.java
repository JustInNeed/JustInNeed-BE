package com.justinneed.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."),
    TAG_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "해시태그 그룹을 찾을 수 없습니다."),
    TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요."),
    INVALID_HASHTAG(HttpStatus.BAD_REQUEST, "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다."),
    TAG_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "해시태그는 최대 10개까지 추가할 수 있습니다."),
    DUPLICATE_HASHTAG(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."),
    INVALID_ORDER_REQUEST(HttpStatus.BAD_REQUEST, "정렬할 그룹 목록이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
