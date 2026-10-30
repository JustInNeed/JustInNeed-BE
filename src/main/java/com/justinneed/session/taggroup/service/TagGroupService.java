package com.justinneed.session.taggroup.service;

import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.dto.TagGroupCreateRequest;
import com.justinneed.session.taggroup.dto.TagGroupOrderRequest;
import com.justinneed.session.taggroup.dto.TagGroupResponse;
import com.justinneed.session.taggroup.dto.TagGroupUpdateRequest;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TagGroupService {

    private final TagGroupRepository tagGroupRepository;
    private final BrowsingSessionRepository sessionRepository;

    public TagGroupService(TagGroupRepository tagGroupRepository, BrowsingSessionRepository sessionRepository) {
        this.tagGroupRepository = tagGroupRepository;
        this.sessionRepository = sessionRepository;
    }

    public List<TagGroupResponse> getTagGroups(Long userId) {
        List<TagGroup> groups = tagGroupRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        List<BrowsingSession> sessions = sessionRepository.findListByUserId(userId);

        return groups.stream()
                .map(group -> TagGroupResponse.from(group, matchingSessions(group, sessions)))
                .toList();
    }

    @Transactional
    public TagGroupResponse createTagGroup(Long userId, TagGroupCreateRequest request) {
        List<String> hashtags = HashtagValidator.normalizeAndValidate(emptyIfNull(request.hashtags()));
        List<TagGroup> existing = tagGroupRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        int position = request.position() == null ? existing.size() : request.position();
        if (position < 0 || position > existing.size()) {
            throw new CustomException(ErrorCode.INVALID_TAG_GROUP_POSITION);
        }
        // 요청 위치 이후의 그룹을 한 칸씩 밀어 position 중복을 막는다
        for (int i = position; i < existing.size(); i++) {
            existing.get(i).updatePosition(i + 1);
        }
        TagGroup group = tagGroupRepository.save(new TagGroup(userId, request.name(), hashtags, position));
        List<BrowsingSession> sessions = sessionRepository.findListByUserId(userId);
        return TagGroupResponse.from(group, matchingSessions(group, sessions));
    }

    @Transactional
    public TagGroupResponse updateTagGroup(Long userId, Long groupId, TagGroupUpdateRequest request) {
        TagGroup group = getOwnedGroup(userId, groupId);
        List<String> hashtags = HashtagValidator.normalizeAndValidate(request.hashtags());
        group.update(request.name(), hashtags);

        List<BrowsingSession> sessions = sessionRepository.findListByUserId(userId);
        return TagGroupResponse.from(group, matchingSessions(group, sessions));
    }

    @Transactional
    public void deleteTagGroup(Long userId, Long groupId) {
        TagGroup group = getOwnedGroup(userId, groupId);
        tagGroupRepository.delete(group);
        reorder(userId);
    }

    @Transactional
    public List<TagGroupResponse> updateOrder(Long userId, TagGroupOrderRequest request) {
        List<TagGroup> groups = tagGroupRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        Map<Long, TagGroup> groupById = groups.stream()
                .collect(Collectors.toMap(TagGroup::getId, Function.identity()));

        if (request.groupIds().size() != groups.size()
                || !new HashSet<>(request.groupIds()).equals(groupById.keySet())) {
            throw new CustomException(ErrorCode.INVALID_ORDER_REQUEST);
        }

        for (int i = 0; i < request.groupIds().size(); i++) {
            groupById.get(request.groupIds().get(i)).updatePosition(i);
        }
        return getTagGroups(userId);
    }

    private TagGroup getOwnedGroup(Long userId, Long groupId) {
        return tagGroupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.TAG_GROUP_NOT_FOUND));
    }

    private List<SessionListResponse> matchingSessions(TagGroup group, List<BrowsingSession> sessions) {
        return sessions.stream()
                .filter(session -> session.hasAnyTag(group.getHashtags()))
                .map(SessionListResponse::from)
                .toList();
    }

    private void reorder(Long userId) {
        List<TagGroup> groups = tagGroupRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        for (int i = 0; i < groups.size(); i++) {
            groups.get(i).updatePosition(i);
        }
    }

    private List<String> emptyIfNull(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
