package com.justinneed.session.chunk.repository;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRawChunkRepository extends JpaRepository<SessionRawChunk, Long> {

    Optional<SessionRawChunk> findByUserIdAndCollectorSessionIdAndChunkId(
            Long userId, String collectorSessionId, String chunkId);

    long countByUserIdAndCollectorSessionId(Long userId, String collectorSessionId);

    // 합칠 청크 내용을 받은 순서로. 큰 JSON 이라 엔티티로 읽어 스냅샷을 만들지 않는다
    @Query("""
            select chunk.payload
            from SessionRawChunk chunk
            where chunk.userId = :userId
              and chunk.collectorSessionId = :collectorSessionId
            order by chunk.id
            """)
    List<Map<String, Object>> findPayloads(
            @Param("userId") Long userId,
            @Param("collectorSessionId") String collectorSessionId
    );

    @Modifying
    @Query("""
            delete from SessionRawChunk chunk
            where chunk.userId = :userId
              and chunk.collectorSessionId = :collectorSessionId
            """)
    int deleteBySession(@Param("userId") Long userId, @Param("collectorSessionId") String collectorSessionId);

    // 실패한 지 보관 기한이 지난 세션의 청크
    default int deleteFailedBefore(LocalDateTime failedBefore) {
        return deleteFailedBefore(SessionStatus.FAILED, failedBefore);
    }

    @Modifying
    @Query("""
            delete from SessionRawChunk chunk
            where exists (select 1
                          from BrowsingSession session
                          join SessionCollection collection on collection.sessionId = session.id
                          where session.userId = chunk.userId
                            and session.collectorSessionId = chunk.collectorSessionId
                            and session.status = :status
                            and collection.failedAt < :failedBefore)
            """)
    int deleteFailedBefore(@Param("status") SessionStatus status, @Param("failedBefore") LocalDateTime failedBefore);
}
