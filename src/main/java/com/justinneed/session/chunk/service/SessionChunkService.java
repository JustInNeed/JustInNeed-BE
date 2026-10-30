package com.justinneed.session.chunk.service;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.dto.ChunkUploadRequest;
import com.justinneed.session.chunk.dto.ChunkUploadResponse;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 청크 수신. 같은 청크가 다시 오면 저장하지 않고 처음 받은 것을 그대로 돌려준다 (재전송해도 안전).
 * 동시에 같은 청크가 두 번 들어오면 유니크 제약에 걸린 쪽을 중복으로 처리한다 — 그래서 트랜잭션으로 묶지 않는다.
 */
@Service
public class SessionChunkService {

    private final SessionRawChunkRepository chunkRepository;

    public SessionChunkService(SessionRawChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public ChunkUploadResponse receive(Long userId, ChunkUploadRequest request) {
        var existing = find(userId, request);
        if (existing != null) {
            return ChunkUploadResponse.of(existing, true);
        }
        try {
            SessionRawChunk saved = chunkRepository.saveAndFlush(new SessionRawChunk(
                    userId, request.sessionId(), request.segId(), request.n(),
                    request.lastChunk(), request.maxN(), request.stopReason(),
                    request.payload(), LocalDateTime.now()));
            return ChunkUploadResponse.of(saved, false);
        } catch (DataIntegrityViolationException race) {
            var winner = find(userId, request);
            if (winner == null) {
                throw race;
            }
            return ChunkUploadResponse.of(winner, true);
        }
    }

    private SessionRawChunk find(Long userId, ChunkUploadRequest request) {
        return chunkRepository.findByUserIdAndCollectorSessionIdAndSegIdAndN(
                userId, request.sessionId(), request.segId(), request.n()).orElse(null);
    }
}
