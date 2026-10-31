package com.justinneed.analytics.dto;

import com.justinneed.analytics.ExplorationType;
import com.justinneed.session.management.dto.SessionListResponse;
import java.util.List;
import java.util.Map;

/** 분석 화면 카드별 응답. 문구의 "**강조**" 는 FE 가 강조 글씨로 보여준다 */
public final class AnalyticsResponse {

    private AnalyticsResponse() {
    }

    /** 카드 아래 분석 · 추천 문구. 추천이 없으면 null */
    public record Note(String analysis, String recommendation) {
    }

    /** 이번 주 인사이트 후보 — 고르는 건 FE */
    public record WeekSessions(List<SessionListResponse> sessions) {
    }

    /** 판정할 수 있는 세션이 없으면 type 은 null 이고 avgDwellSec 은 0 */
    public record Exploration(
            int avgUrlsPerSession,
            int avgDwellSec,
            int avgSessionMinutes,
            ExplorationType type,
            Note note
    ) {
    }

    /** shares: DEEP_READING · READING · SCANNING · IDLE → % (합 100) */
    public record ReadingBehavior(Map<String, Integer> shares, Note note) {
    }

    /** 방문 횟수가 많은 순. pct 합은 100 */
    public record Categories(List<Category> categories, Note note) {
    }

    public record Category(String name, int pct, long count) {
    }

    /** surfing · focus: [요일 월~일][3시간 구간 00 · 03 · … · 21시], 값 0~1 */
    public record TimePatterns(double[][] surfing, double[][] focus, Note note) {
    }

    public record Recommendations(List<Recommendation> recommendations) {
    }

    public record Recommendation(String title, String body) {
    }
}
