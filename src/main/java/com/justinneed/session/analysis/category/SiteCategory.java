package com.justinneed.session.analysis.category;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 방문 페이지 URL 의 사이트 카테고리. (ANA-1 카테고리별 방문 비율 / Deep Reading)
 *
 * <p>LLM 없이 도메인 규칙으로만 분류한다. 도메인 목록은 {@link SiteCategoryDomains} 참고.
 *
 * <p>규칙 우선순위 (먼저 일치하는 규칙이 이긴다)
 * <ol>
 *     <li>도메인 규칙: host 가 도메인과 같거나 그 서브도메인이면 일치. 여러 도메인이 일치하면 가장 긴(구체적인) 도메인이 이긴다.
 *         예) {@code n.news.naver.com} → {@code news.naver.com}(뉴스), {@code mlbpark.donga.com}(커뮤니티) &gt; {@code donga.com}(뉴스)</li>
 *     <li>접두사 규칙: host 맨 앞 라벨. 예) {@code docs.}, {@code developer.} → 공식문서, {@code blog.} → 블로그</li>
 *     <li>접미사 규칙: 예) {@code .go.kr}, {@code .ac.kr}, {@code .edu}, {@code .gov} → 공식문서</li>
 *     <li>어디에도 해당하지 않으면 기타. null / 파싱 불가 URL 도 기타.</li>
 * </ol>
 *
 * <p>host 는 소문자로 바꾸고 {@code www.} 와 포트를 제거한 뒤 비교한다.
 * {@code naver.com}, {@code daum.net} 자체는 어떤 목록에도 없으므로 블로그/뉴스/카페 등 서브도메인 외의 페이지(검색, 메인 등)는 기타가 된다.
 *
 * <p>TODO: 추후 기타 비율이 너무 높아지면, 알 수 없는 도메인마다 LLM 에 한 번 물어보고 결과를 도메인 단위로 캐싱하는 방식을 검토한다. (현재 미구현)
 */
public enum SiteCategory {
    BLOG("블로그", SiteCategoryDomains.BLOG_DOMAINS, SiteCategoryDomains.BLOG_PREFIXES, List.of()),
    OFFICIAL_DOCS("공식문서", SiteCategoryDomains.OFFICIAL_DOCS_DOMAINS, SiteCategoryDomains.OFFICIAL_DOCS_PREFIXES,
            SiteCategoryDomains.OFFICIAL_DOCS_SUFFIXES),
    COMMUNITY("커뮤니티", SiteCategoryDomains.COMMUNITY_DOMAINS, SiteCategoryDomains.COMMUNITY_PREFIXES, List.of()),
    NEWS("뉴스", SiteCategoryDomains.NEWS_DOMAINS, SiteCategoryDomains.NEWS_PREFIXES, List.of()),
    ETC("기타", SiteCategoryDomains.ETC_DOMAINS, List.of(), List.of());

    private static final String WWW_PREFIX = "www.";
    private static final Map<String, SiteCategory> DOMAIN_INDEX = buildDomainIndex();

    private final String displayName;
    private final List<String> domains;
    private final List<String> hostPrefixes;
    private final List<String> hostSuffixes;

    SiteCategory(String displayName, List<String> domains, List<String> hostPrefixes, List<String> hostSuffixes) {
        this.displayName = displayName;
        this.domains = domains;
        this.hostPrefixes = hostPrefixes;
        this.hostSuffixes = hostSuffixes;
    }

    public static SiteCategory classify(String url) {
        return extractHost(url)
                .map(SiteCategory::classifyHost)
                .orElse(ETC);
    }

    /**
     * URL 에서 비교용 host 를 꺼낸다. (소문자, {@code www.} / 포트 / 끝의 점 제거) 파싱할 수 없으면 empty.
     */
    public static Optional<String> extractHost(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String value = url.trim();
        if (!value.contains("://")) {
            value = "http://" + value;
        }

        String host;
        try {
            URI uri = new URI(value);
            host = uri.getHost() != null ? uri.getHost() : hostFromAuthority(uri.getRawAuthority());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith(WWW_PREFIX)) {
            host = host.substring(WWW_PREFIX.length());
        }
        return host.isEmpty() ? Optional.empty() : Optional.of(host);
    }

    /**
     * 밑줄 등이 섞여 {@link URI#getHost()} 가 null 인 경우 authority 에서 직접 host 를 꺼낸다.
     */
    private static String hostFromAuthority(String authority) {
        if (authority == null) {
            return null;
        }
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        int portIndex = host.lastIndexOf(':');
        return portIndex >= 0 ? host.substring(0, portIndex) : host;
    }

    private static SiteCategory classifyHost(String host) {
        // 1. 도메인 규칙: host 자신부터 상위 도메인으로 올라가며 찾으므로 가장 긴 도메인이 먼저 일치한다.
        for (String candidate = host; candidate != null; candidate = parentDomain(candidate)) {
            SiteCategory category = DOMAIN_INDEX.get(candidate);
            if (category != null) {
                return category;
            }
        }
        // 2. 접두사 규칙
        for (SiteCategory category : values()) {
            if (category.hostPrefixes.stream().anyMatch(host::startsWith)) {
                return category;
            }
        }
        // 3. 접미사 규칙
        for (SiteCategory category : values()) {
            if (category.hostSuffixes.stream().anyMatch(suffix -> host.endsWith("." + suffix))) {
                return category;
            }
        }
        return ETC;
    }

    private static String parentDomain(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? null : host.substring(dot + 1);
    }

    private static Map<String, SiteCategory> buildDomainIndex() {
        Map<String, SiteCategory> index = new HashMap<>();
        for (SiteCategory category : values()) {
            for (String domain : category.domains) {
                SiteCategory previous = index.putIfAbsent(domain, category);
                if (previous != null) {
                    throw new IllegalStateException(
                            "중복된 사이트 카테고리 도메인: " + domain + " (" + previous + ", " + category + ")");
                }
            }
        }
        return Map.copyOf(index);
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getDomains() {
        return domains;
    }

    public List<String> getHostPrefixes() {
        return hostPrefixes;
    }

    public List<String> getHostSuffixes() {
        return hostSuffixes;
    }
}
