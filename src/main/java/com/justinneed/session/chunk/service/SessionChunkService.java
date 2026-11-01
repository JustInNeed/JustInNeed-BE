package com.justinneed.session.chunk.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.RawLinkVisits;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.dto.ChunkUploadRequest;
import com.justinneed.session.chunk.dto.ChunkUploadResponse;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.SessionCollectionEndedEvent;
import com.justinneed.session.collection.service.SessionCollectionService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 청크 수신. 같은 청크가 다시 오면 저장하지 않고 처음 받은 것을 그대로 돌려준다 (재전송해도 안전).
 * 세션 행을 수집기 세션 id 로 찾고, 시작 요청이 유실됐거나 늦으면 여기서 만든다.
 * 저장은 세션 행 잠금 안에서 한다. 합치기와 줄을 서므로, 합친 뒤에 저장되어 빠지는 청크가 생기지 않는다.
 * 지웠거나 수집이 끝난 세션의 청크는 저장하지 않고 2xx 로 답해 확장이 재전송을 멈추게 한다.
 */
@Service
public class SessionChunkService {

    private static final Logger log = LoggerFactory.getLogger(SessionChunkService.class);

    private final SessionRawChunkRepository chunkRepository;
    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final SessionCollectionService collectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public SessionChunkService(SessionRawChunkRepository chunkRepository,
            BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            SessionCollectionService collectionService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.chunkRepository = chunkRepository;
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.collectionService = collectionService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ChunkUploadResponse receive(Long userId, ChunkUploadRequest request) {
        Long sessionId = collectionService.findOrCreate(userId, request.sessionId(), startedAtOf(request.payload()));
        return transactionTemplate.execute(status -> store(sessionId, request));
    }

    private ChunkUploadResponse store(Long sessionId, ChunkUploadRequest request) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        if (session.isDeleted() || session.getStatus() != SessionStatus.COLLECTING) {
            log.info("[session chunk] session {} 은 수집 중이 아니라 청크를 저장하지 않음", sessionId);
            return ChunkUploadResponse.ignored(request.sessionId(), request.chunkId(), now);
        }

        Long userId = session.getUserId();
        var existing = chunkRepository.findByUserIdAndCollectorSessionIdAndChunkId(
                userId, request.sessionId(), request.chunkId());
        if (existing.isPresent()) {
            return ChunkUploadResponse.of(existing.get(), true);
        }

        SessionRawChunk saved = chunkRepository.saveAndFlush(new SessionRawChunk(
                userId, request.sessionId(), request.chunkId(), request.payload(), now));
        SessionCollection collection = collectionRepository.getOrCreate(sessionId);
        collection.chunkReceived(now);
        // 종료 요청 뒤 늦게 온 청크로 총 수가 찼으면 3분을 기다리지 않고 바로 합친다
        if (collection.isEndRequested()
                && chunkRepository.countByUserIdAndCollectorSessionId(userId, request.sessionId())
                >= collection.getTotalChunks()) {
            eventPublisher.publishEvent(new SessionCollectionEndedEvent(sessionId));
        }
        return ChunkUploadResponse.of(saved, false);
    }

    // 안전장치로 세션을 만들 때 쓸 시작 시각. 청크에 공통 값이 없으면 지금 시각을 쓴다
    private static LocalDateTime startedAtOf(Map<String, Object> payload) {
        LocalDateTime startedAt = payload.get("session") instanceof Map<?, ?> session
                ? RawLinkVisits.toKst(session.get("startedAt"))
                : null;
        return startedAt != null ? startedAt : LocalDateTime.now();
    }
}
