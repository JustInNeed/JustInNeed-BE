package com.justinneed.community.interestdelete;

import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterestDeleteService {
    private final CommunityProfiles profiles;

    public InterestDeleteService(CommunityProfiles profiles) {
        this.profiles = profiles;
    }

    @Transactional
    public List<String> delete(Long userId, String hashtag) {
        String normalized = HashtagValidator.normalizeAndValidate(List.of(hashtag)).get(0);
        var profile = profiles.lock(userId);
        List<String> interests = new ArrayList<>(profile.getInterests());
        if (!interests.removeIf(tag -> tag.equalsIgnoreCase(normalized))) {
            throw new CustomException(ErrorCode.INTEREST_NOT_FOUND);
        }
        profile.replaceInterests(interests);
        return profile.getInterests();
    }
}
