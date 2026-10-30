package com.justinneed.community.catalog;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityTags {
    private final CommunityProfiles profiles;
    private final CommunityHashtagRepository tags;
    private final BrowsingSessionRepository sessions;
    private final CommunityProfileRepository profileRows;
    private final CommunitySharing sharing;

    public CommunityTags(CommunityProfiles profiles, CommunityHashtagRepository tags,
            BrowsingSessionRepository sessions, CommunityProfileRepository profileRows, CommunitySharing sharing) {
        this.profiles = profiles;
        this.tags = tags;
        this.sessions = sessions;
        this.profileRows = profileRows;
        this.sharing = sharing;
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

    /** Ids among the given ones whose hashtag and owner still exist and the owner has not made private. */
    @Transactional(readOnly = true)
    public Set<Long> publicIds(Collection<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        var found = tags.findWithOwnerByIdIn(new HashSet<>(ids));
        Map<Long, CommunityProfile> owners = new HashMap<>();
        profileRows.findAllById(found.stream().map(CommunityHashtag::getOwnerId).distinct().toList())
                .forEach(p -> owners.put(p.getUserId(), p));
        var result = new HashSet<Long>();
        for (var tag : found) {
            var owner = owners.get(tag.getOwnerId());
            if (owner == null || !sharing.isHidden(owner, tag.getName())) result.add(tag.getId());
        }
        return result;
    }

    public static String key(String value) { return value.toLowerCase(Locale.ROOT); }
}
