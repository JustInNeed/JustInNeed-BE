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
