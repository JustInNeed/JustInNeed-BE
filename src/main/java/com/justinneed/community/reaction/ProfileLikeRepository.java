package com.justinneed.community.reaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface ProfileLikeRepository extends JpaRepository<ProfileLike, Long> {
    // 탈퇴 회원의 행은 남기고 숫자에서만 뺀다
    @Query("""
            select count(l) from ProfileLike l where l.profileId = :profileId
            and exists (select m.id from Member m where m.id = l.actorId and m.withdrawnAt is null)
            """)
    long countByProfileId(@Param("profileId") Long profileId);
    boolean existsByActorIdAndProfileId(Long actorId, Long profileId);
    Optional<ProfileLike> findByActorIdAndProfileId(Long actorId, Long profileId);
}
