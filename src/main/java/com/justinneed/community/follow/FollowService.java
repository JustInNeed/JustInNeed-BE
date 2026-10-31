package com.justinneed.community.follow;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FollowService {
    private final CommunityFollowRepository follows;
    private final CommunityMemberRepository members;
    private final Clock clock;
    public FollowService(CommunityFollowRepository follows, CommunityMemberRepository members, Clock clock) {
        this.follows = follows; this.members = members; this.clock = clock;
    }
    public FollowView follow(Long actor, Long target, boolean following) {
        if (actor.equals(target)) throw CommunityErrors.invalid("자기 자신은 팔로우할 수 없습니다.");
        if (!members.existsByIdAndWithdrawnAtIsNull(target)) throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        if (following) follows.insertIfAbsent(actor, target, LocalDateTime.now(clock));
        else follows.deletePair(actor, target);
        return new FollowView(following);
    }
    public record FollowView(boolean following) { }
}
