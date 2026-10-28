package com.justinneed.community.reaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProfileLikeRepository extends JpaRepository<ProfileLike, Long> {
    long countByProfileId(Long profileId);
    boolean existsByActorIdAndProfileId(Long actorId, Long profileId);
    Optional<ProfileLike> findByActorIdAndProfileId(Long actorId, Long profileId);
}
