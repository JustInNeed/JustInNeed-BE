package com.justinneed.session.management.service;

import com.justinneed.global.common.HashtagValidator;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SessionService {

    private final BrowsingSessionRepository sessionRepository;

    public SessionService(BrowsingSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public List<SessionListResponse> getSessions(Long userId) {
        return sessionRepository.findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(userId).stream()
                .map(SessionListResponse::from)
                .toList();
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

    private void validateTitle(String title) {
        if (title != null && title.isBlank()) {
            throw new CustomException(ErrorCode.TITLE_REQUIRED);
        }
    }
}
