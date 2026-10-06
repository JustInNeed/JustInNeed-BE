package com.justinneed.community.catalog;

import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityTags {
    private final CommunityProfiles profiles;
    private final CommunityHashtagRepository tags;
    private final BrowsingSessionRepository sessions;

    public CommunityTags(CommunityProfiles profiles, CommunityHashtagRepository tags,
            BrowsingSessionRepository sessions) {
        this.profiles = profiles;
        this.tags = tags;
        this.sessions = sessions;
    }

    @Transactional
    public List<CommunityHashtag> owned(Long ownerId) {
        var profile = profiles.lock(ownerId);
        Map<String, String> current = new LinkedHashMap<>();
        sessions.findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(ownerId)
                .forEach(s -> s.getTags().forEach(t -> current.putIfAbsent(key(t), t)));
        profile.getInterests().forEach(t -> current.putIfAbsent(key(t), t));
        profile.getHiddenHashtags().forEach(t -> current.putIfAbsent(key(t), t));
        Map<String, CommunityHashtag> indexed = new HashMap<>();
        tags.findByOwnerIdOrderByIdAsc(ownerId).forEach(t -> indexed.put(t.getNormalizedName(), t));
        // Keep IDs stable when tags are hidden, removed or later reintroduced.
        return current.entrySet().stream().map(entry -> indexed.containsKey(entry.getKey())
                ? indexed.get(entry.getKey())
                : tags.save(new CommunityHashtag(ownerId, entry.getValue()))).toList();
    }

    public static String key(String value) { return value.toLowerCase(Locale.ROOT); }
}
