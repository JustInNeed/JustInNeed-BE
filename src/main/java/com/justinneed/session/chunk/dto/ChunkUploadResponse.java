package com.justinneed.session.chunk.dto;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.time.LocalDateTime;

public record ChunkUploadResponse(
        String sessionId,
        int n,
        boolean duplicate,
        // 지운 세션의 청크라 저장하지 않음 (200 으로 답해 확장이 재전송을 멈추게 한다)
        boolean ignored,
        LocalDateTime receivedAt
) {

    public static ChunkUploadResponse of(SessionRawChunk chunk, boolean duplicate) {
        return new ChunkUploadResponse(chunk.getCollectorSessionId(), chunk.getN(),
                duplicate, false, chunk.getReceivedAt());
    }

    public static ChunkUploadResponse ignored(String sessionId, int n, LocalDateTime receivedAt) {
        return new ChunkUploadResponse(sessionId, n, false, true, receivedAt);
    }
}
