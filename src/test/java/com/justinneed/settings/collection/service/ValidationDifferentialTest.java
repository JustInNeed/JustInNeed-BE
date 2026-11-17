package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.justinneed.auth.dto.NicknameUpdateRequest;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 정책 #30 차등 테스트 — FE 와 같은 입력 표(differential/validation-cases.json)로 BE 검증을 돌린다. 기대값은 BE 기준 정답이다. 표에
 * be 칸이 있으면 이유(reason)가 적힌 승인된 차이다. 이 표가 원본이다. 바꾸면 FE 레포
 * src/test/differential/validation-cases.json 사본도 같이 바꾼다 (FE CI 가 다르면 알려 준다). ExcludedUrlParser 가
 * 패키지 전용이라 이 패키지에 둔다.
 */
class ValidationDifferentialTest {

    private static final JsonNode CASES = load();
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static JsonNode load() {
        try (InputStream in =
                ValidationDifferentialTest.class.getResourceAsStream(
                        "/differential/validation-cases.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("differential/validation-cases.json 을 읽지 못했습니다", e);
        }
    }

    private static Stream<JsonNode> cases(String rule) {
        List<JsonNode> list = new ArrayList<>();
        CASES.get(rule).forEach(list::add);
        return list.stream();
    }

    static Stream<JsonNode> nickname() {
        return cases("nickname");
    }

    static Stream<JsonNode> hashtag() {
        return cases("hashtag");
    }

    static Stream<JsonNode> excludedUrl() {
        return cases("excludedUrl");
    }

    /** BE 가 따라야 할 값: 승인된 차이가 있으면 be 칸, 없으면 공통 기대값 */
    private static JsonNode beExpected(JsonNode c, String common) {
        return c.has("be") ? c.get("be") : c.get(common);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @Test
    @DisplayName("승인된 차이에는 모두 이유가 적혀 있다")
    void approvedDifferencesHaveReasons() {
        Stream.of("nickname", "hashtag", "excludedUrl")
                .flatMap(ValidationDifferentialTest::cases)
                .filter(c -> c.has("fe") || c.has("be"))
                .forEach(c -> assertThat(c.path("reason").asText()).as(c.toString()).isNotBlank());
    }

    @ParameterizedTest(name = "닉네임 {0}")
    @MethodSource("nickname")
    void nicknameMatches(JsonNode c) {
        boolean valid =
                VALIDATOR.validate(new NicknameUpdateRequest(c.get("input").asText())).isEmpty();
        assertThat(valid).isEqualTo(beExpected(c, "valid").asBoolean());
    }

    @ParameterizedTest(name = "해시태그 {0}")
    @MethodSource("hashtag")
    void hashtagMatches(JsonNode c) {
        String actual;
        try {
            actual = HashtagValidator.normalizeAndValidate(List.of(c.get("input").asText())).get(0);
        } catch (CustomException e) {
            actual = null;
        }
        assertThat(actual).isEqualTo(textOrNull(beExpected(c, "value")));
    }

    @ParameterizedTest(name = "수집 제외 URL {0}")
    @MethodSource("excludedUrl")
    void excludedUrlMatches(JsonNode c) {
        String actual;
        try {
            actual = ExcludedUrlParser.parse(c.get("input").asText()).url();
        } catch (CustomException e) {
            actual = null;
        }
        assertThat(actual).isEqualTo(textOrNull(beExpected(c, "value")));
    }
}
