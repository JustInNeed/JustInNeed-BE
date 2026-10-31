package com.justinneed.community.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.justinneed.session.analysis.dto.SessionMindmapRow;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class SessionMindmapsTest {

    private final SessionResultRepository results = mock(SessionResultRepository.class);
    private final SessionMindmaps mindmaps = new SessionMindmaps(results, JsonMapper.builder().build());

    private BrowsingSession session(long id) {
        BrowsingSession session = new BrowsingSession(1L, "제목", LocalDateTime.now());
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    @Test
    void unparsableJsonIsAnEmptyMindmap() {
        when(results.findMindmapRows(any())).thenReturn(List.of(new SessionMindmapRow(7L, "{끊긴 JSON", "[1")));

        assertThat(mindmaps.of(session(7L))).isEqualTo(SessionMindmapResponse.EMPTY);
    }

    @Test
    void missingResultIsAnEmptyMindmap() {
        when(results.findMindmapRows(any())).thenReturn(List.of());

        assertThat(mindmaps.of(session(7L))).isEqualTo(SessionMindmapResponse.EMPTY);
        assertThat(mindmaps.of(List.of())).isEmpty();
    }
}
