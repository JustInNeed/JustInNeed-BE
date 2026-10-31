package com.justinneed.community.service;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityProfiles {
    private final CommunityMemberRepository members;
    private final CommunityProfileRepository profiles;

    public CommunityProfiles(CommunityMemberRepository members, CommunityProfileRepository profiles) {
        this.members = members;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public CommunityProfile read(Long userId) {
        if (!members.existsByIdAndWithdrawnAtIsNull(userId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return profiles.findById(userId).orElseGet(() -> new CommunityProfile(userId));
    }

    // 세션 행도 잠그는 경로는 이 잠금을 먼저 잡는다 (회원 → 세션). 반대 순서가 섞이면 교착이 생긴다
    @Transactional(propagation = Propagation.MANDATORY)
    public CommunityProfile lock(Long userId) {
        // Lock the existing member row even before the first profile is created.
        members.lockById(userId).orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        return profiles.findById(userId).orElseGet(() -> profiles.save(new CommunityProfile(userId)));
    }
}
