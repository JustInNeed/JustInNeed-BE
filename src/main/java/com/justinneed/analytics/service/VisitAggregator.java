package com.justinneed.analytics.service;

import com.justinneed.analytics.ReadingDistribution;
import com.justinneed.analytics.dto.AnalyticsResponse.Categories;
import com.justinneed.analytics.dto.AnalyticsResponse.Category;
import com.justinneed.analytics.dto.AnalyticsResponse.Note;
import com.justinneed.analytics.dto.AnalyticsResponse.Recommendation;
import com.justinneed.analytics.repository.AnalyticsSessionRow;
import com.justinneed.session.analysis.category.SiteCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 방문 집계 (ANA-1-1 · 1-2 · 1-4). 재료는 session_results.page_visits (짧은 방문 포함)이고,
 * 방문 횟수는 재방문을 포함한다. pageVisits 가 없는 세션은 빠진다.
 */
final class VisitAggregator {

    private static final int MAX_BALANCED_CATEGORIES = 3;
    private static final String CATEGORY_LEADS = "현재 탐색은 **%s** 중심으로 이루어지고 있습니다. (전체 탐색의 %d%%)";
    private static final String CATEGORIES_BALANCED = "다양한 유형의 출처를 균형 있게 활용하고 있습니다. (%s)";

    private final Map<SiteCategory, Long> categoryVisits = new EnumMap<>(SiteCategory.class);
    private final Map<String, Long> siteVisits = new HashMap<>();
    private long totalVisits;

    private VisitAggregator() {
        for (SiteCategory category : SiteCategory.values()) {
            categoryVisits.put(category, 0L);
        }
    }

    static VisitAggregator of(List<AnalyticsSessionRow> sessions) {
        VisitAggregator aggregator = new VisitAggregator();
        for (AnalyticsSessionRow session : sessions) {
            if (session.pageVisits() != null) {
                session.pageVisits().forEach(aggregator::add);
            }
        }
        return aggregator;
    }

    private void add(Map<String, Object> page) {
        String url = page.get("url") instanceof String text ? text : null;
        long visits = AnalyticsJson.nonNegative(page.get("visitCount"));
        categoryVisits.merge(SiteCategory.classify(url), visits, Long::sum);
        SiteCategory.extractHost(url).ifPresent(site -> siteVisits.merge(site, visits, Long::sum));
        totalVisits += visits;
    }

    Categories categories() {
        if (totalVisits == 0) {
            return null;
        }
        List<SiteCategory> order = List.of(SiteCategory.values());
        int[] percents = ReadingDistribution.percentagesOf(order.stream().mapToLong(categoryVisits::get).toArray());
        List<Category> categories = new ArrayList<>();
        for (int i = 0; i < percents.length; i++) {
            categories.add(new Category(order.get(i).getDisplayName(), percents[i], categoryVisits.get(order.get(i))));
        }
        categories.sort(Comparator.comparingLong(Category::count).reversed());
        return new Categories(categories, categoryNote(categories));
    }

    private Note categoryNote(List<Category> categories) {
        Category first = categories.get(0);
        String analysis = first.pct() >= AnalyticsRules.LEADING_CATEGORY_PCT
                ? CATEGORY_LEADS.formatted(first.name(), first.pct())
                : CATEGORIES_BALANCED.formatted(categories.stream()
                        .filter(category -> category.count() > 0)
                        .limit(MAX_BALANCED_CATEGORIES)
                        .map(category -> "%s %d%%".formatted(category.name(), category.pct()))
                        .collect(Collectors.joining(" / ")));
        String recommendation = recommendations().stream()
                .findFirst()
                .map(item -> item.title() + " " + item.body())
                .orElse(null);
        return new Note(analysis, recommendation);
    }

    /** 뉴스 · 공식문서가 아닌 곳에 집중할 때 나오는 추천 (ANA-1-4). 조건에 맞는 것이 없으면 빈 목록 */
    List<Recommendation> recommendations() {
        if (totalVisits == 0) {
            return List.of();
        }
        List<Recommendation> recommendations = new ArrayList<>();
        long blogAndCommunity = categoryVisits.get(SiteCategory.BLOG) + categoryVisits.get(SiteCategory.COMMUNITY);
        if (pct(blogAndCommunity) >= AnalyticsRules.BLOG_AND_COMMUNITY_PCT) {
            recommendations.add(new Recommendation("공식 문서도 한 번 정독해보세요.",
                    "지금 공부하는 주제의 공식 문서나 원문을 한 번쯤 읽으며 핵심 개념을 정리해보는 건 어떨까요?"));
        }
        double communityPct = pct(categoryVisits.get(SiteCategory.COMMUNITY));
        if (communityPct >= AnalyticsRules.COMMUNITY_PCT) {
            recommendations.add(new Recommendation(
                    "커뮤니티 탐색 비중이 %d%%로 가장 높아요.".formatted(Math.round(communityPct)),
                    "문제 해결에는 효과적이지만 전체 구조를 이해하는 데는 한계가 있을 수 있어요. 공식 문서를 한 번쯤 정독해보는 건 어떨까요?"));
        }
        topSite()
                .filter(site -> pct(siteVisits.get(site)) >= AnalyticsRules.ONE_SITE_PCT)
                .ifPresent(site -> recommendations.add(new Recommendation(
                        site + "에 정보 탐색이 몰려 있어요.",
                        "다양한 출처를 함께 참고하면 같은 주제도 더 넓고 깊게 이해할 수 있어요.")));
        return recommendations;
    }

    // 방문이 가장 많은 사이트. 같으면 이름순
    private Optional<String> topSite() {
        return siteVisits.entrySet().stream()
                .min(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }

    private double pct(long visits) {
        return visits * 100.0 / totalVisits;
    }
}
