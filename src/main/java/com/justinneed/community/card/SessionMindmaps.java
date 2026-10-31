package com.justinneed.community.card;

import com.justinneed.session.analysis.SessionMindmapBuilder;
import com.justinneed.session.analysis.dto.SessionMindmapRow;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 세션들의 AI 마인드맵. 분석 결과가 없거나 저장된 JSON 이 깨진 세션은 빈 마인드맵이다 (예외를 던지지 않는다). */
@Component
public class SessionMindmaps {
    private final SessionResultRepository results;
    private final ObjectMapper objectMapper;

    public SessionMindmaps(SessionResultRepository results, ObjectMapper objectMapper) {
        this.results = results;
        this.objectMapper = objectMapper;
    }

    public SessionMindmapResponse of(BrowsingSession session) {
        return of(List.of(session)).get(session.getId());
    }

    public Map<Long, SessionMindmapResponse> of(Collection<BrowsingSession> sessions) {
        Map<Long, SessionMindmapResponse> mindmaps = new HashMap<>();
        if (sessions.isEmpty()) {
            return mindmaps;
        }
        Map<Long, BrowsingSession> byId = sessions.stream()
                .collect(Collectors.toMap(BrowsingSession::getId, Function.identity()));
        for (SessionMindmapRow row : results.findMindmapRows(byId.keySet())) {
            mindmaps.put(row.sessionId(), SessionMindmapBuilder.build(
                    parse(row.mindmap()), parse(row.selectedUnits()), byId.get(row.sessionId()).getSources()));
        }
        byId.keySet().forEach(id -> mindmaps.putIfAbsent(id, SessionMindmapResponse.EMPTY));
        return mindmaps;
    }

    private Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException error) {
            return null;
        }
    }
}
