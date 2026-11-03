package com.justinneed.session.chunk.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.service.AnalysisFailures;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.chunk.service.ChunkCompleteness.State;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.CollectionSettings;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 청크 → 세션 원본 (확장 #34 · BE #116). 확장은 시작 · 종료 요청을 보내지 않으므로 끝은 청크로 판단한다.
 *
 * <pre>
 * 청크를 받을 때마다 (advance, 수집 중인 세션만)
 *   COMPLETE (final 이 왔고 1..maxN 이 다 있음) → 합쳐 원본(session_raw_payloads) 저장 → finishCollection (READY, 커밋 뒤 AI 요청)
 *   WAITING  (빈 번호 · final 없음)             → 기다린다
 *   BROKEN   (기다려도 풀리지 않는 모순)         → 합치지 않고 원인을 로그에 남긴다. 1시간 뒤 정리에서 FAILED
 *
 * 마지막 청크 뒤 1시간 조용하면 (closeIfQuiet, ChunkSessionTimeoutScheduler 가 부른다)
 *   빈 번호가 있어도 받은 만큼 합쳐 분석한다. 빠진 청크에만 page 기록이 있던 글은 ChunkAssembler 가 버린다 (나중에 다시 확인)
 *   BROKEN 이면 FAILED (AnalysisFailures). 원본이 없어 재분석은 "원본 없음"
 * </pre>
 *
 * 확장은 크롬 밖 30분이면 스스로 정지하므로 살아 있는 세션이 1시간 동안 조용할 일은 없다. final 이 안 온 것 = 브라우저를 끄거나 덮은 경우.
 * 끝난 뒤 늦게 온 청크는 받아만 두고(SessionChunkService) 다시 합치지 않는다.
 * 합칠 때 stop 기록이 없으면 끝 시각 = 마지막 기록 시각, 종료 사유 = timeout 으로 채운다. 시작 기록이 없으면(1번 청크 유실) 시작 시각 = 세션 행 값.
 * 합치기가 예외로 실패하면 롤백해 청크를 남기고 횟수를 센다. 정해진 횟수가 차면 FAILED.
 * 세션 행을 잠그고 COLLECTING 인지 다시 보므로 청크 수신과 정리 스케줄러가 겹쳐도 한 번만 합친다.
 */
@Service
public class ChunkSessionService {

    public static final String STOP_REASON_TIMEOUT = "timeout";
    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    // ChunkAssembler 의 시각 꼴과 같다 (JS toISOString)
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(ChunkSessionService.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final SessionRawChunkRepository chunkRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final AnalysisRequestService analysisRequestService;
    private final AnalysisFailures failures;
    private final CollectionSettings settings;
    private final TransactionTemplate transactionTemplate;

    public ChunkSessionService(BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            SessionRawChunkRepository chunkRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            AnalysisRequestService analysisRequestService,
            AnalysisFailures failures,
            CollectionSettings settings,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.chunkRepository = chunkRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.analysisRequestService = analysisRequestService;
        this.failures = failures;
        this.settings = settings;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // 청크를 받은 뒤 (저장 커밋 뒤). 다 왔으면 합친다. 합친 결과 상태(READY), 아니면 null
    public SessionStatus advance(Long sessionId) {
        return run(sessionId, () -> advanceLocked(sessionId));
    }

    // 마지막 청크 뒤 조용한 시간이 지났으면 끝낸다. 끝냈으면 그 결과 상태(READY · FAILED), 아니면 null
    public SessionStatus closeIfQuiet(Long sessionId, LocalDateTime now) {
        return run(sessionId, () -> closeIfQuietLocked(sessionId, now));
    }

    private SessionStatus run(Long sessionId, Supplier<SessionStatus> work) {
        try {
            return transactionTemplate.execute(status -> work.get());
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> recordFailure(sessionId, e));
            return null;
        }
    }

    private SessionStatus advanceLocked(Long sessionId) {
        BrowsingSession session = lockCollecting(sessionId);
        if (session == null) {
            return null;
        }
        Long userId = session.getUserId();
        String collectorSessionId = session.getCollectorSessionId();
        // 다 왔는지는 번호 · final 만으로 먼저 본다 — 청크마다 세션 청크 전부의 payload 를 읽지 않게
        if (!allArrived(chunkRepository.findHeads(userId, collectorSessionId))) {
            return null;
        }
        var chunks = chunkRepository.findByUserIdAndCollectorSessionIdOrderByNAsc(userId, collectorSessionId);
        var result = ChunkCompleteness.check(chunks);
        if (result.state() == State.BROKEN) {
            log.warn("[chunk] 세션 {} (수집기 {}) 청크가 맞지 않아 합치지 않음: {}", sessionId, collectorSessionId, result.problems());
            return null;
        }
        if (result.state() == State.WAITING) {
            return null;
        }
        finish(session, chunks);
        return SessionStatus.READY;
    }

    private SessionStatus closeIfQuietLocked(Long sessionId, LocalDateTime now) {
        BrowsingSession session = lockCollecting(sessionId);
        if (session == null) {
            return null;
        }
        LocalDateTime last = collectionRepository.getOrCreate(sessionId).getLastChunkAt();
        LocalDateTime lastActivity = last != null ? last : session.getStartedAt();
        if (lastActivity.plus(settings.autoEndAfter()).isAfter(now)) {
            return null;
        }
        var chunks = chunkRepository.findByUserIdAndCollectorSessionIdOrderByNAsc(
                session.getUserId(), session.getCollectorSessionId());
        var result = ChunkCompleteness.check(chunks);
        if (chunks.isEmpty() || result.state() == State.BROKEN) {
            log.warn("[chunk] 세션 {} (수집기 {}) 마지막 청크 뒤 {} 동안 조용함 — 합칠 수 없어 FAILED: {} {}",
                    sessionId, session.getCollectorSessionId(), settings.autoEndAfter(), result.state(), result.problems());
            failures.fail(session);
            return SessionStatus.FAILED;
        }
        if (!result.missing().isEmpty()) {
            log.warn("[chunk] 세션 {} (수집기 {}) 빈 번호 {} — 받은 만큼 합침", sessionId, session.getCollectorSessionId(), result.missing());
        } else if (result.state() == State.WAITING) {
            log.info("[chunk] 세션 {} (수집기 {}) final 없이 온 것까지 합침", sessionId, session.getCollectorSessionId());
        }
        finish(session, chunks);
        return SessionStatus.READY;
    }

    private BrowsingSession lockCollecting(Long sessionId) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (session == null || session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING
                || session.getCollectorSessionId() == null) {
            return null;
        }
        return session;
    }

    // 합쳐 원본 저장 → 분석 대기 (READY, 커밋 뒤 AI 요청)
    private void finish(BrowsingSession session, List<SessionRawChunk> chunks) {
        var logRecords = new ArrayList<Map<String, Object>>();
        for (var c : chunks) {
            logRecords.addAll(records(c.getPayload()));
        }
        Map<String, Object> bundle = ChunkAssembler.assemble(logRecords);
        fillMissingSessionValues(bundle, session, logRecords, chunks.get(chunks.size() - 1).getReceivedAt());
        if (!rawPayloadRepository.existsById(session.getId())) {
            rawPayloadRepository.save(new SessionRawPayload(session.getId(), session.getUserId(), bundle, LocalDateTime.now()));
        }
        analysisRequestService.finishCollection(session.getId());
        log.info("[chunk] 세션 {} (수집기 {}) 청크 {}개를 합쳐 분석 대기로", session.getId(), session.getCollectorSessionId(), chunks.size());
    }

    // 시작 · stop 기록이 없을 때의 값. stop 기록이 없으면 마지막 기록 시각 · timeout (자동 종료), 시작 기록이 없으면 세션 행의 시작 시각
    @SuppressWarnings("unchecked")
    static void fillMissingSessionValues(Map<String, Object> bundle, BrowsingSession session,
            List<Map<String, Object>> logRecords, LocalDateTime lastReceivedAt) {
        if (!(bundle.get("session") instanceof Map<?, ?> raw)) {
            return;
        }
        var s = (Map<String, Object>) raw;
        String startedAt = s.get("startedAt") instanceof String v ? v
                : ISO.format(session.getStartedAt().atZone(KST).toInstant());
        String endedAt = s.get("endedAt") instanceof String v ? v : lastRecordTime(logRecords, lastReceivedAt);
        if (s.get("endedAt") == null) {
            s.put("stopReason", STOP_REASON_TIMEOUT);
        }
        s.put("startedAt", startedAt);
        s.put("endedAt", endedAt);
        if (s.get("sessionId") == null) {
            s.put("sessionId", session.getCollectorSessionId());
        }
        if (bundle.get("pages") instanceof List<?> pages) {
            for (Object p : pages) {
                if (p instanceof Map<?, ?> page && page.get("meta") instanceof Map<?, ?> m) {
                    var meta = (Map<String, Object>) m;
                    meta.putIfAbsent("startedAt", startedAt);
                    meta.replace("startedAt", null, startedAt);
                    meta.putIfAbsent("endedAt", endedAt);
                    meta.replace("endedAt", null, endedAt);
                }
            }
        }
    }

    private static String lastRecordTime(List<Map<String, Object>> logRecords, LocalDateTime lastReceivedAt) {
        long last = Long.MIN_VALUE;
        for (var r : logRecords) {
            if (r.get("ts") instanceof Number ts) {
                last = Math.max(last, ts.longValue());
            }
        }
        Instant at = last != Long.MIN_VALUE ? Instant.ofEpochMilli(last) : lastReceivedAt.atZone(KST).toInstant();
        return ISO.format(at);
    }

    private void recordFailure(Long sessionId, RuntimeException e) {
        BrowsingSession session = lockCollecting(sessionId);
        if (session == null) {
            return;
        }
        int attempts = collectionRepository.getOrCreate(sessionId).increaseMergeAttempts();
        log.warn("[chunk] 세션 {} 합치기 실패 {}/{}회: {}", sessionId, attempts, settings.mergeMaxAttempts(), e.getMessage(), e);
        if (attempts >= settings.mergeMaxAttempts()) {
            failures.fail(session);
        }
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

    // 1번 청크 안의 시작 기록(k = start) 시각 → KST. 없으면 null
    static LocalDateTime startedAtOf(Map<String, Object> payload) {
        return records(payload).stream()
                .filter(r -> "start".equals(r.get("k")) && r.get("ts") instanceof Number)
                .findFirst()
                .map(r -> LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) r.get("ts")).longValue()), KST))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> payload) {
        return payload.get("records") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
