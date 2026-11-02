package com.justinneed.session.chunk.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("청크 내용 비교 (같은 번호 재전송 판정)")
class SessionChunkSameJsonTest {

    @Test
    @DisplayName("숫자는 자바 타입이 달라도 값이 같으면 같다 (DB 에서 읽은 값 ↔ 요청 값)")
    void numbersCompareByValue() {
        assertThat(SessionChunkService.sameJson(1, 1L)).isTrue();
        assertThat(SessionChunkService.sameJson(0.5, 0.50)).isTrue();
        assertThat(SessionChunkService.sameJson(1, 2L)).isFalse();
    }

    @Test
    @DisplayName("객체는 키 순서와 상관없고, 배열은 순서까지 같아야 같다")
    void mapsIgnoreOrderListsDoNot() {
        assertThat(SessionChunkService.sameJson(Map.of("a", 1, "b", List.of(1, 2)), Map.of("b", List.of(1L, 2L), "a", 1L))).isTrue();
        assertThat(SessionChunkService.sameJson(List.of(1, 2), List.of(2, 1))).isFalse();
        assertThat(SessionChunkService.sameJson(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
        assertThat(SessionChunkService.sameJson(Map.of("a", "x"), Map.of("a", "y"))).isFalse();
    }
}
