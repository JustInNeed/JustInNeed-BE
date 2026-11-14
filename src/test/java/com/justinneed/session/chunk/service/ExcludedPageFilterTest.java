package com.justinneed.session.chunk.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("수집 제외 URL 페이지 거르기 (호스트 정확히 일치 · 경로 / 단위 앞부분 일치)")
class ExcludedPageFilterTest {

    @ParameterizedTest(name = "{0} ~ {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            // 호스트는 정확히 같아야 한다
            "https://naver.com                     | naver.com          | true",
            "https://www.naver.com/                | naver.com          | true",
            "https://mail.naver.com/inbox          | naver.com          | false",
            "https://NAVER.com/x                   | naver.com          | true",
            "https://mail.google.com/mail/u/0      | mail.google.com    | true",
            "https://google.com                    | mail.google.com    | false",
            // 경로는 / 단위 앞부분
            "https://naver.com/4                   | naver.com/4        | true",
            "https://naver.com/4/                  | naver.com/4        | true",
            "https://naver.com/4/abc               | naver.com/4        | true",
            "https://naver.com/40                  | naver.com/4        | false",
            "https://naver.com                     | naver.com/4        | false",
            "https://www.youtube.com/shorts/abc123 | youtube.com/shorts | true",
            "https://www.youtube.com/watch?v=1     | youtube.com/shorts | false",
            // 쿼리 · # 은 보지 않는다
            "https://naver.com/4?utm=a#top         | naver.com/4        | true",
            "https://naver.com/search?q=b          | naver.com/search?q=a | true",
            // 포트
            "https://naver.com:443/4               | naver.com/4        | true",
            "http://naver.com:8080/4               | naver.com/4        | false",
            "http://naver.com:8080/4               | naver.com:8080     | true",
            // % 인코딩
            "https://namu.wiki/w/%ED%95%9C%EA%B8%80 | namu.wiki/w/한글   | true",
            "https://namu.wiki/w/한글               | namu.wiki/w/%ED%95%9C%EA%B8%80 | true",
            // http · https 아닌 페이지
            "chrome://settings                     | settings           | false",
    })
    void matches(String pageUrl, String excludedUrl, boolean expected) {
        assertThat(ExcludedPageFilter.matches(pageUrl, excludedUrl)).isEqualTo(expected);
    }

    @Test
    @DisplayName("걸린 페이지와 그 pageId 의 visits · flushReport 를 빼고 focusMs 를 줄인다")
    @SuppressWarnings("unchecked")
    void removesExcludedPagesFromBundle() {
        var bundle = bundle(
                List.of(page(1, "https://naver.com/4/x", 300), page(2, "https://mail.naver.com/", 450),
                        page("p3", "https://news.example.com", 150)),
                List.of(dropped(4, "https://naver.com/4"), dropped(5, "https://example.com")));
        var session = (Map<String, Object>) bundle.get("session");
        // 세그먼트 하나만 제외 URL 이어도 페이지 전체를 뺀다 (p3 의 두 번째 세그먼트)
        var p3 = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) bundle.get("pages")).get(2)).get("meta");
        ((List<Object>) p3.get("segments")).add(new LinkedHashMap<>(Map.of("segId", "s-p3b", "url", "https://naver.com/4")));

        int removed = ExcludedPageFilter.apply(bundle, List.of("naver.com/4"));

        assertThat(removed).isEqualTo(3);   // 1 · p3 · dropped 4
        assertThat(pageIds(bundle.get("pages"))).containsExactly(2.0);
        assertThat(session.get("focusMs")).isEqualTo(450L);
        assertThat(pageIds(session.get("droppedPages"))).containsExactly(5.0);
        assertThat(pageIds(session.get("visits"))).containsExactly(2.0, 5.0);
        assertThat(pageIds(session.get("flushReport"))).containsExactly(2.0, 5.0);
    }

    @Test
    @DisplayName("제외 URL 이 없거나 걸린 페이지가 없으면 bundle 을 건드리지 않는다")
    @SuppressWarnings("unchecked")
    void keepsBundleWhenNothingMatches() {
        var bundle = bundle(List.of(page(1, "https://mail.naver.com", 300)), List.of());
        var session = (Map<String, Object>) bundle.get("session");

        assertThat(ExcludedPageFilter.apply(bundle, List.of())).isZero();
        assertThat(ExcludedPageFilter.apply(bundle, List.of("naver.com"))).isZero();
        assertThat(pageIds(bundle.get("pages"))).containsExactly(1.0);
        assertThat(session.get("focusMs")).isEqualTo(300L);
        assertThat(pageIds(session.get("visits"))).containsExactly(1.0);
    }

    // ---- ChunkAssembler 결과 꼴의 bundle ----

    private static Map<String, Object> bundle(List<Map<String, Object>> pages, List<Map<String, Object>> dropped) {
        long total = 0;
        var visits = new ArrayList<Object>();
        var flushReport = new ArrayList<Object>();
        for (var p : pages) {
            var meta = (Map<?, ?>) p.get("meta");
            total += ((Number) meta.get("focusMs")).longValue();
            visits.add(new LinkedHashMap<>(Map.of("pageId", meta.get("pageId"), "t", 1)));
            flushReport.add(new LinkedHashMap<>(Map.of("pageId", meta.get("pageId"), "segId", "f")));
        }
        for (var d : dropped) {
            visits.add(new LinkedHashMap<>(Map.of("pageId", d.get("pageId"), "t", 1)));
            flushReport.add(new LinkedHashMap<>(Map.of("pageId", d.get("pageId"), "segId", "f")));
        }
        var session = new LinkedHashMap<String, Object>();
        session.put("focusMs", total);
        session.put("visits", visits);
        session.put("flushReport", flushReport);
        session.put("droppedPages", new ArrayList<Object>(dropped));
        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("session", session);
        bundle.put("pages", new ArrayList<Object>(pages));
        return bundle;
    }

    private static Map<String, Object> page(Object pageId, String url, long focusMs) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("url", url);
        meta.put("pageId", pageId);
        meta.put("focusMs", focusMs);
        meta.put("segments", new ArrayList<Object>(List.of(new LinkedHashMap<>(Map.of("segId", "s" + pageId, "url", url)))));
        var page = new LinkedHashMap<String, Object>();
        page.put("meta", meta);
        return page;
    }

    private static Map<String, Object> dropped(Object pageId, String url) {
        var d = new LinkedHashMap<String, Object>();
        d.put("pageId", pageId);
        d.put("reason", "no-ticks");
        d.put("segments", new ArrayList<Object>(List.of(new LinkedHashMap<>(Map.of("segId", "d" + pageId, "url", url)))));
        return d;
    }

    // pageId 를 숫자면 double 로 (비교를 단순하게), 문자열이면 그대로
    private static List<Object> pageIds(Object list) {
        var ids = new ArrayList<Object>();
        for (Object o : (List<?>) list) {
            var m = (Map<?, ?>) o;
            Object id = m.containsKey("meta") ? ((Map<?, ?>) m.get("meta")).get("pageId") : m.get("pageId");
            ids.add(id instanceof Number n ? n.doubleValue() : id);
        }
        return ids;
    }
}
