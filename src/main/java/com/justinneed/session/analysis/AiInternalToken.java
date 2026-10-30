package com.justinneed.session.analysis;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring ↔ AI 컨테이너 내부 호출 토큰 (AI_INTERNAL_TOKEN, 양쪽 같은 값).
 * Spring → AI 요청에 실어 보내고, AI → Spring 콜백(/internal/ai/**)에서 확인한다.
 */
@Component
public class AiInternalToken {

    public static final String HEADER = "X-Internal-Token";

    private final String token;

    public AiInternalToken(@Value("${app.ai.internal-token}") String token) {
        this.token = token;
    }

    public String value() {
        return token;
    }

    // 토큰이 비어 있으면(설정 누락) 어떤 요청도 통과시키지 않는다. 길이 · 내용 비교 시간이 같도록 MessageDigest.isEqual 사용
    public void verify(String received) {
        if (token == null || token.isBlank() || received == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
            throw new CustomException(ErrorCode.INVALID_INTERNAL_TOKEN);
        }
    }
}
