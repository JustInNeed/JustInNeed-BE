package com.justinneed.surfing;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface SurfSessionRepository extends JpaRepository<SurfSession,Long> {
    boolean existsByUserIdAndState(Long user, SurfSession.State state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SurfSession s where s.id=:id and s.userId=:user")
    Optional<SurfSession> owned(Long id, Long user);
    List<SurfSession> findByStateOrderByIdAsc(SurfSession.State state, Pageable page);
    List<SurfSession> findByStateAndLastActivityAtBeforeOrderByIdAsc(SurfSession.State state, java.time.Instant cutoff, Pageable page);
    List<SurfSession> findByUserIdOrderByStartedAtDesc(Long user);
    List<SurfSession> findByUserIdAndStartedAtLessThanAndEndedAtGreaterThanEqualOrderByStartedAtDesc(
        Long user, java.time.Instant end, java.time.Instant start);
}
