package com.justinneed.community.unpin;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnpinService {
    private final CommunityProfiles profiles;

    public UnpinService(CommunityProfiles profiles) {
        this.profiles = profiles;
    }

    // 고정 목록에서만 뺀다. 공개 여부는 세션 상세에서만 바뀐다
    @Transactional
    public void unpin(Long userId, Long sessionId) {
        CommunityProfile profile = profiles.lock(userId);
        if (!profile.getPinnedSessionIds().contains(sessionId)) {
            throw new CustomException(ErrorCode.SESSION_NOT_FOUND);
        }
        profile.removePinnedSession(sessionId);
    }
}
