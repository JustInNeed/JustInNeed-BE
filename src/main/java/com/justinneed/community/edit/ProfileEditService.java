package com.justinneed.community.edit;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.hashtag.HashtagVisibilityService;
import com.justinneed.community.interest.InterestService;
import com.justinneed.community.interestdelete.InterestDeleteService;
import com.justinneed.community.pin.SharedSessionService;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.unpin.UnpinService;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.auth.repository.MemberRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProfileEditService {
    private final CommunityTags tags;
    private final CommunityProfiles profiles;
    private final HashtagVisibilityService visibility;
    private final InterestService interests;
    private final InterestDeleteService interestDelete;
    private final SharedSessionService pins;
    private final UnpinService unpin;
    private final BrowsingSessionRepository sessions;
    private final MemberRepository members;

    public ProfileEditService(CommunityTags tags, CommunityProfiles profiles, HashtagVisibilityService visibility,
            InterestService interests, InterestDeleteService interestDelete, SharedSessionService pins,
            UnpinService unpin, BrowsingSessionRepository sessions, MemberRepository members) {
        this.tags = tags; this.profiles = profiles; this.visibility = visibility;
        this.interests = interests; this.interestDelete = interestDelete; this.pins = pins;
        this.unpin = unpin; this.sessions = sessions; this.members = members;
    }

    public List<TagView> hashtags(Long userId) {
        var profile = profiles.lock(userId);
        return tags.owned(userId).stream().map(t -> new TagView(200, t.getId(), t.getName(),
                profile.getHiddenHashtags().stream().noneMatch(h -> h.equalsIgnoreCase(t.getName())))).toList();
    }

    public TagView visibility(Long userId, Long id, boolean isPublic) {
        var tag = tags.owned(userId).stream().filter(t -> t.getId().equals(id))
                .findFirst().orElseThrow(CommunityErrors::missing);
        visibility.update(userId, tag.getName(), isPublic);
        return new TagView(200, id, tag.getName(), isPublic);
    }

    public InterestView addInterest(Long userId, String hashtag) {
        var values = interests.add(userId, hashtag);
        var tag = tags.owned(userId).stream().filter(t -> t.getName().equalsIgnoreCase(hashtag))
                .findFirst().orElseThrow();
        return new InterestView(201, tag.getId(), tag.getName(), values.size());
    }

    public List<InterestView> myInterests(Long userId) {
        var values = interests.get(userId, userId);
        return tags.owned(userId).stream().filter(t -> values.stream().anyMatch(v -> v.equalsIgnoreCase(t.getName())))
                .map(t -> new InterestView(200, t.getId(), t.getName(), values.size())).toList();
    }

    public DeletedInterest deleteInterest(Long userId, Long id) {
        var tag = tags.owned(userId).stream().filter(t -> t.getId().equals(id))
                .findFirst().orElseThrow(CommunityErrors::missing);
        interestDelete.delete(userId, tag.getName());
        return new DeletedInterest(200, id);
    }

    public PinView pin(Long userId, Long id) {
        pins.pin(userId, id);
        return new PinView(201, id, true, true, profiles.read(userId).getPinnedSessionIds().indexOf(id));
    }
    public PinView unpin(Long userId, Long id) {
        unpin.unpin(userId, id);
        return new PinView(200, id, false, false, null);
    }
    public OrderView reorder(Long userId, List<Long> ids) {
        pins.reorder(userId, ids);
        return new OrderView(200, ids);
    }
    public SessionView session(Long viewerId, Long id) {
        var session = sessions.findById(id).orElseThrow(CommunityErrors::missing);
        var view = pins.detail(session.getUserId(), viewerId, id);
        var member = members.findById(session.getUserId()).orElseThrow(CommunityErrors::missing);
        return new SessionView(200, id, view.title(), member.getId(), member.getNickname(),
                view.tags(), view.urls(), view.markdown(), profiles.read(member.getId()).getVisibility());
    }

    public record TagView(int code, Long hashtagId, String hashtag, boolean isPublic) { }
    public record InterestView(int code, Long interestId, String hashtag, int interestCount) { }
    public record DeletedInterest(int code, Long interestId) { }
    public record PinView(int code, Long sessionId, boolean isPublic, boolean isPinned, Integer order) { }
    public record OrderView(int code, List<Long> sessionIds) { }
    public record SessionView(int code, Long sessionId, String title, Long ownerId, String nickname,
            List<String> hashtags, List<String> urls, String summary, NodeVisibility visibilityScope) { }
}
