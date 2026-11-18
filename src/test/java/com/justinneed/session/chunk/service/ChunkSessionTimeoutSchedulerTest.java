package com.justinneed.session.chunk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("멈춘 수집 세션 정리 (ChunkSessionTimeoutScheduler)")
class ChunkSessionTimeoutSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);

    @Test
    @DisplayName("[X-SES-04 R1] 한 세션 정리가 예외로 실패해도 나머지 세션 정리는 이어서 한다")
    void oneFailureDoesNotStopOthers() {
        BrowsingSessionRepository sessions = mock(BrowsingSessionRepository.class);
        ChunkSessionService chunkSessions = mock(ChunkSessionService.class);
        BrowsingSession first = session(1L);
        BrowsingSession broken = session(2L);
        BrowsingSession last = session(3L);
        when(sessions.findByStatusAndCollectorSessionIdIsNotNull(SessionStatus.COLLECTING))
                .thenReturn(List.of(first, broken, last));
        when(chunkSessions.closeIfQuiet(1L, NOW)).thenReturn(SessionStatus.READY);
        when(chunkSessions.closeIfQuiet(2L, NOW)).thenThrow(new IllegalStateException("boom"));
        when(chunkSessions.closeIfQuiet(3L, NOW)).thenReturn(SessionStatus.FAILED);

        ChunkSessionTimeoutScheduler scheduler =
                new ChunkSessionTimeoutScheduler(
                        sessions,
                        mock(SessionRawChunkRepository.class),
                        chunkSessions,
                        null,
                        false);

        assertThat(scheduler.closeQuietSessions(NOW)).isEqualTo(2);
        verify(chunkSessions).closeIfQuiet(1L, NOW);
        verify(chunkSessions).closeIfQuiet(2L, NOW);
        verify(chunkSessions).closeIfQuiet(3L, NOW);
    }

    private static BrowsingSession session(Long id) {
        BrowsingSession session = mock(BrowsingSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
