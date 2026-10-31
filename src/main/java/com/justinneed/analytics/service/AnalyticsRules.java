package com.justinneed.analytics.service;

/**
 * 분석 화면 판정 기준값. 엑셀에 수치가 없어 초안으로 정한 값이고(트래킹표 분석 결정 11), 실제 데이터가 쌓이면 여기서 조정한다.
 */
final class AnalyticsRules {

    /** 탐색 성향: 세션 방문 URL 이 이 개수 이상이면 넓게 돌아다닌 세션 */
    static final int MANY_URLS = 10;
    /** 탐색 성향: 페이지 평균 체류가 이 초 미만이면 빠르게 오간 세션 */
    static final int SHORT_DWELL_SEC = 30;
    /** 탐색 성향: Deep + Reading 체류 비율이 이 % 이상이면 집중 */
    static final int FOCUSED_PCT = 60;

    /** 카테고리 문구: 1위 카테고리가 이 % 이상이면 "중심" 문구 */
    static final int LEADING_CATEGORY_PCT = 60;

    /** 추천 행동: 블로그 + 커뮤니티가 이 % 이상 */
    static final int BLOG_AND_COMMUNITY_PCT = 60;
    /** 추천 행동: 커뮤니티가 이 % 이상 */
    static final int COMMUNITY_PCT = 50;
    /** 추천 행동: 한 사이트가 이 % 이상 */
    static final int ONE_SITE_PCT = 60;

    /** 시간대 문구: 체류가 가장 많은 3시간 구간이 전체의 이 % 이상이면 "주로" 문구 */
    static final int PEAK_SLOT_PCT = 30;
    /** 정독 시간대: 체류가 이 ms 이상인 구간만 본다 */
    static final long MIN_FOCUS_SLOT_DWELL_MS = 60_000;

    private AnalyticsRules() {
    }
}
