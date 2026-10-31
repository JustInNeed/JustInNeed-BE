package com.justinneed.home.dto;

import com.justinneed.session.management.dto.SessionListResponse;
import java.util.List;
import java.util.Map;

/**
 * 홈 대시보드 한 화면 데이터 (FE HomeData).
 * 해시태그는 모두 # 없는 원문이고, 사이트는 도메인만 준다 (글자 · 색은 FE).
 */
public record HomeResponse(
        Graph graph,
        Stats stats,
        WeekActivity weekActivity,
        List<TopTag> topTags,
        List<TimelineSlot> todayTimeline,
        List<WeekSession> weekSessions,
        List<SessionListResponse> sessions
) {

    /** edges 는 [출발 노드 id, 도착 노드 id] 쌍의 목록. */
    public record Graph(
            List<Node> nodes,
            List<List<String>> edges,
            Map<String, NodeMeta> nodeMeta,
            List<TagGroup> tagGroups,
            List<String> filterTags
    ) {
    }

    public record Node(
            String id,
            String label,
            int size,
            String group
    ) {
    }

    /** seen 은 today / week / month / all, sessionId 는 이 노드가 나온 가장 최근 세션. */
    public record NodeMeta(
            String seen,
            List<String> tags,
            Long sessionId
    ) {
    }

    public record TagGroup(
            Long id,
            String name,
            List<String> tags
    ) {
    }

    public record Stats(
            int totalNodes,
            int nodeDelta,
            int hashtagCount,
            int weekSessions,
            int sessionDelta,
            int avgSessionMinutes,
            int deepReadingPct,
            int deepReadingDelta
    ) {
    }

    public record WeekActivity(
            String range,
            List<DayActivity> days
    ) {
    }

    public record DayActivity(
            String day,
            int minutes
    ) {
    }

    public record TopTag(
            String tag,
            int count
    ) {
    }

    /** endTime 은 종료 시각이 없는 세션이면 null. */
    public record TimelineSlot(
            String startTime,
            String endTime,
            List<String> tags,
            List<String> sites
    ) {
    }

    /** dayOffset 은 오늘이 0, 가장 오래된 날이 -6. */
    public record WeekSession(
            int dayOffset,
            String startedAt,
            List<String> tags,
            List<Visit> visits
    ) {
    }

    public record Visit(
            String domain,
            long dwellSec
    ) {
    }
}
