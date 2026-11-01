package com.justinneed.session.chunk.dto;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.time.LocalDateTime;

// duplicate: 이미 받은 청크라 이번 요청은 무시했으면 true (receivedAt 은 처음 받은 시각)
// ignored: 지웠거나 수집이 끝난 세션이라 저장하지 않았으면 true. 확장이 재전송을 멈추도록 성공으로 답한다
public record ChunkUploadResponse(
        String sessionId,
        String chunkId,
        boolean duplicate,
        boolean ignored,
        LocalDateTime receivedAt
) {

    public static ChunkUploadResponse of(SessionRawChunk chunk, boolean duplicate) {
        return new ChunkUploadResponse(chunk.getCollectorSessionId(), chunk.getChunkId(),
                duplicate, false, chunk.getReceivedAt());
    }

    public static ChunkUploadResponse ignored(String sessionId, String chunkId, LocalDateTime receivedAt) {
        return new ChunkUploadResponse(sessionId, chunkId, false, true, receivedAt);
    }
}
