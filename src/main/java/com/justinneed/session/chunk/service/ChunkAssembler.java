package com.justinneed.session.chunk.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기록 로그 → bundle. 확장 recorder-core/bg-core.js 의 assemble() 을 그대로 옮긴 순수 함수.
 * 결과는 확장 [내보내기] 파일과 JSON 으로 같아야 한다 — ChunkAssemblerTest 가 확장이 만든 정답 파일과 비교한다.
 * 확장 쪽 assemble() 을 바꾸면 이 파일과 정답 파일(src/test/resources/chunk-assembly/)도 같이 바꾼다.
 *
 * 로그 = 청크들을 n 순서로 이은 payload.records (seq 순). 합치기 전에 ChunkCompleteness 가 COMPLETE 여야 한다.
 *
 * JS 와 맞춘 것
 *   - 기록에 없는 칸은 결과에도 없다 (JS 의 undefined 는 JSON 에서 빠진다). null 인 칸은 null 로 남긴다
 *   - `a || b` 는 a 가 비었을 때(null · 없음 · "" · false · 0) b
 *   - 정렬은 안정 정렬, 비교할 값이 숫자가 아니면 같다고 본다 (JS 의 NaN 비교)
 *   - 시각은 toISOString() 과 같은 꼴 (UTC, 밀리초 3자리, Z)
 */
public final class ChunkAssembler {

    static final int TICK_MS = 150;   // 확장 0-core CFG.TICK_MS · bg-core TICK_MS 와 같아야 한다
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private ChunkAssembler() {
    }

    private static final class Page {
        Object meta;
        final Map<Object, Map<String, Object>> paras = new LinkedHashMap<>();
        final List<Map<String, Object>> events = new ArrayList<>();
        final Map<Object, Map<String, Object>> segs = new LinkedHashMap<>();
    }

    private static final class SegFlushes {
        final Object pageId;
        final boolean hasPageId;
        final Set<Double> ns = new LinkedHashSet<>();
        final List<Object> nOriginal = new ArrayList<>();
        int dup;

        SegFlushes(Object pageId, boolean hasPageId) {
            this.pageId = pageId;
            this.hasPageId = hasPageId;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> assemble(List<Map<String, Object>> log) {
        Map<String, Object> startRec = null;
        Map<String, Object> stopRec = null;
        Object query = null;
        var visits = new ArrayList<Map<String, Object>>();
        var pages = new LinkedHashMap<Object, Page>();
        var segFlushes = new LinkedHashMap<Object, SegFlushes>();

        // 1회차: 세션 · 방문 · 페이지
        for (var r : log) {
            Object k = r.get("k");
            if ("start".equals(k)) {
                startRec = r;
                query = or(r.get("query"), null);
            } else if ("stop".equals(k)) {
                stopRec = r;
            } else if ("query".equals(k)) {
                query = or(r.get("q"), null);
            } else if ("visit".equals(k)) {
                var v = new LinkedHashMap<String, Object>();
                copy(r, v, "t", "pageId", "segId", "tabId", "reason");
                visits.add(v);
            } else if ("page".equals(k)) {
                Object pageId = key(r.get("pageId"));
                Page pg = pages.get(pageId);
                if (pg == null) {
                    pg = new Page();
                    pg.meta = r.get("meta");
                    pages.put(pageId, pg);
                }
                // 나중 것이 이긴다 (자리는 처음 들어온 자리 — JS Map.set 과 같다)
                if (r.get("paragraphs") instanceof List<?> ps) {
                    for (Object p : ps) {
                        var para = (Map<String, Object>) p;
                        pg.paras.put(key(para.get("pid")), para);
                    }
                }
                Object segId = key(r.get("segId"));
                if (!pg.segs.containsKey(segId)) {
                    var sg = new LinkedHashMap<String, Object>();
                    copy(r, sg, "segId", "tabId", "t");
                    // JS `r.meta && r.meta.url`: meta 가 null 이면 null, 없거나 url 이 없으면 빠짐
                    if (r.containsKey("meta") && r.get("meta") == null) {
                        sg.put("url", null);
                    } else if (r.get("meta") instanceof Map<?, ?> meta && meta.containsKey("url")) {
                        sg.put("url", meta.get("url"));
                    }
                    pg.segs.put(segId, sg);
                }
            }
        }

        // 2회차: 조각. (segId, n) 이 같은 조각은 처음 것만
        for (var r : log) {
            if (!"flush".equals(r.get("k"))) {
                continue;
            }
            Object segId = key(r.get("segId"));
            var sc = segFlushes.computeIfAbsent(segId, s -> new SegFlushes(r.get("pageId"), r.containsKey("pageId")));
            Object n = r.get("n");
            if (n != null) {
                double nv = num(n);
                if (sc.ns.contains(nv)) {
                    sc.dup++;
                    continue;
                }
                sc.ns.add(nv);
                sc.nOriginal.add(n);
            }
            Page pg = pages.get(key(r.get("pageId")));
            if (pg == null) {
                continue;
            }
            if (r.get("events") instanceof List<?> evs) {
                for (Object e : evs) {
                    var ev = new LinkedHashMap<String, Object>((Map<String, Object>) e);
                    // JS `e.segId || r.segId`: e 쪽이 비었으면 조각의 segId (조각에도 없으면 빠짐)
                    if (!truthy(ev.get("segId"))) {
                        putOrDrop(ev, "segId", r);
                    }
                    putOrDrop(ev, "tabId", r);
                    pg.events.add(ev);
                }
            }
        }

        Object startedAt = startRec != null ? iso(startRec.get("ts")) : null;
        Object endedAt = stopRec != null ? iso(stopRec.get("ts")) : null;
        long total = 0;
        var out = new ArrayList<Map<String, Object>>();
        var droppedPages = new ArrayList<Map<String, Object>>();
        var segEnd = new LinkedHashMap<Object, String>();

        for (var entry : pages.entrySet()) {
            Object pageId = entry.getKey();
            Page pg = entry.getValue();
            var paragraphs = new ArrayList<>(pg.paras.values());
            paragraphs.sort(byNumber("order"));
            var timeline = new ArrayList<>(pg.events);
            timeline.sort(byNumber("t"));
            var segments = new ArrayList<>(pg.segs.values());

            for (var sg : segments) {
                int lastTick = -1;
                int lastHide = -1;
                boolean closed = false;
                for (int i = 0; i < timeline.size(); i++) {
                    var e = timeline.get(i);
                    if (!same(e.get("segId"), sg.get("segId"))) {
                        continue;
                    }
                    Object type = e.get("type");
                    if ("segend".equals(type)) {
                        closed = true;
                    } else if ("tick".equals(type)) {
                        lastTick = i;
                    } else if ("pagehide".equals(type)) {
                        lastHide = i;
                    }
                }
                segEnd.put(key(sg.get("segId")), closed ? "closed" : (lastHide > lastTick ? "unloaded" : "open"));
            }

            long ticks = timeline.stream().filter(e -> "tick".equals(e.get("type"))).count();
            String why = paragraphs.isEmpty() ? "no-units" : ticks == 0 ? "no-ticks" : null;
            if (why != null) {
                var d = new LinkedHashMap<String, Object>();
                d.put("pageId", pageId);
                d.put("reason", why);
                var segs = new ArrayList<Map<String, Object>>();
                for (var g : segments) {
                    var s = new LinkedHashMap<String, Object>();
                    copy(g, s, "segId", "tabId", "url");
                    segs.add(s);
                }
                d.put("segments", segs);
                droppedPages.add(d);
                continue;
            }

            total += ticks * TICK_MS;
            var meta = new LinkedHashMap<String, Object>();
            if (pg.meta instanceof Map<?, ?> m) {
                meta.putAll((Map<String, Object>) m);
            }
            meta.put("sessionId", startRec != null ? startRec.get("sessionId") : null);
            meta.put("startedAt", startedAt);
            meta.put("endedAt", endedAt);
            meta.put("focusMs", ticks * TICK_MS);
            meta.put("pageId", pageId);
            meta.put("segments", segments);
            meta.put("paragraphs", paragraphs);
            var page = new LinkedHashMap<String, Object>();
            page.put("meta", meta);
            page.put("timeline", timeline);
            out.add(page);
        }

        // 조각 유실 보고
        var flushReport = new ArrayList<Map<String, Object>>();
        for (var entry : segFlushes.entrySet()) {
            var sc = entry.getValue();
            Object maxN = -1;
            double maxV = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < sc.nOriginal.size(); i++) {
                double v = num(sc.nOriginal.get(i));
                if (v > maxV) {
                    maxV = v;
                    maxN = sc.nOriginal.get(i);
                }
            }
            var missing = new ArrayList<Integer>();
            for (int i = 0; i <= maxV; i++) {
                if (!sc.ns.contains((double) i)) {
                    missing.add(i);
                }
            }
            var rep = new LinkedHashMap<String, Object>();
            rep.put("segId", entry.getKey());
            putOrDrop(rep, "pageId", sc.hasPageId, sc.pageId);
            rep.put("maxN", maxN);
            rep.put("missing", missing);
            rep.put("duplicates", sc.dup);
            rep.put("end", segEnd.getOrDefault(entry.getKey(), "open"));
            rep.put("orphan", !pages.containsKey(key(sc.pageId)));
            flushReport.add(rep);
        }

        var session = new LinkedHashMap<String, Object>();
        session.put("sessionId", startRec != null ? startRec.get("sessionId") : null);
        session.put("tester", startRec != null ? or(startRec.get("tester"), null) : null);
        session.put("startedAt", startedAt);
        session.put("endedAt", endedAt);
        if (stopRec == null) {
            session.put("stopReason", null);
        } else {
            putOrDrop(session, "stopReason", stopRec.containsKey("reason"), stopRec.get("reason"));
        }
        session.put("focusMs", total);
        session.put("searchQuery", query);
        session.put("scenario", startRec != null ? or(startRec.get("scenario"), null) : null);
        session.put("tickMs", TICK_MS);
        session.put("records", log.size());
        session.put("visits", visits);
        session.put("flushReport", flushReport);
        session.put("droppedPages", droppedPages);

        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("kind", "rbc-session");
        bundle.put("bundleVersion", 1);
        bundle.put("session", session);
        bundle.put("pages", out);
        return bundle;
    }

    // ---- JS 와 맞추는 작은 도구들 ----

    // 기록에 있는 칸만 옮긴다 (없는 칸 = JS undefined = JSON 에서 빠짐)
    private static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String k : keys) {
            if (from.containsKey(k)) {
                to.put(k, from.get(k));
            }
        }
    }

    private static void putOrDrop(Map<String, Object> to, String k, Map<String, Object> from) {
        putOrDrop(to, k, from.containsKey(k), from.get(k));
    }

    private static void putOrDrop(Map<String, Object> to, String k, boolean present, Object v) {
        if (present) {
            to.put(k, v);
        } else {
            to.remove(k);
        }
    }

    // JS `a || b`
    private static Object or(Object a, Object b) {
        return truthy(a) ? a : b;
    }

    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        return true;
    }

    // JS 객체 · Map 의 키. 숫자는 값으로 (1 과 1L 이 같은 키)
    private static Object key(Object v) {
        return v instanceof Number n ? (Object) n.doubleValue() : v;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    // JS sort((a, b) => a[k] - b[k]) — 숫자가 아니면 NaN → 같다고 본다. List.sort 는 안정 정렬
    private static Comparator<Map<String, Object>> byNumber(String k) {
        return (a, b) -> {
            double x = num(a.get(k));
            double y = num(b.get(k));
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return 0;
            }
            return x < y ? -1 : x > y ? 1 : 0;   // Double.compare 와 달리 -0 과 0 을 같게 (JS 뺄셈과 같다)
        };
    }

    private static String iso(Object ts) {
        return ts instanceof Number n ? ISO.format(Instant.ofEpochMilli(n.longValue())) : null;
    }
}
