package com.justinneed.community.visibility;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VisibilityService {
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;

    public VisibilityService(CommunityProfiles profiles, CommunitySharing sharing) {
        this.profiles = profiles;
        this.sharing = sharing;
    }

    public VisibilityResponse get(Long userId) {
        return new VisibilityResponse(profiles.read(userId).getVisibility());
    }

    @Transactional
    public VisibilityResponse update(Long userId, NodeVisibility visibility) {
        profiles.lock(userId).changeVisibility(visibility);
        return new VisibilityResponse(visibility);
    }

    public List<NodeResponse> nodes(Long userId, Long viewerId) {
        CommunityProfile profile = profiles.read(userId);
        Map<String, List<SharedSessionView>> sessionsByTag = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (var session : sharing.visibleSessions(profile, viewerId)) {
            var view = sharing.project(profile, viewerId, session);
            for (String tag : session.getTags()) {
                String key = tag.toLowerCase(Locale.ROOT);
                labels.putIfAbsent(key, tag);
                sessionsByTag.computeIfAbsent(key, ignored -> new ArrayList<>()).add(view);
            }
        }
        return sessionsByTag.entrySet().stream()
                .map(entry -> new NodeResponse(labels.get(entry.getKey()), entry.getValue())).toList();
    }

    public record VisibilityResponse(NodeVisibility visibility) { }
    public record NodeResponse(String hashtag, List<SharedSessionView> sessions) { }
}
