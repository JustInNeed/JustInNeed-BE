package com.justinneed.community.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum NodeVisibility {
    URL_ONLY, CONTENT;

    // 기본 enum 변환은 숫자를 순서로 받아 1 이 CONTENT 가 된다. 공개 범위가 넓어지는 값이라 이름만 받는다
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static NodeVisibility of(String value) {
        return valueOf(value);
    }
}
