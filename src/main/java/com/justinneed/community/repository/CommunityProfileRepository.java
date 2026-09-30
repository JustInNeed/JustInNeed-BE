package com.justinneed.community.repository;

import com.justinneed.community.domain.CommunityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityProfileRepository extends JpaRepository<CommunityProfile, Long> {
}
