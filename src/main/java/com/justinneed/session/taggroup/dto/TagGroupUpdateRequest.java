package com.justinneed.session.taggroup.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TagGroupUpdateRequest(
        // 안 보내면(null) 이름은 그대로. 보냈다면 빈 값 · 공백만은 안 된다 (생성의 @NotBlank 와 같은 문구)
        @Pattern(regexp = "(?s).*\\S.*", message = "그룹 이름을 입력해 주세요.")
        @Size(max = 50, message = "그룹 이름은 최대 50자까지 입력할 수 있습니다.")
        String name,
        List<String> hashtags
) {
}
