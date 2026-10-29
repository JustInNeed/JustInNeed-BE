package com.justinneed.surfing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SurfEventRepository extends JpaRepository<SurfEvent,Long> {
    List<SurfEvent> findBySessionIdOrderByOccurredAtAscIdAsc(Long session);
    boolean existsBySessionIdAndClientId(Long session,String client);
    long countBySessionId(Long session);
}
