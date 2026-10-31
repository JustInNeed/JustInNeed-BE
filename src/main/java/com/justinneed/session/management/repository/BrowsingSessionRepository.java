package com.justinneed.session.management.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowsingSessionRepository extends JpaRepository<BrowsingSession, Long> {

    // 끝난 상태. 이 밖의 상태(수집 중 · 분석 대기 · 분석 중 등)는 모두 진행 중으로 본다
    List<SessionStatus> FINISHED_STATUSES = List.of(SessionStatus.COMPLETED, SessionStatus.FAILED);

    // 목록 · 태그 그룹 조회(findListPage · findListByUserId)는 summary 를 fetch join 으로 함께 읽는다.
    // summary 는 mappedBy 쪽 1:1 이라 바이트코드 강화 없이는 LAZY 가 안 돼, 따로 두면 세션마다 summary 조회가 한 번씩 더 나간다 (N+1).
    // fetch 대상이 1:1 이라 행 수가 늘지 않아 Slice 의 limit / offset 도 DB 에서 그대로 적용된다

    // 세션 목록 · 태그 그룹 공통 정렬 (REC-10-1 기록 종료 시각 기준 최신순)
    // 1. 진행 중 세션을 맨 위에 두고, 그 안에서는 시작 시각 최신순 (종료 시각은 보지 않는다)
    // 2. 끝난 세션(완료 · 실패)은 종료 시각 최신순, 종료 시각이 없으면 뒤로
    //    NULL 정렬은 DB 마다 다르다 (Postgres 는 desc 에서 NULL 이 앞, H2 는 설정에 따라 다름).
    //    nulls last 는 Hibernate 가 방언 기본값과 같다고 보면 SQL 에서 빼 버리므로, 종료 시각 유무를 따로 먼저 비교한다
    // 3. 시작 시각 최신순, 마지막으로 id 역순이라 같은 시각이어도 페이지 경계가 흔들리지 않는다
    String LIST_ORDER = """
            order by case when session.status in :finished then 1 else 0 end,
                     case when session.status in :finished and session.endedAt is null then 1 else 0 end,
                     case when session.status in :finished then session.endedAt end desc,
                     session.startedAt desc,
                     session.id desc
            """;

    List<BrowsingSession> findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(Long userId);

    // 세션 목록 한 페이지. favoriteOnly 면 즐겨찾기만 (REC-10-3)
    default Slice<BrowsingSession> findListPage(Long userId, boolean favoriteOnly, Pageable pageable) {
        return findListPage(userId, favoriteOnly, FINISHED_STATUSES, pageable);
    }

    @Query("""
            select session
            from BrowsingSession session
            left join fetch session.summary
            where session.userId = :userId
              and session.deletedAt is null
              and (:favoriteOnly = false or session.favorite = true)
            """ + LIST_ORDER)
    Slice<BrowsingSession> findListPage(
            @Param("userId") Long userId,
            @Param("favoriteOnly") boolean favoriteOnly,
            @Param("finished") List<SessionStatus> finished,
            Pageable pageable
    );

    // 태그 그룹에 묶을 내 세션 전체. 목록과 같은 순서
    default List<BrowsingSession> findListByUserId(Long userId) {
        return findListByUserId(userId, FINISHED_STATUSES);
    }

    @Query("""
            select session
            from BrowsingSession session
            left join fetch session.summary
            where session.userId = :userId
              and session.deletedAt is null
            """ + LIST_ORDER)
    List<BrowsingSession> findListByUserId(
            @Param("userId") Long userId,
            @Param("finished") List<SessionStatus> finished
    );

    // 내 세션들의 태그 목록(세션당 한 줄), 목록과 같은 순서. 태그 컬럼만 읽어 summary 는 조회하지 않는다
    default List<List<String>> findTagsByUserId(Long userId) {
        return findTagsByUserId(userId, FINISHED_STATUSES);
    }

    @Query("""
            select session.tags
            from BrowsingSession session
            where session.userId = :userId
              and session.deletedAt is null
            """ + LIST_ORDER)
    List<List<String>> findTagsByUserId(
            @Param("userId") Long userId,
            @Param("finished") List<SessionStatus> finished
    );

    long countByUserIdAndDeletedAtIsNull(Long userId);

    long countByUserIdAndFavoriteTrueAndDeletedAtIsNull(Long userId);

    @EntityGraph(attributePaths = "summary")
    @Query("""
            select session
            from BrowsingSession session
            where session.id = :id
              and session.deletedAt is null
            """)
    Optional<BrowsingSession> findWithSummaryByIdAndDeletedAtIsNull(@Param("id") Long id);

    // 세션 행을 바꾸는 경로는 트랜잭션에서 이 잠금을 가장 먼저 잡는다. 그래야 기다린 뒤 최신 행을 읽어 쓰기가 줄을 선다.
    // 회원 잠금(CommunityProfiles.lock)도 잡을 때만 그쪽이 먼저다 (회원 → 세션).
    // fetch join 을 쓰면 Postgres 가 outer join 의 nullable 쪽 FOR UPDATE 를 거부하므로 세션만 잠근다.
    // summary 는 세션을 읽을 때 따로 조회되고(mappedBy 쪽 1:1), 잠금 뒤의 조회라 역시 최신 값이다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BrowsingSession session where session.id = :id")
    Optional<BrowsingSession> lockById(@Param("id") Long id);

    // 소유자 · 삭제 여부는 잠금을 잡은 뒤의 행으로 판단한다 (기다리는 동안 삭제될 수 있다)
    default Optional<BrowsingSession> lockOwned(Long id, Long userId) {
        return lockById(id).filter(session -> session.getUserId().equals(userId) && !session.isDeleted());
    }
}
