package com.justinneed.home;

import com.justinneed.home.dto.HomeResponse.Node;
import com.justinneed.home.dto.HomeResponse.NodeMeta;
import com.justinneed.home.repository.HomeSessionRow;
import com.justinneed.session.analysis.SessionHashtagSelector;
import com.justinneed.session.analysis.dto.AiMindmapNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 세션별 마인드맵(session_results.mindmap)을 합쳐 홈 전체 마인드맵을 만든다. DB · 시계 없이 입력만으로 계산한다.
 *
 * <pre>
 * 노드 키   = keyword 의 공백을 모두 지우고 소문자로. 빈 키워드 · 모르는 type 은 건너뛴다
 * 표시 이름 = 가장 최근 세션에서 처음 나온 표기
 * 타입      = 세션마다 다르면 root &gt; topic &gt; unit 중 가장 높은 것
 * 크기      = 타입별 최솟값에서 세션 1개 늘 때마다 +2, 타입별 최댓값까지
 * 엣지      = core → 모든 root + hierarchy 엣지 (related · 자기 자신 · 중복 · 방향만 다른 중복 제외)
 * 개수 제한 = core 포함 150개. root → topic → unit, 같은 타입은 나온 세션이 많은 순 → 최근 순
 * 정리      = 남은 노드 사이의 엣지만 두고 core 에서 닿지 않는 노드를 뺀다. 남은 노드가 없으면 core 도 두지 않는다
 * </pre>
 *
 * AI JSON 은 이미 저장된 값이라 형식이 어긋나도 예외를 던지지 않고 해당 항목만 건너뛴다.
 */
public final class HomeGraphBuilder {

    private static final String CORE_ID = "core";
    private static final String CORE_LABEL = "내 탐색";
    private static final String CORE_GROUP = "core";
    private static final int CORE_SIZE = 28;
    private static final int MAX_NODES = 150;
    private static final int SIZE_STEP_PER_SESSION = 2;
    private static final String EDGE_TYPE_HIERARCHY = "hierarchy";
    private static final int WEEK_DAYS = 7;
    private static final int MONTH_DAYS = 30;

    private static final Comparator<MergedNode> KEEP_ORDER = Comparator
            .comparing(MergedNode::type)
            .thenComparing(MergedNode::sessionCount, Comparator.reverseOrder())
            .thenComparingInt(MergedNode::latestOrder)
            .thenComparing(MergedNode::key);

    private HomeGraphBuilder() {
    }

    /**
     * @param sessions 대상 세션, 시작 시각 최신순
     * @param today    오늘 (KST)
     */
    public static Result build(List<HomeSessionRow> sessions, LocalDate today) {
        Map<String, MergedNode> mergedByKey = new LinkedHashMap<>();
        Map<Set<String>, Edge> edges = new LinkedHashMap<>();
        for (int order = 0; order < sessions.size(); order++) {
            HomeSessionRow session = sessions.get(order);
            Map<String, MergedNode> mergedById = mergeNodes(session, order, mergedByKey);
            addEdges(session, mergedById, edges);
        }

        List<MergedNode> kept = mergedByKey.values().stream()
                .sorted(KEEP_ORDER)
                .limit(MAX_NODES - 1)
                .toList();
        List<MergedNode> connected = connectedToCore(kept, edges.values());

        LocalDate weekStart = today.minusDays(WEEK_DAYS - 1);
        int nodeDelta = (int) mergedByKey.values().stream()
                .filter(node -> !node.firstSeenOn().isBefore(weekStart))
                .count();
        return toResult(connected, edges.values(), mergedByKey.size(), nodeDelta, today);
    }

    // 세션 하나의 노드를 합치고, 이 세션의 노드 id → 합친 노드를 돌려준다 (엣지를 옮기는 데 쓴다)
    private static Map<String, MergedNode> mergeNodes(HomeSessionRow session, int order,
            Map<String, MergedNode> mergedByKey) {
        Map<String, MergedNode> mergedById = new HashMap<>();
        for (Object item : listOf(mapValue(session.mindmap(), "nodes"))) {
            if (!(item instanceof Map<?, ?> node)) {
                continue;
            }
            NodeType type = NodeType.of(node.get("type"));
            if (type == null || !(node.get("keyword") instanceof String keyword)) {
                continue;
            }
            String key = keyOf(keyword);
            if (key.isEmpty()) {
                continue;
            }
            MergedNode merged = mergedByKey.computeIfAbsent(key,
                    newKey -> new MergedNode(newKey, keyword.strip(), order, session));
            merged.addOccurrence(type, session);
            if (node.get("id") instanceof String id) {
                mergedById.put(id, merged);
            }
        }
        return mergedById;
    }

    private static void addEdges(HomeSessionRow session, Map<String, MergedNode> mergedById,
            Map<Set<String>, Edge> edges) {
        for (Object item : listOf(mapValue(session.mindmap(), "edges"))) {
            if (!(item instanceof Map<?, ?> edge) || !EDGE_TYPE_HIERARCHY.equals(edge.get("type"))) {
                continue;
            }
            MergedNode from = mergedById.get(edge.get("from"));
            MergedNode to = mergedById.get(edge.get("to"));
            if (from == null || to == null || from == to) {
                continue;
            }
            // Set 키라 [a, b] 와 [b, a] 가 같은 엣지가 된다. 먼저 나온(최근 세션) 방향을 남긴다
            edges.putIfAbsent(Set.of(from.key(), to.key()), new Edge(from, to));
        }
    }

    // 남은 노드 사이의 엣지를 따라 root(= core 에 바로 연결)에서 닿는 노드만 남긴다
    private static List<MergedNode> connectedToCore(List<MergedNode> kept, Collection<Edge> edges) {
        Set<MergedNode> keptSet = new HashSet<>(kept);
        Map<MergedNode, List<MergedNode>> neighbors = new HashMap<>();
        for (Edge edge : edges) {
            if (keptSet.contains(edge.from()) && keptSet.contains(edge.to())) {
                neighbors.computeIfAbsent(edge.from(), node -> new ArrayList<>()).add(edge.to());
                neighbors.computeIfAbsent(edge.to(), node -> new ArrayList<>()).add(edge.from());
            }
        }

        Deque<MergedNode> queue = new ArrayDeque<>();
        Set<MergedNode> reached = new HashSet<>();
        for (MergedNode node : kept) {
            if (node.type() == NodeType.ROOT) {
                queue.add(node);
                reached.add(node);
            }
        }
        while (!queue.isEmpty()) {
            for (MergedNode neighbor : neighbors.getOrDefault(queue.poll(), List.of())) {
                if (reached.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return kept.stream()
                .filter(reached::contains)
                .toList();
    }

    private static Result toResult(List<MergedNode> nodes, Collection<Edge> edges, int totalNodes, int nodeDelta,
            LocalDate today) {
        if (nodes.isEmpty()) {
            return new Result(List.of(), List.of(), Map.of(), totalNodes, nodeDelta);
        }

        List<Node> responseNodes = new ArrayList<>();
        List<List<String>> responseEdges = new ArrayList<>();
        Map<String, NodeMeta> nodeMeta = new LinkedHashMap<>();
        Map<MergedNode, String> idByNode = new HashMap<>();
        responseNodes.add(new Node(CORE_ID, CORE_LABEL, CORE_SIZE, CORE_GROUP));
        for (MergedNode node : nodes) {
            String id = "n" + (idByNode.size() + 1);
            idByNode.put(node, id);
            responseNodes.add(new Node(id, node.label(), node.size(), node.type().group()));
            nodeMeta.put(id, new NodeMeta(seen(node.lastSeenOn(), today), node.tags(), node.latestSessionId()));
            if (node.type() == NodeType.ROOT) {
                responseEdges.add(List.of(CORE_ID, id));
            }
        }
        for (Edge edge : edges) {
            String from = idByNode.get(edge.from());
            String to = idByNode.get(edge.to());
            if (from != null && to != null) {
                responseEdges.add(List.of(from, to));
            }
        }
        return new Result(responseNodes, responseEdges, nodeMeta, totalNodes, nodeDelta);
    }

    // 최근 7일 · 30일은 오늘을 포함해 센다
    private static String seen(LocalDate lastSeenOn, LocalDate today) {
        long daysAgo = ChronoUnit.DAYS.between(lastSeenOn, today);
        if (daysAgo <= 0) {
            return "today";
        }
        if (daysAgo < WEEK_DAYS) {
            return "week";
        }
        if (daysAgo < MONTH_DAYS) {
            return "month";
        }
        return "all";
    }

    /** 노드 키. 해시태그 노드 수도 같은 키로 센다. */
    public static String keyOf(String keyword) {
        return SessionHashtagSelector.removeWhitespace(keyword).toLowerCase(Locale.ROOT);
    }

    private static Object mapValue(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /**
     * nodes 는 core 가 맨 앞(남은 노드가 없으면 빈 목록), totalNodes 는 개수 제한 전 합친 노드 수 (core 제외),
     * nodeDelta 는 그중 최근 7일 세션에서 처음 나온 노드 수.
     */
    public record Result(
            List<Node> nodes,
            List<List<String>> edges,
            Map<String, NodeMeta> nodeMeta,
            int totalNodes,
            int nodeDelta
    ) {
    }

    private record Edge(MergedNode from, MergedNode to) {
    }

    // 선언 순서가 곧 우선순위다 (root > topic > unit)
    private enum NodeType {
        ROOT(AiMindmapNode.TYPE_ROOT, "city", 16, 24),
        TOPIC(AiMindmapNode.TYPE_TOPIC, "logi", 12, 18),
        UNIT(AiMindmapNode.TYPE_UNIT, "sub", 7, 12);

        private final String aiType;
        private final String group;
        private final int minSize;
        private final int maxSize;

        NodeType(String aiType, String group, int minSize, int maxSize) {
            this.aiType = aiType;
            this.group = group;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        private static NodeType of(Object aiType) {
            for (NodeType type : values()) {
                if (type.aiType.equals(aiType)) {
                    return type;
                }
            }
            return null;
        }

        private String group() {
            return group;
        }

        private int size(int sessionCount) {
            return Math.min(maxSize, minSize + SIZE_STEP_PER_SESSION * (sessionCount - 1));
        }
    }

    // 세션을 최신순으로 훑으며 채운다. 첫 세션이 가장 최근, 마지막에 더한 세션이 가장 오래된 세션이다
    private static final class MergedNode {

        private final String key;
        private final String label;
        private final int latestOrder;
        private final HomeSessionRow latestSession;
        private final Map<String, String> tagByKey = new LinkedHashMap<>();
        private HomeSessionRow earliestSession;
        private NodeType type;
        private int sessionCount;

        private MergedNode(String key, String label, int latestOrder, HomeSessionRow latestSession) {
            this.key = key;
            this.label = label;
            this.latestOrder = latestOrder;
            this.latestSession = latestSession;
        }

        private void addOccurrence(NodeType occurrenceType, HomeSessionRow session) {
            if (type == null || occurrenceType.compareTo(type) < 0) {
                type = occurrenceType;
            }
            // 한 세션에 같은 키워드가 여러 번 나와도 세션 1개로 센다
            if (session == earliestSession) {
                return;
            }
            earliestSession = session;
            sessionCount++;
            for (String tag : session.tags()) {
                if (tag != null) {
                    tagByKey.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
                }
            }
        }

        private String key() {
            return key;
        }

        private String label() {
            return label;
        }

        private int latestOrder() {
            return latestOrder;
        }

        private NodeType type() {
            return type;
        }

        private int sessionCount() {
            return sessionCount;
        }

        private int size() {
            return type.size(sessionCount);
        }

        private Long latestSessionId() {
            return latestSession.id();
        }

        private LocalDate lastSeenOn() {
            return latestSession.startDate();
        }

        private LocalDate firstSeenOn() {
            return earliestSession.startDate();
        }

        private List<String> tags() {
            return List.copyOf(tagByKey.values());
        }
    }
}
