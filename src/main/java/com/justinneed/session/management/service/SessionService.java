package com.justinneed.session.management.service;

import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.dto.SessionListResponse;
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

@Service
@Transactional(readOnly = true)
public class SessionService {

    // 무한 스크롤 한 번에 받을 수 있는 최대 개수
    private static final int MAX_PAGE_SIZE = 50;

    private final BrowsingSessionRepository sessionRepository;

    public SessionService(BrowsingSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
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

    public SessionDetailResponse getSession(Long userId, Long sessionId) {
        BrowsingSession session = sessionRepository.findWithSummaryByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        return SessionDetailResponse.from(session);
    }

    @Transactional
    public SessionDetailResponse updateSession(Long userId, Long sessionId, SessionUpdateRequest request) {
        BrowsingSession session = sessionRepository.findWithSummaryByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));

        validateTitle(request.title());
        List<String> tags = HashtagValidator.normalizeAndValidate(request.tags());
        session.update(request.title(), request.editedMarkdown(), request.isPublic(), request.isFavorite(), tags);
        return SessionDetailResponse.from(session);
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        BrowsingSession session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        session.delete();
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
