package com.justinneed.session.analysis;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.analysis.category.SiteCategory;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 세션 원본(stepC json)에서 링크(URL) 기준 방문 정보를 꺼낸다. (유닛 단위 아님)
 *
 * <pre>
 * 링크      = pages[].meta.url (없으면 meta.pageId)
 * 방문 시각 = meta.enteredAt, 같은 링크가 여러 page 로 나뉘어 있으면 가장 이른 시각
 * 카테고리  = SiteCategory.classify(링크)
 * </pre>
 *
 * AI pages 는 체류 3분 이하 방문을 빼고 주므로, 출처 방문 시각 · 방문 링크 수(pageCount)는 원본에서 구한다.
 */
public final class RawLinkVisits {

    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);

    private final Map<String, LinkVisit> visits;

    private RawLinkVisits(Map<String, LinkVisit> visits) {
        this.visits = visits;
    }

    @SuppressWarnings("unchecked")
    public static RawLinkVisits from(Map<String, Object> payload) {
        Map<String, LinkVisit> visits = new LinkedHashMap<>();
        Object pages = payload == null ? null : payload.get("pages");
        if (!(pages instanceof List<?> pageList)) {
            return new RawLinkVisits(visits);
        }

        for (Object page : pageList) {
            if (!(page instanceof Map<?, ?> pageMap) || !(pageMap.get("meta") instanceof Map<?, ?> meta)) {
                continue;
            }
            String url = firstText((Map<String, Object>) meta, "url", "pageId");
            if (url == null) {
                continue;
            }
            LocalDateTime enteredAt = toKst(meta.get("enteredAt"));
            visits.merge(url, new LinkVisit(url, enteredAt, SiteCategory.classify(url)), RawLinkVisits::earlier);
        }
        return new RawLinkVisits(visits);
    }

    // 세션 종료 시각(KST). session.endedAt, 없으면(비정상 종료) 페이지별 meta.endedAt 중 가장 늦은 값
    @SuppressWarnings("unchecked")
    public static Optional<LocalDateTime> sessionEndedAt(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }
        if (payload.get("session") instanceof Map<?, ?> session) {
            LocalDateTime endedAt = toKst(((Map<String, Object>) session).get("endedAt"));
            if (endedAt != null) {
                return Optional.of(endedAt);
            }
        }
        if (!(payload.get("pages") instanceof List<?> pages)) {
            return Optional.empty();
        }
        return pages.stream()
                .filter(page -> page instanceof Map<?, ?> map && map.get("meta") instanceof Map<?, ?>)
                .map(page -> toKst(((Map<?, ?>) ((Map<?, ?>) page).get("meta")).get("endedAt")))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
    }

    public int linkCount() {
        return visits.size();
    }

    public Optional<LinkVisit> find(String url) {
        return Optional.ofNullable(visits.get(url));
    }

    // stepC · AI 시각은 UTC ISO 문자열(…Z / …+00:00). 세션 시각과 같이 KST LocalDateTime 으로 바꾼다. 읽을 수 없으면 null
    public static LocalDateTime toKst(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(KST).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static LinkVisit earlier(LinkVisit a, LinkVisit b) {
        if (a.firstVisitedAt() == null) {
            return b;
        }
        if (b.firstVisitedAt() == null) {
            return a;
        }
        return b.firstVisitedAt().isBefore(a.firstVisitedAt()) ? b : a;
    }

    public record LinkVisit(
            String url,
            LocalDateTime firstVisitedAt,
            SiteCategory category
    ) {
    }
}
