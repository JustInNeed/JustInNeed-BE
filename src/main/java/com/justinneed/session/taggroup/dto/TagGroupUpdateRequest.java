package com.justinneed.session.taggroup.dto;

import java.util.List;

// 그룹은 이름 없이 해시태그로만 구분한다 (QA-011)
public record TagGroupUpdateRequest(
        List<String> hashtags
) {
}
