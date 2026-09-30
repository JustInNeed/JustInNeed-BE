package com.justinneed.community.graph;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CommunityNodeRepository extends JpaRepository<CommunityNode, Long> {
    List<CommunityNode> findByOwnerId(Long ownerId);
}
