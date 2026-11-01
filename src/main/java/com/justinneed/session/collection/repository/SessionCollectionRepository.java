package com.justinneed.session.collection.repository;

import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionCollectionRepository extends JpaRepository<SessionCollection, Long> {

    // 이 기능 이전에 만든 세션은 행이 없다. 세션 행을 잠근 뒤에 호출한다
    default SessionCollection getOrCreate(Long sessionId) {
        return findById(sessionId).orElseGet(() -> save(new SessionCollection(sessionId)));
    }

    // 합칠 때가 된 수집 중 세션. 누락 대기가 끝난 종료 요청, 또는 종료 요청 없이 자동 종료 시간이 지난 세션.
    // 합치기가 끝나기 전에 서버가 죽은 세션도 누락 대기가 지나면 여기로 다시 잡힌다
    default List<Long> findMergeDue(LocalDateTime endRequestedBefore, LocalDateTime lastActiveBefore) {
        return findMergeDue(SessionStatus.COLLECTING, endRequestedBefore, lastActiveBefore);
    }

    @Query("""
            select collection.sessionId
            from SessionCollection collection
            join BrowsingSession session on session.id = collection.sessionId
            where session.status = :status
              and session.deletedAt is null
              and ((collection.endRequestedAt is not null and collection.endRequestedAt <= :endRequestedBefore)
                or (collection.endRequestedAt is null and collection.lastChunkAt <= :lastActiveBefore)
                or (collection.endRequestedAt is null and collection.lastChunkAt is null
                    and session.startedAt <= :lastActiveBefore))
            """)
    List<Long> findMergeDue(
            @Param("status") SessionStatus status,
            @Param("endRequestedBefore") LocalDateTime endRequestedBefore,
            @Param("lastActiveBefore") LocalDateTime lastActiveBefore
    );

    // AI 가 아직 GET /input 을 부르지 않은 분석 중 세션
    default List<Long> findAwaitingInput() {
        return findAwaitingInput(SessionStatus.ANALYZING);
    }

    @Query("""
            select collection.sessionId
            from SessionCollection collection
            join BrowsingSession session on session.id = collection.sessionId
            where session.status = :status
              and session.deletedAt is null
              and collection.aiRequestedAt is not null
              and collection.inputFetchedAt is null
            """)
    List<Long> findAwaitingInput(@Param("status") SessionStatus status);

    // GET /input 뒤 콜백이 오지 않은 분석 중 세션
    default List<Long> findCallbackOverdue(LocalDateTime fetchedBefore) {
        return findCallbackOverdue(SessionStatus.ANALYZING, fetchedBefore);
    }

    @Query("""
            select collection.sessionId
            from SessionCollection collection
            join BrowsingSession session on session.id = collection.sessionId
            where session.status = :status
              and session.deletedAt is null
              and collection.inputFetchedAt <= :fetchedBefore
            """)
    List<Long> findCallbackOverdue(
            @Param("status") SessionStatus status,
            @Param("fetchedBefore") LocalDateTime fetchedBefore
    );
}
