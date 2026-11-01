package com.justinneed.community.edit;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityHashtag;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.PinCandidateRow;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.SessionStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileSaveService {
    private final CommunityProfiles profiles;
    private final CommunityTags tags;
    private final CommunitySessionRepository sessions;

    public ProfileSaveService(CommunityProfiles profiles, CommunityTags tags, CommunitySessionRepository sessions) {
        this.profiles = profiles;
        this.tags = tags;
        this.sessions = sessions;
    }

    /** 프로필 수정 화면의 저장 한 번. 하나라도 실패하면 아무것도 저장되지 않는다. */
    @Transactional
    public void save(Long userId, String bio, NodeVisibility visibility, List<String> interests,
            List<Long> hiddenHashtagIds, List<Long> pinnedSessionIds) {
        CommunityProfile profile = profiles.lock(userId);
        // 화면이 받은 해시태그 id 는 저장 전 목록 기준이라 관심사를 바꾸기 전에 읽는다
        List<CommunityHashtag> owned = tags.owned(userId);

        profile.changeBio(bio.strip());
        List<String> nextInterests = HashtagValidator.normalizeAndValidate(interests);
        profile.replaceInterests(nextInterests);
        tags.ensure(userId, nextInterests);
        profile.replaceHiddenHashtags(hidden(profile, owned, hiddenHashtagIds));
        profile.replacePinnedSessionIds(pins(userId, pinnedSessionIds));
        profile.changeVisibility(visibility);
    }

    private static List<String> hidden(CommunityProfile profile, List<CommunityHashtag> owned, List<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        owned.forEach(tag -> names.put(tag.getId(), tag.getNormalizedName()));
        // 태그 행이 없는 옛 항목은 id 가 없어 화면이 보낼 수 없다. 요청에 없다고 지우면 다시 공개되므로 그대로 둔다
        Set<String> withRow = new HashSet<>(names.values());
        List<String> hidden = new ArrayList<>(profile.getHiddenHashtags());
        hidden.removeIf(tag -> withRow.contains(CommunityTags.key(tag)));
        for (Long id : new LinkedHashSet<>(ids)) {
            String name = names.get(id);
            if (name == null) {
                throw CommunityErrors.missing();
            }
            hidden.add(name);
        }
        return hidden;
    }

    // 공개 여부는 바꾸지 않는다. 고정은 공개 세션 안에서 맨 위에 두는 것이다
    private List<Long> pins(Long userId, List<Long> ids) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new CustomException(ErrorCode.INVALID_PIN_ORDER);
        }
        if (ids.isEmpty()) {
            return ids;
        }
        Map<Long, PinCandidateRow> found = sessions.findPinCandidates(userId, ids).stream()
                .collect(Collectors.toMap(PinCandidateRow::id, Function.identity()));
        for (Long id : ids) {
            PinCandidateRow session = found.get(id);
            if (session == null) {
                throw new CustomException(ErrorCode.SESSION_NOT_FOUND);
            }
            if (session.status() != SessionStatus.COMPLETED) {
                throw new CustomException(ErrorCode.SESSION_NOT_COMPLETED);
            }
            if (!session.publicSession()) {
                throw new CustomException(ErrorCode.PIN_REQUIRES_PUBLIC);
            }
        }
        return ids;
    }
}
