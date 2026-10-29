package com.justinneed.session.analysis.repository;

import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiClusterModelRepository extends JpaRepository<AiClusterModel, Long> {

    Optional<AiClusterModel> findFirstByScope(ClusterModelScope scope);

    Optional<AiClusterModel> findByScopeAndUserId(ClusterModelScope scope, Long userId);
}
