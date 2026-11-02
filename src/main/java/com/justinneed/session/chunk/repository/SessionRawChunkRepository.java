package com.justinneed.session.chunk.repository;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRawChunkRepository extends JpaRepository<SessionRawChunk, Long> {

    Optional<SessionRawChunk> findByUserIdAndCollectorSessionIdAndN(
            Long userId, String collectorSessionId, int n);

    List<SessionRawChunk> findByUserIdAndCollectorSessionIdOrderByNAsc(Long userId, String collectorSessionId);

    // 번호 · final · maxN 만 (payload 는 읽지 않는다) — 다 왔는지 먼저 가볍게 본다
    @Query("select chunk.n, chunk.finalChunk, chunk.maxN from SessionRawChunk chunk"
            + " where chunk.userId = :userId and chunk.collectorSessionId = :collectorSessionId")
    List<Object[]> findHeads(@Param("userId") Long userId, @Param("collectorSessionId") String collectorSessionId);

    // 이 세션 청크를 마지막으로 받은 시각 (청크가 없으면 null)
    @Query("select max(chunk.receivedAt) from SessionRawChunk chunk"
            + " where chunk.userId = :userId and chunk.collectorSessionId = :collectorSessionId")
    LocalDateTime findLastReceivedAt(@Param("userId") Long userId, @Param("collectorSessionId") String collectorSessionId);

    @Modifying(clearAutomatically = true)
    @Query("delete from SessionRawChunk chunk where chunk.receivedAt < :before")
    int deleteReceivedBefore(@Param("before") LocalDateTime before);
}
