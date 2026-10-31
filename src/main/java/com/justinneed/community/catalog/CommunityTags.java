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
    private static final int MAX_NAME_LENGTH = 10;

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

    /** 지금 이 회원의 해시태그: 지우지 않은 세션의 태그 + 관심사 + 비공개 설정. 행은 쓰기 시점에 만들어 둔다 (CommunityCardSync · ensure). */
    @Transactional(readOnly = true)
    public List<CommunityHashtag> owned(Long ownerId) {
        var profile = profiles.read(ownerId);
        Set<String> current = new LinkedHashSet<>();
        sessions.findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(ownerId)
                .forEach(s -> s.getTags().forEach(t -> current.add(key(t))));
        profile.getInterests().forEach(t -> current.add(key(t)));
        profile.getHiddenHashtags().forEach(t -> current.add(key(t)));
        var indexed = index(ownerId);
        return current.stream().map(indexed::get).filter(Objects::nonNull).toList();
    }

    /** 이 회원의 해시태그 행 전체. 숨기거나 빠진 태그도 id 가 그대로라 다시 나타나도 같은 행이다. 소문자 이름 → 행. */
    @Transactional(readOnly = true)
    public Map<String, CommunityHashtag> index(Long ownerId) {
        Map<String, CommunityHashtag> indexed = new LinkedHashMap<>();
        tags.findByOwnerIdOrderByIdAsc(ownerId).forEach(t -> indexed.put(t.getNormalizedName(), t));
        return indexed;
    }

    /** 작성자별 index. 작성자 수와 상관없이 한 번만 조회한다. */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, CommunityHashtag>> indexes(Collection<Long> ownerIds) {
        Map<Long, Map<String, CommunityHashtag>> indexed = new HashMap<>();
        if (ownerIds.isEmpty()) return indexed;
        tags.findByOwnerIdInOrderByIdAsc(ownerIds).forEach(t ->
                indexed.computeIfAbsent(t.getOwnerId(), id -> new LinkedHashMap<>()).put(t.getNormalizedName(), t));
        return indexed;
    }

    /** 없는 태그 행만 만든다. 동시에 들어와도 교착이 없도록 정렬한 순서로 넣는다. */
    @Transactional
    public void ensure(Long ownerId, Collection<String> names) {
        if (names.isEmpty()) return;
        var existing = index(ownerId);
        Map<String, String> missing = new TreeMap<>();
        for (String name : names) {
            // 컬럼 길이를 넘는 옛 데이터는 건너뛴다. 조회는 행이 없는 태그를 빼고 보여준다
            if (name.length() <= MAX_NAME_LENGTH && !existing.containsKey(key(name))) {
                missing.putIfAbsent(key(name), name);
            }
        }
        missing.forEach((normalized, name) -> tags.insertIfAbsent(ownerId, name, normalized));
    }

    /** Ids among the given ones whose hashtag and (not withdrawn) owner still exist and the owner has not made private. */
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
