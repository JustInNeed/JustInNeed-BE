package com.justinneed.session.management.service;

import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.SessionMindmapBuilder;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import com.justinneed.session.management.dto.SessionPageResponse;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class SessionService {

    // 무한 스크롤 한 번에 받을 수 있는 최대 개수
    private static final int MAX_PAGE_SIZE = 50;

    private final BrowsingSessionRepository sessionRepository;
    private final SessionResultRepository resultRepository;
    private final CommunitySharing sharing;
    private final CommunityProfiles profiles;
    private final CommunityCardSync cards;
    private final ObjectMapper objectMapper;

    public SessionService(BrowsingSessionRepository sessionRepository, SessionResultRepository resultRepository,
            CommunitySharing sharing, CommunityProfiles profiles, CommunityCardSync cards, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.resultRepository = resultRepository;
        this.sharing = sharing;
        this.profiles = profiles;
        this.cards = cards;
        this.objectMapper = objectMapper;
    }

    public SessionPageResponse getSessions(Long userId, int page, int size, boolean favoriteOnly) {
        Pageable pageable = pageRequest(page, size);
        Slice<BrowsingSession> slice = sessionRepository.findListPage(userId, favoriteOnly, pageable);
        List<SessionListResponse> sessions = slice.stream()
                .map(SessionListResponse::from)
                .toList();

        // 화면 상단 "전체 N개 세션 · M개 즐겨찾기" 는 필터 · 페이지와 상관없이 센다
        long totalCount = sessionRepository.countByUserIdAndDeletedAtIsNull(userId);
        long favoriteCount = sessionRepository.countByUserIdAndFavoriteTrueAndDeletedAtIsNull(userId);
        return new SessionPageResponse(sessions, slice.hasNext(), totalCount, favoriteCount);
    }

    // 그룹 만들기의 해시태그 후보: 내 세션 전체의 태그를 목록 순서로 훑어 처음 나온 순서대로, 대소문자 무시 중복 제거
    public List<String> getHashtags(Long userId) {
        Map<String, String> hashtagByKey = new LinkedHashMap<>();
        for (List<String> tags : sessionRepository.findTagsByUserId(userId)) {
            for (String tag : tags) {
                // 처음 나온 표기를 남긴다
                hashtagByKey.putIfAbsent(tag.toLowerCase(), tag);
            }
        }
        return List.copyOf(hashtagByKey.values());
    }

    // 없거나 삭제 404, 소유자는 전체, 다른 사용자는 공개 세션만 읽기 전용, 그 밖에는 403
    public SessionDetailResponse getSession(Long userId, Long sessionId) {
        BrowsingSession session = findSession(sessionId);
        if (session.getUserId().equals(userId)) {
            return SessionDetailResponse.from(session);
        }
        CommunityProfile owner = publicOwner(session);
        List<String> tags = session.getTags().stream().filter(tag -> !sharing.isHidden(owner, tag)).toList();
        return SessionDetailResponse.forViewer(session, tags, owner.getVisibility() == NodeVisibility.CONTENT);
    }

    // 접근 규칙은 세션 상세와 같다. 키워드 · 인용은 내용이라 URL 만 공개한 작성자의 마인드맵은 다른 사용자에게 비워서 준다
    public SessionMindmapResponse getMindmap(Long userId, Long sessionId) {
        BrowsingSession session = findSession(sessionId);
        boolean mine = session.getUserId().equals(userId);
        if (!mine && publicOwner(session).getVisibility() != NodeVisibility.CONTENT) {
            return SessionMindmapResponse.EMPTY;
        }
        if (session.getStatus() != SessionStatus.COMPLETED) {
            return SessionMindmapResponse.EMPTY;
        }
        return resultRepository.findMindmapSource(sessionId)
                .map(source -> SessionMindmapBuilder.build(
                        parseJson(source.mindmap()), parseJson(source.selectedUnits()), session.getSources()))
                .map(mindmap -> mine ? mindmap : mindmap.withWebCitationsOnly())
                .orElse(SessionMindmapResponse.EMPTY);
    }

    private Object parseJson(String json) {
        return json == null ? null : objectMapper.readValue(json, Object.class);
    }

    @Transactional
    public SessionDetailResponse updateSession(Long userId, Long sessionId, SessionUpdateRequest request) {
        // 비공개로 돌리면 고정 목록도 고치므로 공개 여부를 바꿀 때는 고정(SharedSessionService.pin)과 같이 회원 → 세션 순서로 잠근다
        CommunityProfile profile = request.isPublic() == null ? null : profiles.lock(userId);
        BrowsingSession session = sessionRepository.lockOwned(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (request.changesContent() && session.getStatus() != SessionStatus.COMPLETED) {
            throw new CustomException(ErrorCode.SESSION_NOT_COMPLETED);
        }

        validateTitle(request.title());
        List<String> tags = HashtagValidator.normalizeAndValidate(request.tags());
        session.update(request.title(), request.editedMarkdown(), request.isPublic(), request.isFavorite(), tags);
        // 고정은 공개 세션 안에서의 자리라, 다시 공개해도 되살아나지 않게 지운다
        if (Boolean.FALSE.equals(request.isPublic())) {
            profile.removePinnedSession(sessionId);
        }
        if (request.changesContent()) {
            cards.sync(session);
        }
        return SessionDetailResponse.from(session);
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        BrowsingSession session = sessionRepository.lockOwned(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        session.delete();
        cards.sync(session);
    }

    private BrowsingSession findSession(Long sessionId) {
        return sessionRepository.findWithSummaryByIdAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
    }

    // 다른 사용자가 볼 수 있는 세션이면 작성자 프로필, 아니면 403
    private CommunityProfile publicOwner(BrowsingSession session) {
        if (!sharing.publicSessionIds(List.of(session.getId())).contains(session.getId())) {
            throw new CustomException(ErrorCode.SESSION_FORBIDDEN);
        }
        return profiles.read(session.getUserId());
    }

    private Pageable pageRequest(int page, int size) {
        // 건너뛸 개수(page * size)가 int 를 넘으면 JPA 가 처리하지 못해 500 이 나므로 같이 막는다
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST);
        }
        return PageRequest.of(page, size);
    }

    private void validateTitle(String title) {
        if (title != null && title.isBlank()) {
            throw new CustomException(ErrorCode.TITLE_REQUIRED);
        }
    }
}
