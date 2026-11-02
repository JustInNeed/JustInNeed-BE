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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * 청크는 합친 뒤에도 남겨 둔다 — 다시 온 청크의 내용 비교(409)에 쓰인다. 지우는 때는 안 끝난 세션 정리와 함께 정한다.
 */
@Service
public class ChunkSessionService {

    static final String COLLECTING_TITLE = "수집 중인 세션";
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

    @Transactional
    public State advance(Long userId, String collectorSessionId) {
        var chunks = chunkRepository.findByUserIdAndCollectorSessionIdOrderByNAsc(userId, collectorSessionId);
        LocalDateTime startedAt = startedAt(chunks);
        BrowsingSession session = sessionRepository.findByUserIdAndCollectorSessionId(userId, collectorSessionId)
                .orElseGet(() -> sessionRepository.saveAndFlush(BrowsingSession.collecting(
                        userId, collectorSessionId, COLLECTING_TITLE, startedAt != null ? startedAt : LocalDateTime.now())));
        session = sessionRepository.lockById(session.getId()).orElseThrow();
        if (session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING) {
            return null;
        }
        if (startedAt != null && !startedAt.equals(session.getStartedAt())) {
            session.correctStartedAt(startedAt);
        }

        var result = ChunkCompleteness.check(chunks);
        if (result.state() == State.BROKEN) {
            log.warn("[chunk] 세션 {} (수집기 {}) 청크가 맞지 않아 합치지 않음: {}", session.getId(), collectorSessionId, result.problems());
            return result.state();
        }
        if (result.state() == State.WAITING) {
            return result.state();
        }

        var logRecords = new ArrayList<Map<String, Object>>();
        for (var c : chunks) {
            logRecords.addAll(records(c));
        }
        Map<String, Object> bundle = ChunkAssembler.assemble(logRecords);
        if (!rawPayloadRepository.existsById(session.getId())) {
            rawPayloadRepository.save(new SessionRawPayload(session.getId(), userId, bundle, LocalDateTime.now()));
        }
        analysisRequestService.finishCollection(session.getId());
        log.info("[chunk] 세션 {} (수집기 {}) 청크 {}개를 합쳐 분석 대기로", session.getId(), collectorSessionId, chunks.size());
        return result.state();
    }

    // 1번 청크 안의 시작 기록(k = start) 시각 → KST
    private static LocalDateTime startedAt(List<SessionRawChunk> chunks) {
        return chunks.stream()
                .filter(c -> c.getN() == 1)
                .flatMap(c -> records(c).stream())
                .filter(r -> "start".equals(r.get("k")) && r.get("ts") instanceof Number)
                .findFirst()
                .map(r -> LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) r.get("ts")).longValue()), KST))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(SessionRawChunk c) {
        return c.getPayload().get("records") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
