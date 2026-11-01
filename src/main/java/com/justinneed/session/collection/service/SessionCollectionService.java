package com.justinneed.session.collection.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.dto.CollectorEndRequest;
import com.justinneed.session.collection.dto.CollectorSessionResponse;
import com.justinneed.session.collection.dto.CollectorStartRequest;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 확장의 세션 시작 · 종료 요청.
 *
 * <pre>
 * 시작 요청            sessions 행 생성 (COLLECTING, 임시 제목)
 * 청크 · 종료 요청     행이 없으면 같은 방식으로 만든다 (시작 요청 유실 · 지연 대비)
 * 종료 요청            값을 DB 에 남긴다. 청크가 총 수만큼 있으면 커밋 뒤 바로 합치고, 적으면 스케줄러가 3분 뒤 합친다
 * </pre>
 *
 * 둘 다 재전송해도 안전하다. 동시에 만들면 유니크 제약에 걸린 쪽이 먼저 만든 행을 쓴다 — 그래서 바깥에 트랜잭션을 두지 않는다.
 */
@Service
public class SessionCollectionService {

    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int MAX_COLLECTOR_SESSION_ID_LENGTH = 64;

    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final SessionRawChunkRepository chunkRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public SessionCollectionService(BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            SessionRawChunkRepository chunkRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CollectorSessionResponse start(Long userId, CollectorStartRequest request) {
        LocalDateTime startedAt = toKst(request.startedAt());
        Row row = findOrInsert(userId, request.collectorSessionId(), startedAt, true);
        return transactionTemplate.execute(status -> applyStart(row, startedAt));
    }

    // 안전장치로 먼저 만들어진 행이면 시작 시각을 요청 값으로 고친다
    private CollectorSessionResponse applyStart(Row row, LocalDateTime startedAt) {
        BrowsingSession session = lock(row.sessionId());
        SessionCollection collection = collectionRepository.getOrCreate(row.sessionId());
        if (!session.isDeleted() && collection.getStartRequestedAt() == null) {
            session.correctStartedAt(startedAt);
            collection.markStartRequested(LocalDateTime.now());
        }
        return CollectorSessionResponse.of(session, row.created());
    }

    public CollectorSessionResponse end(Long userId, String collectorSessionId, CollectorEndRequest request) {
        if (collectorSessionId.length() > MAX_COLLECTOR_SESSION_ID_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_COLLECTOR_SESSION_ID);
        }
        LocalDateTime endedAt = toKst(request.endedAt());
        Row row = findOrInsert(userId, collectorSessionId, endedAt, false);
        return transactionTemplate.execute(status -> applyEnd(row, endedAt, request));
    }

    private CollectorSessionResponse applyEnd(Row row, LocalDateTime endedAt, CollectorEndRequest request) {
        BrowsingSession session = lock(row.sessionId());
        if (session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING) {
            return CollectorSessionResponse.of(session, row.created());
        }
        SessionCollection collection = collectionRepository.getOrCreate(row.sessionId());
        collection.requestEnd(endedAt, request.stopReason(), request.totalChunks(), LocalDateTime.now());
        long received = chunkRepository.countByUserIdAndCollectorSessionId(
                session.getUserId(), session.getCollectorSessionId());
        if (received >= collection.getTotalChunks()) {
            eventPublisher.publishEvent(new SessionCollectionEndedEvent(session.getId()));
        }
        return CollectorSessionResponse.of(session, row.created());
    }

    // 청크 수신의 안전장치. 지운 세션도 찾으므로 삭제된 세션을 다시 만들지 않는다
    public Long findOrCreate(Long userId, String collectorSessionId, LocalDateTime startedAt) {
        return findOrInsert(userId, collectorSessionId, startedAt, false).sessionId();
    }

    private Row findOrInsert(Long userId, String collectorSessionId, LocalDateTime startedAt, boolean startRequested) {
        Long existing = findId(userId, collectorSessionId);
        if (existing != null) {
            return new Row(existing, false);
        }
        try {
            return new Row(transactionTemplate.execute(
                    status -> insert(userId, collectorSessionId, startedAt, startRequested)), true);
        } catch (DataIntegrityViolationException race) {
            Long winner = findId(userId, collectorSessionId);
            if (winner == null) {
                throw race;
            }
            return new Row(winner, false);
        }
    }

    private Long insert(Long userId, String collectorSessionId, LocalDateTime startedAt, boolean startRequested) {
        BrowsingSession session = sessionRepository.saveAndFlush(
                new BrowsingSession(userId, temporaryTitle(userId), startedAt, collectorSessionId));
        SessionCollection collection = new SessionCollection(session.getId());
        if (startRequested) {
            collection.markStartRequested(LocalDateTime.now());
        }
        collectionRepository.save(collection);
        return session.getId();
    }

    // 2026.11.02의 1번째 세션. 분석이 성공하면 AI 결과 제목으로 바뀐다. 번호는 표시용이라 동시 요청으로 겹칠 수 있다
    private String temporaryTitle(Long userId) {
        LocalDate today = LocalDate.now(KST);
        long order = sessionRepository.countCreatedBetween(userId,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay()) + 1;
        return today.format(TITLE_DATE) + "의 " + order + "번째 세션";
    }

    private Long findId(Long userId, String collectorSessionId) {
        return sessionRepository.findByUserIdAndCollectorSessionId(userId, collectorSessionId)
                .map(BrowsingSession::getId)
                .orElse(null);
    }

    private BrowsingSession lock(Long sessionId) {
        return sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
    }

    private static LocalDateTime toKst(Instant instant) {
        return LocalDateTime.ofInstant(instant, KST);
    }

    private record Row(
            Long sessionId,
            boolean created
    ) {
    }
}
