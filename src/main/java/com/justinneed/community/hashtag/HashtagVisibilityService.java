package com.justinneed.community.hashtag;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HashtagVisibilityService {
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;
    private final BrowsingSessionRepository sessions;

    public HashtagVisibilityService(CommunityProfiles profiles, CommunitySharing sharing,
            BrowsingSessionRepository sessions) {
        this.profiles = profiles;
        this.sharing = sharing;
        this.sessions = sessions;
    }

    public List<HashtagResponse> get(Long userId) {
        CommunityProfile profile = profiles.read(userId);
        return tags(profile).values().stream()
                .map(tag -> new HashtagResponse(tag, !sharing.isHidden(profile, tag))).toList();
    }

    @Transactional
    public HashtagResponse update(Long userId, String hashtag, boolean isPublic) {
        String normalized = HashtagValidator.normalizeAndValidate(List.of(hashtag)).get(0);
        CommunityProfile profile = profiles.lock(userId);
        String label = tags(profile).get(normalized.toLowerCase(Locale.ROOT));
        if (label == null) {
            throw new CustomException(ErrorCode.INVALID_HASHTAG);
        }
        List<String> hidden = new ArrayList<>(profile.getHiddenHashtags());
        hidden.removeIf(tag -> tag.equalsIgnoreCase(normalized));
        if (!isPublic) {
            hidden.add(normalized.toLowerCase(Locale.ROOT));
        }
        profile.replaceHiddenHashtags(hidden);
        return new HashtagResponse(label, isPublic);
    }

    private Map<String, String> tags(CommunityProfile profile) {
        Map<String, String> result = new LinkedHashMap<>();
        sessions.findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(profile.getUserId())
                .forEach(session -> session.getTags().forEach(tag ->
                        result.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag)));
        profile.getInterests().forEach(tag -> result.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag));
        // Keep preferences discoverable after the last session with a hidden tag is removed.
        profile.getHiddenHashtags().forEach(tag -> result.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag));
        return result;
    }

    public record HashtagResponse(String hashtag, boolean isPublic) { }
}
