package com.justinneed.session.chunk.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.chunk.service.ChunkCompleteness.State;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청크 → 세션 (확장 #34 · BE #116).
 *
 * <pre>
 * 청크를 받을 때마다 (처음 받은 것 · 다시 온 것 모두)
 *   1. 이 회원 · 수집기 세션 id 의 세션이 없으면 만든다: COLLECTING, 임시 제목, 시작 시각 = 1번 청크의 시작 기록 (없으면 지금)
 *   2. 세션을 잠그고, COLLECTING 이 아니면(이미 합쳤음 · 지움) 아무것도 안 한다
 *   3. 청크 전부를 ChunkCompleteness 로 판정
 *      COMPLETE → ChunkAssembler 로 합쳐 원본(session_raw_payloads) 저장 → finishCollection (READY, 커밋 뒤 AI 요청)
 *      WAITING  → 기다린다 (빈 번호 · final 없음)
 *      BROKEN   → 합치지 않고 원인을 로그에 남긴다 (세션은 COLLECTING 그대로)
 * </pre>
 * 처리 중 오류는 그대로 던진다 → 청크 요청이 500 → 확장이 다시 보냄 → 이미 받은 청크라도 여기를 다시 돈다 (자연스러운 재시도).
 *
 * 안 끝난 세션 (closeIfQuiet, ChunkSessionTimeoutScheduler 가 부른다, 11-02 서현):
 *   마지막 청크를 받은 뒤 QUIET(1시간) 동안 새 청크가 없으면 끝으로 본다. 확장은 크롬 밖 30분이면 스스로 정지하므로
 *   살아 있는 세션이 1시간 동안 조용할 일은 없다. final 이 안 온 것 = 브라우저를 끄거나 덮어서 정지 처리가 안 된 경우.
 *     빈 번호 없음 (final 이 없어도) → 그때까지 온 것으로 합쳐 분석
 *     빈 번호 있음 · 모순 → 기존 FAILED (새 상태 · 웹 변경 없음). 원본이 없어 재분석은 "원본 없음"
 *   그 뒤 늦게 온 청크는 받아만 두고(COLLECTING 이 아니라 위에서 무시) 분석에 넣지 않는다. 확장은 받았다는 답에 지운다.
 * 청크는 합친 뒤에도 남겨 둔다 — 다시 온 청크의 내용 비교(409)에 쓰인다. 받은 지 CHUNK_RETENTION(21일)이 지나면 지운다.
 */
@Service
public class ChunkSessionService {

    static final String COLLECTING_TITLE = "수집 중인 세션";
    public static final Duration QUIET = Duration.ofHours(1);
    public static final Duration CHUNK_RETENTION = Duration.ofDays(21);
    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final Logger log = LoggerFactory.getLogger(ChunkSessionService.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionRawChunkRepository chunkRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final AnalysisRequestService analysisRequestService;

    public ChunkSessionService(BrowsingSessionRepository sessionRepository,
            SessionRawChunkRepository chunkRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            AnalysisRequestService analysisRequestService) {
        this.sessionRepository = sessionRepository;
        this.chunkRepository = chunkRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.analysisRequestService = analysisRequestService;
    }

    // n · records = 방금 받은 청크 (1번이면 그 안의 시작 기록으로 시작 시각을 맞춘다)
    @Transactional
    public State advance(Long userId, String collectorSessionId, int n, Map<String, Object> payload) {
        LocalDateTime startedAt = n == 1 ? startedAt(payload) : null;
        Long id = sessionRepository.findIdByCollector(userId, collectorSessionId)
                .orElseGet(() -> sessionRepository.saveAndFlush(BrowsingSession.collecting(
                        userId, collectorSessionId, COLLECTING_TITLE, startedAt != null ? startedAt : LocalDateTime.now())).getId());
        BrowsingSession session = sessionRepository.lockById(id).orElseThrow();
        if (session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING) {
            log.info("[chunk] 세션 {} (수집기 {}) 는 이미 {} — 늦게 온 청크 n {} 는 받아만 두고 분석에 넣지 않음",
                    id, collectorSessionId, session.isDeleted() ? "삭제됨" : session.getStatus(), n);
            return null;
        }
        if (startedAt != null && !startedAt.equals(session.getStartedAt())) {
            session.correctStartedAt(startedAt);
        }

        // 다 왔는지는 번호 · final 만으로 먼저 본다 — 청크마다 세션 청크 전부의 payload 를 읽지 않게
        if (!allArrived(chunkRepository.findHeads(userId, collectorSessionId))) {
            return State.WAITING;
        }
        var chunks = chunkRepository.findByUserIdAndCollectorSessionIdOrderByNAsc(userId, collectorSessionId);
        var result = ChunkCompleteness.check(chunks);
        if (result.state() == State.BROKEN) {
            log.warn("[chunk] 세션 {} (수집기 {}) 청크가 맞지 않아 합치지 않음: {}", session.getId(), collectorSessionId, result.problems());
            return result.state();
        }
        if (result.state() == State.WAITING) {
            return result.state();
        }

        finish(session, chunks);
        return result.state();
    }

    // 조용한 지 QUIET 가 지난 COLLECTING 세션을 끝낸다. 끝냈으면 그 결과 상태(READY · FAILED), 아니면 null
    @Transactional
    public SessionStatus closeIfQuiet(Long sessionId, LocalDateTime now) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (session == null || session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING
                || session.getCollectorSessionId() == null) {
            return null;
        }
        Long userId = session.getUserId();
        String collectorSessionId = session.getCollectorSessionId();
        LocalDateTime last = chunkRepository.findLastReceivedAt(userId, collectorSessionId);
        if (last != null && last.isAfter(now.minus(QUIET))) {
            return null;
        }
        var chunks = chunkRepository.findByUserIdAndCollectorSessionIdOrderByNAsc(userId, collectorSessionId);
        var result = ChunkCompleteness.check(chunks);
        boolean usable = result.state() == State.COMPLETE
                || (result.state() == State.WAITING && result.missing().isEmpty() && !chunks.isEmpty());
        if (!usable) {
            log.warn("[chunk] 세션 {} (수집기 {}) 마지막 청크 뒤 {} 동안 조용함 — 합칠 수 없어 FAILED: {} 빈 번호 {} {}",
                    sessionId, collectorSessionId, QUIET, result.state(), result.missing(), result.problems());
            session.failAnalysis();
            return SessionStatus.FAILED;
        }
        log.info("[chunk] 세션 {} (수집기 {}) 마지막 청크 뒤 {} 동안 조용함 — final 없이 온 것까지 합침", sessionId, collectorSessionId, QUIET);
        finish(session, chunks);
        return SessionStatus.READY;
    }

    // 합쳐 원본 저장 → 분석 대기 (READY, 커밋 뒤 AI 요청)
    private void finish(BrowsingSession session, List<SessionRawChunk> chunks) {
        var logRecords = new ArrayList<Map<String, Object>>();
        for (var c : chunks) {
            logRecords.addAll(records(c));
        }
        Map<String, Object> bundle = ChunkAssembler.assemble(logRecords);
        if (!rawPayloadRepository.existsById(session.getId())) {
            rawPayloadRepository.save(new SessionRawPayload(session.getId(), session.getUserId(), bundle, LocalDateTime.now()));
        }
        analysisRequestService.finishCollection(session.getId());
        log.info("[chunk] 세션 {} (수집기 {}) 청크 {}개를 합쳐 분석 대기로", session.getId(), session.getCollectorSessionId(), chunks.size());
    }

    // final 이 있고 1..maxN 이 다 있으면 true (자세한 판정 · 모순은 ChunkCompleteness 가 본다)
    private static boolean allArrived(List<Object[]> heads) {
        Integer maxN = null;
        var ns = new HashSet<Integer>();
        for (Object[] h : heads) {
            ns.add(((Number) h[0]).intValue());
            if (Boolean.TRUE.equals(h[1]) && h[2] instanceof Number m) {
                maxN = m.intValue();
            }
        }
        if (maxN == null) {
            return false;
        }
        for (int i = 1; i <= maxN; i++) {
            if (!ns.contains(i)) {
                return false;
            }
        }
        return true;
    }

    // 1번 청크 안의 시작 기록(k = start) 시각 → KST
    private static LocalDateTime startedAt(Map<String, Object> payload) {
        return records(payload).stream()
                .filter(r -> "start".equals(r.get("k")) && r.get("ts") instanceof Number)
                .findFirst()
                .map(r -> LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) r.get("ts")).longValue()), KST))
                .orElse(null);
    }

    private static List<Map<String, Object>> records(SessionRawChunk c) {
        return records(c.getPayload());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> payload) {
        return payload.get("records") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
