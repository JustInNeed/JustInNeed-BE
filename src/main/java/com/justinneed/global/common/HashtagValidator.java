package com.justinneed.global.common;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class HashtagValidator {

    private static final int MAX_TAG_COUNT = 10;
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("^[가-힣a-zA-Z0-9]{1,10}$");

    private HashtagValidator() {
    }

    public static List<String> normalizeAndValidate(List<String> tags) {
        if (tags == null) {
            return null;
        }
        if (tags.size() > MAX_TAG_COUNT) {
            throw new CustomException(ErrorCode.TAG_LIMIT_EXCEEDED);
        }

        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            String value = tag == null ? "" : tag.trim();
            if (!isValid(value)) {
                throw new CustomException(ErrorCode.INVALID_HASHTAG);
            }
            String key = value.toLowerCase();
            if (!seen.add(key)) {
                throw new CustomException(ErrorCode.DUPLICATE_HASHTAG);
            }
            normalized.add(value);
        }
        return normalized;
    }

    // 해시태그 형식(한글/영문/숫자 1~10자)만 검사한다. 예외 없이 결과만 필요할 때 사용.
    public static boolean isValid(String tag) {
        return tag != null && HASHTAG_PATTERN.matcher(tag).matches();
    }
}
