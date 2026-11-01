package com.justinneed.session.collection;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.analysis.RawLinkVisits;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 청크들을 AI 가 읽는 세션 원본(stepC json) 하나로 합친다. DB 를 쓰지 않는 순수 로직이다.
 * 청크는 기존 JSON 을 쪼갠 형태로 가정한다: { kind, bundleVersion, session{...}, pages[{meta{...}, timeline[...]}] } 의 일부.
 * 실제 청크 형식이 정해지면 이 클래스를 맞춘다.
 *
 * <pre>
 * payloads    받은 순서(session_raw_chunks.id). 청크 id 순서는 쓰지 않는다
 * 공통 값     값이 들어 있는 청크 중 먼저 나온 값 (kind, bundleVersion, session.*, meta.*)
 * pages       pageId 로 묶는다. 페이지 순서는 첫 이벤트의 t 순
 * timeline    t 순 정렬, 같은 t 는 받은 순서. 지우지 않는다
 * visits      모아서 t 순 정렬
 * paragraphs  pid 당 한 줄. retired · order 는 뒤 청크 값, noise 는 조각 (pid, k) 단위 합집합
 * segments    페이지별로 모은다 (같은 항목은 한 번만)
 * focusMs     합친 틱 수 × tickMs (세션 · 페이지 각각)
 * endedAt     종료 요청 값. 자동 종료면 마지막 틱 시각 (페이지 meta 에도 같은 값, 수집기와 동일)
 * stopReason  종료 요청 값. 자동 종료면 timeout
 * </pre>
 *
 * 없는 필드는 건너뛴다. 값의 형식이 틀렸거나(pages 가 배열이 아님 등) tickMs 를 어디서도 못 찾으면 ChunkMergeException.
 * 입력 Map 은 고치지 않는다 (청크 엔티티 값일 수 있다).
 */
public final class SessionChunkMerger {

    public static final String KIND = "rbc-session";
    public static final String STOP_REASON_TIMEOUT = "timeout";

    private static final Logger log = LoggerFactory.getLogger(SessionChunkMerger.class);
    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final String TICK = "tick";

    private SessionChunkMerger() {
    }

    /**
     * @param startedAt  session.startedAt 공통 값이 없을 때 쓴다 (sessions.started_at, KST)
     * @param endedAt    종료 요청 값 (KST). 자동 종료면 null
     * @param stopReason 종료 요청 값. 자동 종료면 null
     */
    public record Context(
            String collectorSessionId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String stopReason
    ) {
    }

    public static Map<String, Object> merge(List<Map<String, Object>> payloads, Context context) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> session = new LinkedHashMap<>();
        List<Object> visits = new ArrayList<>();
        Map<String, PageMerge> pages = new LinkedHashMap<>();

        for (Map<String, Object> payload : payloads) {
            putFirstValues(merged, payload, Set.of("session", "pages"));
            Map<String, Object> chunkSession = mapOf(payload.get("session"), "session");
            putFirstValues(session, chunkSession, Set.of("visits"));
            visits.addAll(listOf(chunkSession.get("visits"), "session.visits"));
            for (Object page : listOf(payload.get("pages"), "pages")) {
                collectPage(pages, mapOf(page, "pages[]"));
            }
        }

        long tickMs = tickMs(session, pages);
        List<PageMerge> ordered = pages.values().stream()
                .sorted(Comparator.comparingDouble(PageMerge::firstT))
                .toList();

        fillSession(session, context, ordered, tickMs);
        session.put("visits", sortedByT(visits));

        merged.putIfAbsent("kind", KIND);
        merged.put("session", session);
        Object endedAt = session.get("endedAt");
        merged.put("pages", ordered.stream().map(page -> page.toJson(tickMs, endedAt)).toList());
        return merged;
    }

    private static void fillSession(Map<String, Object> session, Context context, List<PageMerge> pages, long tickMs) {
        session.putIfAbsent("sessionId", context.collectorSessionId());

        LocalDateTime startedAt = RawLinkVisits.toKst(session.get("startedAt"));
        if (startedAt == null) {
            startedAt = context.startedAt();
            session.put("startedAt", toUtcText(startedAt));
        }
        session.put("tickMs", tickMs);

        LocalDateTime endedAt = context.endedAt() != null
                ? context.endedAt()
                : startedAt.plus(lastT(pages), ChronoUnit.MILLIS);
        session.put("endedAt", toUtcText(endedAt));
        session.put("stopReason", context.stopReason() != null ? context.stopReason() : STOP_REASON_TIMEOUT);
        session.put("focusMs", pages.stream().mapToLong(PageMerge::tickCount).sum() * tickMs);
    }

    // 세션 시작부터 마지막 틱까지(ms). 틱이 없으면 마지막 이벤트까지
    private static long lastT(List<PageMerge> pages) {
        OptionalDouble lastTick = pages.stream()
                .flatMap(page -> page.timeline.stream())
                .filter(SessionChunkMerger::isTick)
                .mapToDouble(SessionChunkMerger::tOf)
                .filter(Double::isFinite)
                .max();
        if (lastTick.isPresent()) {
            return (long) lastTick.getAsDouble();
        }
        return (long) pages.stream()
                .flatMap(page -> page.timeline.stream())
                .mapToDouble(SessionChunkMerger::tOf)
                .filter(Double::isFinite)
                .max()
                .orElse(0);
    }

    // 초 소수점이 길면 AI(파이썬 fromisoformat)가 못 읽을 수 있어 수집기와 같은 ms 까지만 쓴다
    private static String toUtcText(LocalDateTime kst) {
        return DateTimeFormatter.ISO_INSTANT.format(kst.atZone(KST).toInstant().truncatedTo(ChronoUnit.MILLIS));
    }

    private static long tickMs(Map<String, Object> session, Map<String, PageMerge> pages) {
        if (session.get("tickMs") instanceof Number tickMs) {
            return tickMs.longValue();
        }
        return pages.values().stream()
                .filter(page -> page.meta.get("tickMs") instanceof Number)
                .map(page -> (Number) page.meta.get("tickMs"))
                .findFirst()
                .map(Number::longValue)
                .orElseThrow(() -> new ChunkMergeException("tickMs 가 어떤 청크에도 없음"));
    }

    private static void collectPage(Map<String, PageMerge> pages, Map<String, Object> page) {
        Map<String, Object> meta = mapOf(page.get("meta"), "pages[].meta");
        if (!(meta.get("pageId") instanceof String pageId) || pageId.isBlank()) {
            log.warn("[chunk merge] pageId 가 없는 페이지를 건너뜀");
            return;
        }
        pages.computeIfAbsent(pageId, key -> new PageMerge()).add(page, meta);
    }

    private static List<Object> sortedByT(List<Object> events) {
        // 안정 정렬이라 같은 t 는 받은 순서를 유지한다. t 가 없는 이벤트도 지우지 않고 뒤에 둔다
        return events.stream().sorted(Comparator.comparingDouble(SessionChunkMerger::tOf)).toList();
    }

    private static double tOf(Object event) {
        return event instanceof Map<?, ?> map && map.get("t") instanceof Number t
                ? t.doubleValue()
                : Double.POSITIVE_INFINITY;
    }

    private static boolean isTick(Object event) {
        return event instanceof Map<?, ?> map && TICK.equals(map.get("type"));
    }

    private static void putFirstValues(Map<String, Object> target, Map<String, Object> source, Set<String> skipped) {
        source.forEach((key, value) -> {
            if (value != null && !skipped.contains(key)) {
                target.putIfAbsent(key, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value, String name) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ChunkMergeException(name + " 가 객체가 아님");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value, String name) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new ChunkMergeException(name + " 가 배열이 아님");
    }

    /** pageId 하나로 모으는 중인 페이지. */
    private static final class PageMerge {

        private static final Set<String> META_MERGED = Set.of("paragraphs", "segments", "focusMs", "endedAt");

        private final Map<String, Object> rest = new LinkedHashMap<>();
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> paragraphs = new LinkedHashMap<>();
        private final Set<Object> segments = new LinkedHashSet<>();
        private final List<Object> timeline = new ArrayList<>();

        void add(Map<String, Object> page, Map<String, Object> chunkMeta) {
            putFirstValues(rest, page, Set.of("meta", "timeline"));
            putFirstValues(meta, chunkMeta, META_MERGED);
            segments.addAll(listOf(chunkMeta.get("segments"), "meta.segments"));
            timeline.addAll(listOf(page.get("timeline"), "pages[].timeline"));
            for (Object paragraph : listOf(chunkMeta.get("paragraphs"), "meta.paragraphs")) {
                addParagraph(mapOf(paragraph, "meta.paragraphs[]"));
            }
        }

        private void addParagraph(Map<String, Object> paragraph) {
            if (!(paragraph.get("pid") instanceof String pid)) {
                log.warn("[chunk merge] pid 가 없는 유닛을 건너뜀");
                return;
            }
            Map<String, Object> kept = paragraphs.get(pid);
            if (kept == null) {
                Map<String, Object> copy = new LinkedHashMap<>(paragraph);
                copy.put("pieces", copyPieces(paragraph));
                paragraphs.put(pid, copy);
                return;
            }
            // 재스캔으로 유닛이 사라지면 뒤 청크에서 retired 와 뒤로 밀린 order 가 온다
            if (paragraph.containsKey("retired")) {
                kept.put("retired", paragraph.get("retired"));
            } else {
                kept.remove("retired");
            }
            if (paragraph.get("order") != null) {
                kept.put("order", paragraph.get("order"));
            }
            mergeNoise(kept, paragraph);
            putFirstValues(kept, paragraph, Set.of("retired", "order", "pieces"));
        }

        private static List<Object> copyPieces(Map<String, Object> paragraph) {
            List<Object> pieces = new ArrayList<>();
            for (Object piece : listOf(paragraph.get("pieces"), "paragraphs[].pieces")) {
                pieces.add(new LinkedHashMap<>(mapOf(piece, "paragraphs[].pieces[]")));
            }
            return pieces;
        }

        // 배열 순서가 조각 번호 k. 청크 하나에서라도 noise 면 noise 로 본다
        private static void mergeNoise(Map<String, Object> kept, Map<String, Object> later) {
            List<Object> pieces = listOf(kept.get("pieces"), "paragraphs[].pieces");
            List<Object> laterPieces = copyPieces(later);
            for (int k = 0; k < laterPieces.size(); k++) {
                Map<String, Object> laterPiece = mapOf(laterPieces.get(k), "paragraphs[].pieces[]");
                if (k >= pieces.size()) {
                    pieces.add(laterPiece);
                } else if (Boolean.TRUE.equals(laterPiece.get("noise"))) {
                    mapOf(pieces.get(k), "paragraphs[].pieces[]").put("noise", true);
                }
            }
        }

        long tickCount() {
            return timeline.stream().filter(SessionChunkMerger::isTick).count();
        }

        double firstT() {
            return timeline.stream().mapToDouble(SessionChunkMerger::tOf).min().orElse(Double.POSITIVE_INFINITY);
        }

        Map<String, Object> toJson(long tickMs, Object endedAt) {
            Map<String, Object> mergedMeta = new LinkedHashMap<>(meta);
            mergedMeta.put("focusMs", tickCount() * tickMs);
            mergedMeta.put("endedAt", endedAt);
            mergedMeta.put("segments", new ArrayList<>(segments));
            mergedMeta.put("paragraphs", new ArrayList<>(paragraphs.values()));

            Map<String, Object> page = new LinkedHashMap<>(rest);
            page.put("meta", mergedMeta);
            page.put("timeline", sortedByT(timeline));
            return page;
        }
    }
}
