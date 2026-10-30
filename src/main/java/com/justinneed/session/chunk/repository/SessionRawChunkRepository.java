package com.justinneed.session.chunk.repository;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRawChunkRepository extends JpaRepository<SessionRawChunk, Long> {

    Optional<SessionRawChunk> findByUserIdAndCollectorSessionIdAndSegIdAndN(
            Long userId, String collectorSessionId, String segId, int n);
}
