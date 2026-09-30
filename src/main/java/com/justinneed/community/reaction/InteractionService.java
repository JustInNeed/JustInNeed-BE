package com.justinneed.community.reaction;

import com.justinneed.community.activity.*;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.TargetType;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class InteractionService {
    private final InteractionLocks locks;
    private final ProfileLikeRepository likes;
    private final ExplorationRepository explorations;
    private final TargetResolver targets;
    private final ActivityRecorder activity;
    public InteractionService(InteractionLocks locks, ProfileLikeRepository likes, ExplorationRepository explorations,
            TargetResolver targets, ActivityRecorder activity) {
        this.locks = locks; this.likes = likes; this.explorations = explorations;
        this.targets = targets; this.activity = activity;
    }
    public LikeView like(Long actor, Long profileId, boolean liked) {
        if (actor.equals(profileId)) throw CommunityErrors.invalid("Cannot like your own profile");
        locks.acquire(actor, List.of(profileId));
        var existing = likes.findByActorIdAndProfileId(actor, profileId);
        if (liked && existing.isEmpty()) likes.save(new ProfileLike(actor, profileId));
        if (!liked) existing.ifPresent(likes::delete);
        likes.flush();
        return new LikeView(200, profileId, liked, likes.countByProfileId(profileId));
    }
    public ExploringView exploring(Long actor, TargetType type, Long id, boolean exploring) {
        locks.acquire(actor, List.of(targets.owner(type, id)));
        var existing = explorations.findByActorIdAndTargetTypeAndTargetId(actor, type, id);
        TargetResolver.Resolved target;
        try { target = targets.resolve(type, id); }
        catch (ResponseStatusException error) {
            if (exploring || error.getStatusCode().value() != 404) throw error;
            existing.ifPresent(explorations::delete);
            return new ExploringView(200, type, id, false, 0);
        }
        if (exploring && existing.isEmpty()) {
            explorations.save(new Exploration(actor, type, id));
            activity.record(actor, type, id, ActivityKind.EXPLORE, target.tags());
        }
        if (!exploring) existing.ifPresent(explorations::delete);
        explorations.flush();
        return new ExploringView(200, type, id, exploring, explorations.countByTargetTypeAndTargetId(type, id));
    }
    public ExploringView status(Long actor, TargetType type, Long id) {
        locks.acquire(actor, List.of(targets.owner(type, id)));
        targets.resolve(type, id);
        return new ExploringView(200, type, id,
                explorations.findByActorIdAndTargetTypeAndTargetId(actor, type, id).isPresent(),
                explorations.countByTargetTypeAndTargetId(type, id));
    }
    public record LikeView(int code, Long userId, boolean liked, long likeCount) { }
    public record ExploringView(int code, TargetType targetType, Long targetId, boolean exploring, long exploringCount) { }
}
