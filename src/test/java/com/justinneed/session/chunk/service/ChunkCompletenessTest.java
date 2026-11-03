package com.justinneed.session.chunk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.service.ChunkCompleteness.State;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("청크 완료 판정 (확장 tools/chunks-to-bundle.js inspect() 와 같은 규칙)")
class ChunkCompletenessTest {

    private static final Long USER = 1L;
    private static final String SESSION = "s-1";

    // seq from..to 기록을 담은 청크. from > to 면 기록 없는 청크
    private static SessionRawChunk chunk(int n, int from, int to, boolean last) {
        return chunk(USER, SESSION, n, from, to, last, last ? n : null, USER);
    }

    private static SessionRawChunk chunk(Long user, String session, int n, int from, int to,
                                         boolean last, Integer maxN, Long memberId) {
        var records = new ArrayList<Map<String, Object>>();
        for (int s = from; s <= to; s++) {
            records.add(Map.of("seq", s, "k", "flush"));
        }
        var payload = new HashMap<String, Object>();
        payload.put("v", 1);
        payload.put("memberId", memberId);
        payload.put("fromSeq", records.isEmpty() ? null : from);
        payload.put("toSeq", records.isEmpty() ? null : to);
        payload.put("records", records);
        return new SessionRawChunk(user, session, n, last, maxN, last ? "user" : null, payload, LocalDateTime.now());
    }

    @Test
    @DisplayName("final 이 오고 1..maxN 이 다 있고 seq 가 이어지면 COMPLETE (순서가 섞여 와도)")
    void complete() {
        var r = ChunkCompleteness.check(List.of(chunk(3, 20, 29, true), chunk(1, 0, 9, false), chunk(2, 10, 19, false)));
        assertThat(r.state()).isEqualTo(State.COMPLETE);
        assertThat(r.maxN()).isEqualTo(3);
        assertThat(r.missing()).isEmpty();
    }

    @Test
    @DisplayName("기록 없는 마지막 청크(정지 직후 다 잘라 보낸 경우)도 COMPLETE")
    void emptyFinalChunk() {
        var r = ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), chunk(2, 1, 0, true)));
        assertThat(r.state()).isEqualTo(State.COMPLETE);
    }

    @Test
    @DisplayName("final 이 아직 없으면 WAITING")
    void noFinalYet() {
        var r = ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), chunk(2, 10, 19, false)));
        assertThat(r.state()).isEqualTo(State.WAITING);
        assertThat(r.missing()).isEmpty();
    }

    @Test
    @DisplayName("빈 번호가 있으면 WAITING 과 빈 번호 — seq 끊김은 모순으로 치지 않는다")
    void missingMiddle() {
        var r = ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), chunk(4, 30, 39, true), chunk(2, 10, 19, false)));
        assertThat(r.state()).isEqualTo(State.WAITING);
        assertThat(r.missing()).containsExactly(3);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    @DisplayName("final 이 안 왔고 앞 번호가 빠져도 WAITING (보이는 가장 큰 n 까지 빈 번호)")
    void missingBeforeFinal() {
        var r = ChunkCompleteness.check(List.of(chunk(2, 10, 19, false)));
        assertThat(r.state()).isEqualTo(State.WAITING);
        assertThat(r.missing()).containsExactly(1);
    }

    @Test
    @DisplayName("seq 가 끊기면 BROKEN (청크는 다 왔는데 기록이 빔)")
    void seqGap() {
        var r = ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), chunk(2, 11, 19, true)));
        assertThat(r.state()).isEqualTo(State.BROKEN);
        assertThat(r.problems()).anyMatch(p -> p.contains("fromSeq 11 ≠ 앞 청크 toSeq + 1 (10)"));
    }

    @Test
    @DisplayName("첫 청크가 seq 0 부터가 아니면 BROKEN")
    void firstNotZero() {
        var r = ChunkCompleteness.check(List.of(chunk(1, 1, 9, true)));
        assertThat(r.state()).isEqualTo(State.BROKEN);
    }

    @Test
    @DisplayName("final 이 둘이거나, final 뒤에 번호가 있거나, maxN ≠ n 이면 BROKEN")
    void finalContradictions() {
        assertThat(ChunkCompleteness.check(List.of(chunk(1, 0, 9, true), chunk(2, 10, 19, true))).state()).isEqualTo(State.BROKEN);
        assertThat(ChunkCompleteness.check(List.of(chunk(1, 0, 9, true), chunk(2, 10, 19, false))).state()).isEqualTo(State.BROKEN);
        var wrongMax = chunk(USER, SESSION, 2, 10, 19, true, 5, USER);
        assertThat(ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), wrongMax)).problems())
                .anyMatch(p -> p.contains("maxN 5 ≠ n 2"));
    }

    @Test
    @DisplayName("memberId 가 저장 회원과 다르거나, 회원 · 세션이 섞이면 BROKEN")
    void memberAndSessionMix() {
        var otherMember = chunk(USER, SESSION, 2, 10, 19, true, 2, 9L);
        assertThat(ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), otherMember)).state()).isEqualTo(State.BROKEN);
        var otherSession = chunk(USER, "s-2", 2, 10, 19, true, 2, USER);
        assertThat(ChunkCompleteness.check(List.of(chunk(1, 0, 9, false), otherSession)).problems())
                .anyMatch(p -> p.contains("세션 id 가 2개"));
    }

    @Test
    @DisplayName("records 안 seq 가 끊기면 BROKEN")
    void gapInsideChunk() {
        var c = chunk(1, 0, 3, true);
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) c.getPayload().get("records");
        records.set(2, Map.of("seq", 7));
        assertThat(ChunkCompleteness.check(List.of(c)).state()).isEqualTo(State.BROKEN);
    }
}
