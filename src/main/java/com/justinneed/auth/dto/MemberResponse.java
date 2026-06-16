package com.justinneed.auth.dto;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import java.time.LocalDateTime;

public record MemberResponse(
        Long id,
        String nickname,
        SocialProvider socialProvider,
        String email,
        LocalDateTime joinedAt
) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getNickname(),
                member.getSocialProvider(),
                member.getEmail(),
                member.getJoinedAt()
        );
    }
}
