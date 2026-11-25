package com.justinneed.community.reaction;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InteractionService {
    private final InteractionLocks locks;
    private final ProfileLikeRepository likes;

    public InteractionService(InteractionLocks locks, ProfileLikeRepository likes) {
        this.locks = locks;
        this.likes = likes;
    }

    public LikeView like(Long actor, Long profileId, boolean liked) {
        if (actor.equals(profileId)) throw new CustomException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        locks.acquire(actor, List.of(profileId));
        var existing = likes.findByActorIdAndProfileId(actor, profileId);
        if (liked && existing.isEmpty()) likes.save(new ProfileLike(actor, profileId));
        if (!liked) existing.ifPresent(likes::delete);
        likes.flush();
        return new LikeView(200, profileId, liked, likes.countByProfileId(profileId));
    }

    @Schema(name = "InteractionServiceLikeView")
    public record LikeView(int code, Long userId, boolean liked, long likeCount) {}
}
