package com.justinneed.surfing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SurfScrapRepository extends JpaRepository<SurfScrap,Long> {
    List<SurfScrap> findBySessionIdOrderBySavedAtAsc(Long session);
    Optional<SurfScrap> findBySessionIdAndClientId(Long session,String client);
    long countBySessionId(Long session);
}
