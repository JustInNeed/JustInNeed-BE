package com.justinneed.session.chunk.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.dto.ChunkUploadRequest;
import com.justinneed.session.chunk.dto.ChunkUploadResponse;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.SessionCollectionService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 청크 수신. 확장은 시작 · 종료 요청을 보내지 않으므로 세션 행은 첫 청크를 받을 때 만든다 (SessionCollectionService).
 *
 * <pre>
 * 받기 전 검사 (확장 #34)
 *   payload.memberId 가 없으면 400, 토큰의 회원과 다르면 403 — 남의 기록이 이 회원 세션으로 저장되지 않게
 * 같은 (회원, 세션 id, n) 이 이미 있으면
 *   내용이 같으면 200 duplicate. 다르면 409 — "이미 받음" 으로 답하면 확장이 지워서 새 내용이 조용히 사라진다
 * 세션 상태
 *   수집 중      저장하고, 다 왔으면 합쳐 분석으로 넘긴다 (ChunkSessionService)
 *   수집이 끝남  저장만 하고 분석에 넣지 않는다 (늦게 온 청크). 확장은 받았다는 답에 지우고 재전송을 멈춘다
 *   지운 세션    저장하지 않고 200 으로 답해 재전송을 멈추게 한다
 * </pre>
 *
 * 저장은 세션 행 잠금 안에서 한다. 합치기와 줄을 서므로 합친 뒤에 저장되어 빠지는 청크가 생기지 않고, 같은 청크가 동시에 와도 하나만 저장된다.
 * 합치기는 저장을 커밋한 뒤 따로 한다. 합치기가 실패해도 청크는 남고 요청은 성공한다 (실패 횟수는 ChunkSessionService 가 센다).
 */
@Service
public class SessionChunkService {

    private static final Logger log = LoggerFactory.getLogger(SessionChunkService.class);

    private final SessionRawChunkRepository chunkRepository;
    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final SessionCollectionService collectionService;
    private final ChunkSessionService chunkSessionService;
    private final TransactionTemplate transactionTemplate;

    public SessionChunkService(SessionRawChunkRepository chunkRepository,
            BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            SessionCollectionService collectionService,
            ChunkSessionService chunkSessionService,
            PlatformTransactionManager transactionManager) {
        this.chunkRepository = chunkRepository;
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.collectionService = collectionService;
        this.chunkSessionService = chunkSessionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ChunkUploadResponse receive(Long userId, ChunkUploadRequest request) {
        checkMember(userId, request.payload());
        // 1번 청크 안의 시작 기록 시각. 다른 번호가 먼저 오면 지금 시각으로 만들고, 1번이 오면 고친다
        LocalDateTime startedAt = request.n() == 1 ? ChunkSessionService.startedAtOf(request.payload()) : null;
        Long sessionId = collectionService.findOrCreate(userId, request.sessionId(),
                startedAt != null ? startedAt : LocalDateTime.now());
        Stored stored = transactionTemplate.execute(status -> store(sessionId, request, startedAt));
        if (stored.collecting()) {
            chunkSessionService.advance(sessionId);
        }
        return stored.response();
    }

    private Stored store(Long sessionId, ChunkUploadRequest request, LocalDateTime startedAt) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        if (session.isDeleted()) {
            log.info("[session chunk] session {} 은 지운 세션이라 청크 n {} 를 저장하지 않음", sessionId, request.n());
            return new Stored(ChunkUploadResponse.ignored(request.sessionId(), request.n(), now), false);
        }
        boolean collecting = session.getStatus() == SessionStatus.COLLECTING;

        Long userId = session.getUserId();
        var existing = chunkRepository.findByUserIdAndCollectorSessionIdAndN(userId, request.sessionId(), request.n());
        if (existing.isPresent()) {
            return new Stored(duplicateOf(existing.get(), request), collecting);
        }

        SessionRawChunk saved = chunkRepository.saveAndFlush(new SessionRawChunk(
                userId, request.sessionId(), request.n(),
                request.lastChunk(), request.maxN(), request.stopReason(),
                request.payload(), now));
        if (collecting) {
            collectionRepository.getOrCreate(sessionId).chunkReceived(now);
            if (startedAt != null && !startedAt.equals(session.getStartedAt())) {
                session.correctStartedAt(startedAt);
            }
        } else {
            log.info("[session chunk] session {} (수집기 {}) 는 이미 {} — 늦게 온 청크 n {} 는 받아만 두고 분석에 넣지 않음",
                    sessionId, request.sessionId(), session.getStatus(), request.n());
        }
        return new Stored(ChunkUploadResponse.of(saved, false), collecting);
    }

    private static void checkMember(Long userId, Map<String, Object> payload) {
        Object memberId = payload.get("memberId");
        if (!(memberId instanceof Number number) || !isWhole(number)) {
            throw new CustomException(ErrorCode.CHUNK_MEMBER_REQUIRED);
        }
        if (number.longValue() != userId) {
            throw new CustomException(ErrorCode.CHUNK_MEMBER_MISMATCH);
        }
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
    }

    private static ChunkUploadResponse duplicateOf(SessionRawChunk stored, ChunkUploadRequest request) {
        boolean same = stored.isFinalChunk() == request.lastChunk()
                && Objects.equals(stored.getMaxN(), request.maxN())
                && Objects.equals(stored.getStopReason(), request.stopReason())
                && sameJson(stored.getPayload(), request.payload());
        if (!same) {
            throw new CustomException(ErrorCode.CHUNK_CONTENT_CONFLICT);
        }
        return ChunkUploadResponse.of(stored, true);
    }

    // JSON 값 비교. 숫자는 값으로 비교한다 — DB(jsonb)에서 읽은 숫자와 요청의 숫자가 다른 자바 타입(Integer · Long · Double)일 수 있다
    static boolean sameJson(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (var k : x.keySet()) {
                if (!sameJson(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!sameJson(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    // collecting = 저장 뒤 합치기를 시도할지 (수집 중인 세션)
    private record Stored(
            ChunkUploadResponse response,
            boolean collecting
    ) {
    }
}
