package com.justinneed.session.analysis;

import com.justinneed.session.analysis.dto.AiMindmapNode;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import com.justinneed.session.management.dto.SessionMindmapResponse.Citation;
import com.justinneed.session.management.dto.SessionMindmapResponse.Edge;
import com.justinneed.session.management.dto.SessionMindmapResponse.Node;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 마인드맵(session_results.mindmap) + 컷오프 유닛(selected_units) → 세션 마인드맵 응답.
 *
 * <pre>
 * 노드   = root / topic / unit 만. 같은 id 는 처음 것만, 빈 키워드 · 모르는 type 은 건너뛴다
 * 인용   = unit 노드의 id 가 컷오프 유닛의 unitKey 와 같으면 글 제목 · 링크 · 원문.
 *          방문 시각은 세션 출처와 같은 값, 출처에 없으면 유닛을 처음 본 시각
 * 엣지   = hierarchy / related 만, 양 끝 노드가 모두 남은 것만. primary 는 없으면 false
 * </pre>
 *
 * AI JSON 은 이미 저장된 값이라 형식이 어긋나도 예외를 던지지 않고 해당 항목만 건너뛴다.
 */
public final class SessionMindmapBuilder {

    private static final Set<String> NODE_TYPES = Set.of(
            AiMindmapNode.TYPE_ROOT, AiMindmapNode.TYPE_TOPIC, AiMindmapNode.TYPE_UNIT);
    private static final Set<String> EDGE_TYPES = Set.of("hierarchy", "related");

    private SessionMindmapBuilder() {
    }

    public static SessionMindmapResponse build(Map<String, Object> mindmap, List<Map<String, Object>> selectedUnits,
            List<Source> sources) {
        if (mindmap == null) {
            return SessionMindmapResponse.EMPTY;
        }
        Map<String, Citation> citations = citations(selectedUnits, sources);
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Object item : list(mindmap.get("nodes"))) {
            if (item instanceof Map<?, ?> node
                    && node.get("id") instanceof String id && !id.isBlank()
                    && node.get("type") instanceof String type && NODE_TYPES.contains(type)
                    && node.get("keyword") instanceof String keyword && !keyword.isBlank()) {
                nodes.putIfAbsent(id, new Node(id, type, keyword, number(node.get("score")), childCount(node),
                        AiMindmapNode.TYPE_UNIT.equals(type) ? citations.get(id) : null));
            }
        }

        List<Edge> edges = new ArrayList<>();
        for (Object item : list(mindmap.get("edges"))) {
            if (item instanceof Map<?, ?> edge
                    && edge.get("from") instanceof String from && nodes.containsKey(from)
                    && edge.get("to") instanceof String to && nodes.containsKey(to)
                    && edge.get("type") instanceof String type && EDGE_TYPES.contains(type)) {
                edges.add(new Edge(from, to, type, Boolean.TRUE.equals(edge.get("primary"))));
            }
        }
        return new SessionMindmapResponse(List.copyOf(nodes.values()), edges);
    }

    private static Map<String, Citation> citations(List<Map<String, Object>> selectedUnits, List<Source> sources) {
        Map<String, LocalDateTime> visitedAtByLink = new HashMap<>();
        for (Source source : sources) {
            if (source.visitedAt() != null) {
                visitedAtByLink.putIfAbsent(source.url(), source.visitedAt());
            }
        }

        Map<String, Citation> citations = new HashMap<>();
        for (Object item : list(selectedUnits)) {
            if (item instanceof Map<?, ?> unit
                    && unit.get("unitKey") instanceof String unitKey
                    && unit.get("pageUrl") instanceof String url && !url.isBlank()) {
                LocalDateTime visitedAt = visitedAtByLink.containsKey(url)
                        ? visitedAtByLink.get(url)
                        : RawLinkVisits.toKst(unit.get("firstSeenAt"));
                citations.putIfAbsent(unitKey,
                        new Citation(text(unit.get("pageTitle")), url, text(unit.get("text")), visitedAt));
            }
        }
        return citations;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static int childCount(Map<?, ?> node) {
        return node.get("childCount") instanceof Number number ? number.intValue() : 0;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }
}
