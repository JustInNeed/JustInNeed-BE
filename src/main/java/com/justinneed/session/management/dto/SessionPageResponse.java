package com.justinneed.session.management.dto;

import java.util.List;

/**
 * 세션 목록 한 페이지 (무한 스크롤, REC-10-1).
 * totalCount · favoriteCount 는 즐겨찾기 필터 · 페이지와 상관없이 내 전체 세션 수 / 즐겨찾기 세션 수다.
 */
public record SessionPageResponse(
        List<SessionListResponse> sessions,
        boolean hasNext,
        long totalCount,
        long favoriteCount
) {
}
