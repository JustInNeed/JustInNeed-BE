package com.justinneed.community.interest;

import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InterestService {
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;

    public InterestService(CommunityProfiles profiles, CommunitySharing sharing) {
        this.profiles = profiles;
        this.sharing = sharing;
    }

    public List<String> get(Long userId, Long viewerId) {
        var profile = profiles.read(userId);
        return profile.getInterests().stream()
                .filter(tag -> userId.equals(viewerId) || !sharing.isHidden(profile, tag)).toList();
    }

    @Transactional
    public List<String> add(Long userId, String hashtag) {
        String normalized = HashtagValidator.normalizeAndValidate(List.of(hashtag)).get(0);
        var profile = profiles.lock(userId);
        List<String> interests = new ArrayList<>(profile.getInterests());
        if (interests.stream().anyMatch(tag -> tag.equalsIgnoreCase(normalized))) {
            throw new CustomException(ErrorCode.DUPLICATE_HASHTAG);
        }
        interests.add(normalized);
        profile.replaceInterests(HashtagValidator.normalizeAndValidate(interests));
        return profile.getInterests();
    }
}
