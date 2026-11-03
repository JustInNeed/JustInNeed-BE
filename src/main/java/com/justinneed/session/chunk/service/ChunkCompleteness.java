package com.justinneed.session.chunk.service;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 한 수집 세션의 청크가 다 왔는지 판정한다 (DB 를 건드리지 않는 순수 함수).
 * 규칙은 확장 레포 tools/chunks-to-bundle.js 의 inspect() 와 같다 — 합치기(assemble)는 COMPLETE 일 때만 한다.
 *
 *   청크 = 확장이 기록 로그를 30초마다 자른 단위. n 은 1부터 빈틈없이, 마지막 청크에만 final · maxN(= 그 n).
 *   payload.records 는 기록 로그 줄(seq 순). 청크를 n 순서로 이으면 seq 0 부터 빈틈없어야 한다.
 *   page 기록(글 URL · 유닛)은 그 글을 처음 연 청크에만 있어서, 빈 번호가 있으면 합칠 수 없다 → 기다린다.
 *
 * 결과
 *   COMPLETE : final 이 왔고 1..maxN 이 다 있고 seq 가 이어짐 → 합쳐도 된다
 *   WAITING  : final 이 아직 없거나 빈 번호가 있음 → 더 기다린다 (missing = 빈 번호)
 *   BROKEN   : 기다려도 풀리지 않는 모순 (final 둘 · maxN ≠ n · seq 끊김 · 회원 · 세션 섞임) → 합치지 않고 원인을 남긴다
 */
public final class ChunkCompleteness {

    public enum State { COMPLETE, WAITING, BROKEN }

    public record Result(State state, Integer maxN, List<Integer> missing, List<String> problems) {

        public boolean complete() {
            return state == State.COMPLETE;
        }
    }

    private ChunkCompleteness() {
    }

    public static Result check(List<SessionRawChunk> chunks) {
        if (chunks.isEmpty()) {
            return new Result(State.WAITING, null, List.of(), List.of("청크가 없음"));
        }
        var sorted = chunks.stream().sorted(Comparator.comparingInt(SessionRawChunk::getN)).toList();
        var problems = new ArrayList<String>();

        Set<String> sessions = new HashSet<>();
        Set<Long> users = new HashSet<>();
        for (var c : sorted) {
            sessions.add(c.getCollectorSessionId());
            users.add(c.getUserId());
            Long member = memberOf(c);
            if (member == null || !member.equals(c.getUserId())) {
                problems.add("n " + c.getN() + ": payload.memberId(" + c.getPayload().get("memberId") + ") ≠ 저장 회원(" + c.getUserId() + ")");
            }
        }
        if (sessions.size() != 1) {
            problems.add("세션 id 가 " + sessions.size() + "개");
        }
        if (users.size() != 1) {
            problems.add("회원이 " + users.size() + "명");
        }

        var finals = sorted.stream().filter(SessionRawChunk::isFinalChunk).toList();
        var last = sorted.get(sorted.size() - 1);
        Integer maxN = null;
        if (finals.size() > 1) {
            problems.add("final 청크가 " + finals.size() + "개");
        } else if (finals.size() == 1) {
            var fin = finals.get(0);
            maxN = fin.getMaxN();
            if (fin != last) {
                problems.add("final 청크(n " + fin.getN() + ") 뒤에 n " + last.getN() + " 이 있음");
            }
            if (!Objects.equals(maxN, fin.getN())) {
                problems.add("final 청크 maxN " + maxN + " ≠ n " + fin.getN());
            }
        }

        int upTo = maxN != null ? maxN : last.getN();
        Set<Integer> have = new HashSet<>();
        for (var c : sorted) {
            have.add(c.getN());
        }
        var missing = new ArrayList<Integer>();
        for (int i = 1; i <= upTo; i++) {
            if (!have.contains(i)) {
                missing.add(i);
            }
        }

        // seq 이어짐은 빈 번호가 없을 때만 본다 (빈 번호가 있으면 끊긴 게 당연하다)
        if (missing.isEmpty()) {
            checkSeq(sorted, problems);
        }

        if (!problems.isEmpty()) {
            return new Result(State.BROKEN, maxN, missing, problems);
        }
        if (finals.isEmpty() || !missing.isEmpty()) {
            return new Result(State.WAITING, maxN, missing, List.of());
        }
        return new Result(State.COMPLETE, maxN, missing, List.of());
    }

    private static void checkSeq(List<SessionRawChunk> sorted, List<String> problems) {
        long prev = -1;
        for (var c : sorted) {
            var p = c.getPayload();
            List<?> records = p.get("records") instanceof List<?> l ? l : List.of();
            Long from = asLong(p.get("fromSeq"));
            Long to = asLong(p.get("toSeq"));
            if (records.isEmpty()) {
                if (from != null || to != null) {
                    problems.add("n " + c.getN() + ": 기록이 없는데 fromSeq/toSeq 가 있음");
                }
                continue;
            }
            Long first = seqOf(records.get(0));
            Long lastSeq = seqOf(records.get(records.size() - 1));
            if (from == null || from != prev + 1) {
                problems.add("n " + c.getN() + ": fromSeq " + from + " ≠ 앞 청크 toSeq + 1 (" + (prev + 1) + ")");
            }
            if (!Objects.equals(from, first) || !Objects.equals(to, lastSeq)) {
                problems.add("n " + c.getN() + ": fromSeq/toSeq 가 records 의 seq 와 다름");
            }
            for (int i = 1; i < records.size(); i++) {
                Long a = seqOf(records.get(i - 1));
                Long b = seqOf(records.get(i));
                if (a == null || b == null || b != a + 1) {
                    problems.add("n " + c.getN() + ": records 안 seq 가 끊김 (" + a + " → " + b + ")");
                    break;
                }
            }
            prev = to != null ? to : prev;
        }
    }

    private static Long memberOf(SessionRawChunk c) {
        return asLong(c.getPayload().get("memberId"));
    }

    private static Long seqOf(Object record) {
        return record instanceof Map<?, ?> m ? asLong(m.get("seq")) : null;
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
