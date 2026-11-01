package com.justinneed.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    EXCLUDED_URL_NOT_FOUND(HttpStatus.NOT_FOUND, "수집 제외 URL을 찾을 수 없습니다."),
    INVALID_URL(HttpStatus.BAD_REQUEST, "올바른 URL 형식이 아닙니다."),
    DUPLICATE_URL(HttpStatus.CONFLICT, "이미 등록된 URL입니다."),
    EXCLUDED_URL_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "URL은 최대 100개까지 등록할 수 있습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."),
    SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "비공개 세션이라 볼 수 없어요."),
    SESSION_NOT_COMPLETED(HttpStatus.CONFLICT, "분석이 끝난 세션만 수정할 수 있습니다."),
    RAW_PAYLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "세션 원본을 찾을 수 없습니다."),
    // AI 콜백에 대한 응답. 5xx 면 AI 가 재시도하므로 4xx 로 둔다
    ANALYSIS_LLM_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "AI 요약 생성에 실패했습니다."),
    SESSION_NOT_COLLECTING(HttpStatus.CONFLICT, "수집 중인 세션이 아닙니다."),
    SESSION_NOT_FAILED(HttpStatus.CONFLICT, "분석에 실패한 세션만 다시 분석할 수 있습니다."),
    ANALYSIS_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "분석 중인 세션이 아닙니다."),
    ANALYSIS_MEMBER_WITHDRAWN(HttpStatus.CONFLICT, "탈퇴한 회원의 세션은 분석 결과를 저장하지 않습니다."),
    INVALID_INTERNAL_TOKEN(HttpStatus.UNAUTHORIZED, "내부 호출 토큰이 올바르지 않습니다."),
    INTEREST_NOT_FOUND(HttpStatus.NOT_FOUND, "관심 해시태그를 찾을 수 없습니다."),
    TAG_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "해시태그 그룹을 찾을 수 없습니다."),
    TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요."),
    INVALID_HASHTAG(HttpStatus.BAD_REQUEST, "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다."),
    INVALID_INTEGRATION_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 외부 플랫폼입니다."),
    DUPLICATE_INTEGRATION_EMAIL(HttpStatus.CONFLICT, "이미 연동된 이메일입니다."),
    TAG_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "해시태그는 최대 10개까지 추가할 수 있습니다."),
    DUPLICATE_HASHTAG(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."),
    INVALID_ORDER_REQUEST(HttpStatus.BAD_REQUEST, "정렬할 그룹 목록이 올바르지 않습니다."),
    INVALID_PIN_ORDER(HttpStatus.BAD_REQUEST, "고정할 세션 목록이 올바르지 않습니다."),
    PIN_REQUIRES_PUBLIC(HttpStatus.BAD_REQUEST, "공개한 세션만 고정할 수 있습니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INVALID_WITHDRAW_CONFIRMATION(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구가 올바르지 않습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    INVALID_TAG_GROUP_POSITION(HttpStatus.BAD_REQUEST, "그룹 위치 값이 올바르지 않습니다."),
    TAG_GROUP_HASHTAG_REQUIRED(HttpStatus.BAD_REQUEST, "해시태그를 1개 이상 선택해 주세요."),
    INVALID_PAGE_REQUEST(HttpStatus.BAD_REQUEST, "페이지 값이 올바르지 않습니다."),
    INVALID_LIMIT_REQUEST(HttpStatus.BAD_REQUEST, "조회 개수는 1개부터 100개까지 입력할 수 있습니다."),
    SELF_LIKE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "내 프로필과 마인드맵에는 좋아요를 누를 수 없습니다."),
    INVALID_BOOKMARK_SELECTION(HttpStatus.BAD_REQUEST, "저장할 대상은 중복 없이 1개부터 100개까지 선택할 수 있습니다."),
    BOOKMARK_SOURCE_MISMATCH(HttpStatus.BAD_REQUEST, "저장할 대상이 원본 마인드맵에 속하지 않습니다."),
    BOOKMARK_HASHTAG_REQUIRED(HttpStatus.BAD_REQUEST, "저장할 해시태그가 없습니다."),
    BOOKMARK_NODE_REQUIRED(HttpStatus.BAD_REQUEST, "저장할 노드가 없는 마인드맵입니다."),
    BOOKMARK_NAME_TOO_LONG(HttpStatus.BAD_REQUEST, "묶음 이름은 30자까지 입력할 수 있습니다."),
    BOOKMARK_NAME_INVALID(HttpStatus.BAD_REQUEST, "묶음 이름에 쓸 수 없는 문자가 있습니다."),
    BOOKMARK_NAME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "이름은 해시태그 묶음에만 붙일 수 있습니다."),
    MINI_MINDMAP_REQUIRED(HttpStatus.BAD_REQUEST, "미니 마인드맵 북마크만 원본을 볼 수 있습니다."),
    INVALID_LOGIN_CODE(HttpStatus.UNAUTHORIZED, "로그인 정보가 만료되었어요. 다시 로그인해 주세요."),
    LOGIN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.");

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
