package com.justinneed.session.chunk.repository;

import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.management.domain.SessionStatus;
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

    long countByUserIdAndCollectorSessionId(Long userId, String collectorSessionId);

    List<SessionRawChunk> findByUserIdAndCollectorSessionIdOrderByNAsc(Long userId, String collectorSessionId);

    // 번호 · final · maxN 만 (payload 는 읽지 않는다) — 다 왔는지 먼저 가볍게 본다
    @Query("select chunk.n, chunk.finalChunk, chunk.maxN from SessionRawChunk chunk"
            + " where chunk.userId = :userId and chunk.collectorSessionId = :collectorSessionId")
    List<Object[]> findHeads(@Param("userId") Long userId, @Param("collectorSessionId") String collectorSessionId);

    @Modifying
    @Query("""
            delete from SessionRawChunk chunk
            where chunk.userId = :userId
              and chunk.collectorSessionId = :collectorSessionId
            """)
    int deleteBySession(@Param("userId") Long userId, @Param("collectorSessionId") String collectorSessionId);

    // 분석에 성공했고 마지막 청크를 받은 지 보관 기한이 지난 세션의 청크.
    // 확장이 재전송하는 기간 동안만 다시 온 청크의 내용 비교(409)에 쓴다
    default int deleteCompletedBefore(LocalDateTime lastReceivedBefore) {
        return deleteCompletedBefore(SessionStatus.COMPLETED, lastReceivedBefore);
    }

    @Modifying
    @Query("""
            delete from SessionRawChunk chunk
            where exists (select 1
                          from BrowsingSession session
                          where session.userId = chunk.userId
                            and session.collectorSessionId = chunk.collectorSessionId
                            and session.status = :status)
              and not exists (select 1
                              from SessionRawChunk recent
                              where recent.userId = chunk.userId
                                and recent.collectorSessionId = chunk.collectorSessionId
                                and recent.receivedAt >= :lastReceivedBefore)
            """)
    int deleteCompletedBefore(@Param("status") SessionStatus status,
            @Param("lastReceivedBefore") LocalDateTime lastReceivedBefore);

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
