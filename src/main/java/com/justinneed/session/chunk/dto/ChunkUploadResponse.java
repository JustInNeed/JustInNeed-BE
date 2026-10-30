package com.justinneed.session.chunk.dto;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.time.LocalDateTime;

// duplicate: 이미 받은 청크라 이번 요청은 무시했으면 true (receivedAt 은 처음 받은 시각)
public record ChunkUploadResponse(
        String sessionId,
        String segId,
        int n,
        boolean duplicate,
        LocalDateTime receivedAt
) {

    public static ChunkUploadResponse of(SessionRawChunk chunk, boolean duplicate) {
        return new ChunkUploadResponse(chunk.getCollectorSessionId(), chunk.getSegId(), chunk.getN(),
                duplicate, chunk.getReceivedAt());
    }
}
