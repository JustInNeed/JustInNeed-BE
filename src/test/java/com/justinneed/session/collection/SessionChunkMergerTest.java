package com.justinneed.session.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("청크 병합")
class SessionChunkMergerTest {

    private static final String COLLECTOR_ID = "ee30ed8c-5fb3-4cfc-aa4a-107b573409c6";
    // KST. UTC 로 2026-10-30T16:44:04Z
    private static final LocalDateTime ROW_STARTED_AT = LocalDateTime.of(2026, 10, 31, 1, 44, 4);
    private static final SessionChunkMerger.Context ENDED = new SessionChunkMerger.Context(
            COLLECTOR_ID, ROW_STARTED_AT, LocalDateTime.of(2026, 10, 31, 1, 44, 18, 279_000_000), "user");
    private static final SessionChunkMerger.Context AUTO_ENDED = new SessionChunkMerger.Context(
            COLLECTOR_ID, ROW_STARTED_AT, null, null);
    private static final Map<String, Object> HEADER_SESSION = Map.of(
            "sessionId", COLLECTOR_ID, "startedAt", "2026-10-30T16:44:04.406Z", "tickMs", 150);

    private static Map<String, Object> tick(int t) {
        return Map.of("type", "tick", "t", t, "segId", "seg-1");
    }

    private static Map<String, Object> page(String pageId, List<Map<String, Object>> paragraphs,
            List<Map<String, Object>> timeline) {
        return Map.of("meta", Map.of("pageId", pageId, "paragraphs", paragraphs), "timeline", timeline);
    }

    private static Map<String, Object> chunk(Map<String, Object> session, Map<String, Object>... pages) {
        Map<String, Object> payload = new HashMap<>();
        if (session != null) {
            payload.put("kind", "rbc-session");
            payload.put("bundleVersion", 1);
            payload.put("session", session);
        }
        payload.put("pages", List.of(pages));
        return payload;
    }

    private static Map<String, Object> paragraph(String pid, Object... pieceNoise) {
        List<Map<String, Object>> pieces = new ArrayList<>();
        for (Object noise : pieceNoise) {
            Map<String, Object> piece = new HashMap<>();
            piece.put("off", pieces.size());
            piece.put("chars", 1);
            if (noise != null) {
                piece.put("noise", noise);
            }
            pieces.add(piece);
        }
        Map<String, Object> paragraph = new HashMap<>();
        paragraph.put("pid", pid);
        paragraph.put("order", 0);
        paragraph.put("text", "본문");
        paragraph.put("charLen", 2);
        paragraph.put("pieces", pieces);
        return paragraph;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> session(Map<String, Object> merged) {
        return (Map<String, Object>) merged.get("session");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pages(Map<String, Object> merged) {
        return (List<Map<String, Object>>) merged.get("pages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> page) {
        return (Map<String, Object>) page.get("meta");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> timeline(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("timeline");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> paragraphs(Map<String, Object> page) {
        return (List<Map<String, Object>>) meta(page).get("paragraphs");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pieces(Map<String, Object> paragraph) {
        return (List<Map<String, Object>>) paragraph.get("pieces");
    }

    @Test
    @DisplayName("같은 pageId 의 timeline 을 t 순으로 모으고, 같은 t 는 받은 순서를 지키며 지우지 않는다")
    void timelineIsSortedByTKeepingReceivedOrder() {
        Map<String, Object> first = chunk(HEADER_SESSION, page("a", List.of(),
                List.of(tick(300), Map.of("type", "copy", "t", 150, "from", "first"))));
        Map<String, Object> second = chunk(null, page("a", List.of(),
                List.of(tick(150), Map.of("type", "copy", "t", 150, "from", "second"), tick(300))));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(first, second), ENDED);

        assertThat(pages(merged)).hasSize(1);
        assertThat(timeline(pages(merged).get(0))).extracting(event -> event.get("t"))
                .containsExactly(150, 150, 150, 300, 300);
        assertThat(timeline(pages(merged).get(0))).filteredOn(event -> "copy".equals(event.get("type")))
                .extracting(event -> event.get("from")).containsExactly("first", "second");
    }

    @Test
    @DisplayName("청크 하나에 여러 페이지가 있어도 페이지별로 나누고, 페이지 순서는 첫 이벤트의 t 순이다")
    void chunkWithManyPages() {
        Map<String, Object> first = chunk(HEADER_SESSION,
                page("late", List.of(), List.of(tick(900))),
                page("early", List.of(), List.of(tick(150))));
        Map<String, Object> second = chunk(null, page("early", List.of(), List.of(tick(300))));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(first, second), ENDED);

        assertThat(pages(merged)).extracting(page -> meta(page).get("pageId")).containsExactly("early", "late");
        assertThat(timeline(pages(merged).get(0))).hasSize(2);
    }

    @Test
    @DisplayName("유닛은 pid 당 한 줄이고 retired 는 뒤 청크 값을 쓴다")
    void paragraphsAreOnePerPid() {
        Map<String, Object> retired = paragraph("u1", false);
        retired.put("retired", true);
        retired.put("order", 9);
        Map<String, Object> revived = paragraph("u2", false);
        Map<String, Object> wasRetired = paragraph("u2", false);
        wasRetired.put("retired", true);

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(paragraph("u1", false), wasRetired), List.of())),
                chunk(null, page("a", List.of(retired, revived), List.of()))), ENDED);

        List<Map<String, Object>> paragraphs = paragraphs(pages(merged).get(0));
        assertThat(paragraphs).extracting(p -> p.get("pid")).containsExactly("u1", "u2");
        assertThat(paragraphs.get(0)).containsEntry("retired", true).containsEntry("order", 9);
        assertThat(paragraphs.get(1)).doesNotContainKey("retired");
    }

    @Test
    @DisplayName("같은 pid 라도 다른 페이지의 유닛은 따로 둔다")
    void samePidOnOtherPage() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(paragraph("u1", false)), List.of(tick(150)))),
                chunk(null, page("b", List.of(paragraph("u1", true)), List.of(tick(300))))), ENDED);

        assertThat(pieces(paragraphs(pages(merged).get(0)).get(0)).get(0)).containsEntry("noise", false);
        assertThat(pieces(paragraphs(pages(merged).get(1)).get(0)).get(0)).containsEntry("noise", true);
    }

    @Test
    @DisplayName("noise 는 조각 (pid, k) 단위 합집합이다. 판정이 없는 조각은 그대로 둔다")
    void noiseIsUnionPerPiece() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(paragraph("u1", false, true, null)), List.of())),
                chunk(null, page("a", List.of(paragraph("u1", true, false, null)), List.of()))), ENDED);

        List<Map<String, Object>> pieces = pieces(paragraphs(pages(merged).get(0)).get(0));
        assertThat(pieces.get(0)).containsEntry("noise", true);
        assertThat(pieces.get(1)).containsEntry("noise", true);
        assertThat(pieces.get(2)).doesNotContainKey("noise");
    }

    @Test
    @DisplayName("segments 는 페이지별로 모으고 meta 나머지는 먼저 나온 값을 쓴다")
    void segmentsAndRestOfMeta() {
        Map<String, Object> seg1 = Map.of("segId", "s1", "t", 100);
        Map<String, Object> seg2 = Map.of("segId", "s2", "t", 500);
        Map<String, Object> first = Map.of("meta", Map.of("pageId", "a", "title", "처음 제목", "segments", List.of(seg1)));
        Map<String, Object> second = Map.of("meta", Map.of("pageId", "a", "title", "나중 제목", "url", "https://a.com",
                "segments", List.of(seg1, seg2)));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, first), chunk(null, second)), ENDED);

        Map<String, Object> meta = meta(pages(merged).get(0));
        assertThat(meta).containsEntry("title", "처음 제목").containsEntry("url", "https://a.com");
        assertThat(meta.get("segments")).isEqualTo(List.of(seg1, seg2));
    }

    @Test
    @DisplayName("session.visits 를 합쳐 t 순으로 정렬한다")
    void visitsAreMerged() {
        Map<String, Object> firstSession = new HashMap<>(HEADER_SESSION);
        firstSession.put("visits", List.of(Map.of("t", 900, "pageId", "b"), Map.of("t", 100, "pageId", "a")));
        Map<String, Object> secondSession = Map.of("visits", List.of(Map.of("t", 500, "pageId", "a")));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(firstSession), chunk(secondSession)), ENDED);

        assertThat((List<Map<String, Object>>) session(merged).get("visits"))
                .extracting(visit -> visit.get("t")).containsExactly(100, 500, 900);
    }

    @Test
    @DisplayName("공통 값은 어느 청크에 있든 읽고, 뒤 청크에만 있어도 된다")
    void commonValuesFromAnyChunk() {
        Map<String, Object> onlyPages = chunk(null, page("a", List.of(), List.of(tick(150))));
        Map<String, Object> header = chunk(Map.of("startedAt", "2026-10-30T16:44:04.406Z", "tickMs", 150,
                "searchQuery", "검색어"));
        header.put("bundleVersion", 3);

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(onlyPages, header), ENDED);

        assertThat(merged).containsEntry("kind", "rbc-session").containsEntry("bundleVersion", 3);
        assertThat(session(merged)).containsEntry("startedAt", "2026-10-30T16:44:04.406Z")
                .containsEntry("searchQuery", "검색어");
    }

    @Test
    @DisplayName("세션 공통 값이 없으면 startedAt 은 세션 행 값(UTC), kind · sessionId 는 기본값, tickMs 는 페이지 meta 값을 쓴다")
    void fallbacks() {
        Map<String, Object> pageWithTick = Map.of("meta", Map.of("pageId", "a", "tickMs", 200),
                "timeline", List.of(tick(200)));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(chunk(null, pageWithTick)), ENDED);

        assertThat(merged).containsEntry("kind", "rbc-session");
        assertThat(session(merged))
                .containsEntry("sessionId", COLLECTOR_ID)
                .containsEntry("startedAt", "2026-10-30T16:44:04Z")
                .containsEntry("tickMs", 200L)
                .containsEntry("focusMs", 200L);
    }

    @Test
    @DisplayName("focusMs 는 합친 틱 수 × tickMs 로 세션 · 페이지마다 다시 계산한다")
    void focusMs() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION,
                        page("a", List.of(), List.of(tick(150), tick(300), Map.of("type", "focus", "t", 310))),
                        page("b", List.of(), List.of(tick(450))))), ENDED);

        assertThat(session(merged)).containsEntry("focusMs", 450L);
        assertThat(meta(pages(merged).get(0))).containsEntry("focusMs", 300L);
        assertThat(meta(pages(merged).get(1))).containsEntry("focusMs", 150L);
    }

    @Test
    @DisplayName("종료 요청의 endedAt · stopReason 을 세션과 페이지에 채운다")
    void endRequestValues() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(), List.of(tick(150))))), ENDED);

        assertThat(session(merged)).containsEntry("endedAt", "2026-10-30T16:44:18.279Z")
                .containsEntry("stopReason", "user");
        assertThat(meta(pages(merged).get(0))).containsEntry("endedAt", "2026-10-30T16:44:18.279Z");
    }

    @Test
    @DisplayName("자동 종료면 endedAt 은 마지막 틱 시각, stopReason 은 timeout 이다")
    void autoEndValues() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(), List.of(tick(1500), Map.of("type", "segend", "t", 9000))))),
                AUTO_ENDED);

        assertThat(session(merged)).containsEntry("endedAt", "2026-10-30T16:44:05.906Z")
                .containsEntry("stopReason", "timeout");
    }

    @Test
    @DisplayName("AI parse_session 이 요구하는 필드를 모두 갖춘다")
    void satisfiesAiContract() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(paragraph("u1", false)), List.of(tick(150))))), ENDED);

        assertThat(merged.get("kind")).isEqualTo("rbc-session");
        assertThat(session(merged)).containsKeys("sessionId", "startedAt", "tickMs", "endedAt", "focusMs", "stopReason");
        assertThat(pages(merged)).allSatisfy(page -> {
            assertThat(meta(page)).containsKeys("pageId", "paragraphs", "segments");
            assertThat(page).containsKey("timeline");
        });
    }

    @Test
    @DisplayName("필드가 없어도 죽지 않는다: timeline · paragraphs · segments · visits 없음, pageId · pid 없는 항목은 건너뜀")
    void missingFieldsAreTolerated() {
        Map<String, Object> noPageId = Map.of("meta", Map.of("title", "pageId 없음"), "timeline", List.of(tick(150)));
        Map<String, Object> noTimeline = Map.of("meta", Map.of("pageId", "a",
                "paragraphs", List.of(Map.of("text", "pid 없음"), Map.of("pid", "u1"))));

        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, noPageId, noTimeline), Map.of()), ENDED);

        assertThat(pages(merged)).singleElement().satisfies(page -> {
            assertThat(timeline(page)).isEmpty();
            assertThat(paragraphs(page)).extracting(p -> p.get("pid")).containsExactly("u1");
            assertThat(meta(page).get("segments")).isEqualTo(List.of());
        });
        assertThat(session(merged)).containsEntry("visits", List.of()).containsEntry("focusMs", 0L);
    }

    @Test
    @DisplayName("t 가 없는 이벤트도 지우지 않고 뒤에 둔다")
    void eventWithoutTIsKept() {
        Map<String, Object> merged = SessionChunkMerger.merge(List.of(
                chunk(HEADER_SESSION, page("a", List.of(), List.of(Map.of("type", "scroller"), tick(150))))), ENDED);

        assertThat(timeline(pages(merged).get(0))).extracting(event -> event.get("type"))
                .containsExactly("tick", "scroller");
    }

    @Test
    @DisplayName("입력 청크를 고치지 않는다")
    void inputIsNotMutated() {
        Map<String, Object> firstParagraph = paragraph("u1", false);
        Map<String, Object> first = chunk(HEADER_SESSION, page("a", List.of(firstParagraph), List.of()));

        SessionChunkMerger.merge(List.of(first, chunk(null, page("a", List.of(paragraph("u1", true)), List.of()))), ENDED);

        assertThat(pieces(firstParagraph).get(0)).containsEntry("noise", false);
    }

    @Test
    @DisplayName("tickMs 가 어디에도 없으면 합칠 수 없다")
    void noTickMs() {
        assertThatThrownBy(() -> SessionChunkMerger.merge(
                List.of(chunk(Map.of("sessionId", COLLECTOR_ID), page("a", List.of(), List.of()))), ENDED))
                .isInstanceOf(ChunkMergeException.class)
                .hasMessageContaining("tickMs");
    }

    @Test
    @DisplayName("값의 형식이 틀리면 합칠 수 없다")
    void brokenShape() {
        for (Map<String, Object> broken : List.<Map<String, Object>>of(
                Map.of("pages", "배열 아님"),
                Map.of("session", List.of()),
                Map.of("pages", List.of("객체 아님")),
                Map.of("pages", List.of(Map.of("meta", Map.of("pageId", "a"), "timeline", Map.of()))),
                Map.of("pages", List.of(Map.of("meta", Map.of("pageId", "a", "paragraphs", 1)))))) {
            assertThatThrownBy(() -> SessionChunkMerger.merge(List.of(chunk(HEADER_SESSION), broken), ENDED))
                    .isInstanceOf(ChunkMergeException.class);
        }
    }
}
