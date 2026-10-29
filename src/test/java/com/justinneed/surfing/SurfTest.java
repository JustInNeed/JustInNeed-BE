package com.justinneed.surfing;
import com.justinneed.community.CommunityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SurfTest extends CommunityTestSupport {
    @Autowired SurfService service;
    @Autowired SurfSessionRepository surfSessions;
    @Autowired SurfEventRepository events;
    @MockitoBean SurfModel model;
    @Test void collectsExcludesDeduplicatesAndCreatesOnePrivateRecord() {
        String token=service.connect(ownerId).extensionToken();
        var s=service.start(ownerId,token);
        var e=new SurfRequests.Event("e1",SurfEvent.Type.PAGE_VIEW,"https://example.com/a","Page","text",Instant.now(),1000);
        var denied=new SurfRequests.Event("e2",SurfEvent.Type.CLICK,"https://mail.google.com/inbox","mail","secret",Instant.now(),0);
        assertThat(service.collect(ownerId,s.sessionId(),token,new SurfRequests.Batch(List.of(e,denied))).accepted()).isEqualTo(1);
        assertThat(service.collect(ownerId,s.sessionId(),token,new SurfRequests.Batch(List.of(e))).ignored()).isEqualTo(1);
        when(model.analyze(anyList())).thenReturn(new SurfModel.Result("Record","Summary",List.of("Java"),Map.of("e1",SurfEvent.Behavior.READING)));
        service.end(ownerId,s.sessionId(),token,SurfRequests.EndReason.USER);
        service.process(ownerId,s.sessionId());
        var done=service.status(ownerId,s.sessionId());
        assertThat(done.status()).isEqualTo(SurfSession.State.COMPLETED);
        assertThat(sessions.findById(done.recordId()).orElseThrow().isPublicSession()).isFalse();
        service.process(ownerId,s.sessionId());
        verify(model,times(1)).analyze(anyList());
        assertThat(events.findBySessionIdOrderByOccurredAtAscIdAsc(s.sessionId())).hasSize(1);
    }
    @Test void failuresCanRetryAndOwnershipAndClosedCollectionAreEnforced() throws Exception {
        String token=service.connect(ownerId).extensionToken();
        var s=service.start(ownerId,token);
        assertThatThrownBy(()->service.start(ownerId,token)).hasMessageContaining("already active");
        service.scrap(ownerId,s.sessionId(),token,new SurfRequests.Scrap("s1","https://example.com","page","selected"));
        assertThat(service.scraps(ownerId,s.sessionId())).hasSize(1);
        service.end(ownerId,s.sessionId(),token,SurfRequests.EndReason.BROWSER_CLOSED);
        service.process(ownerId,s.sessionId());
        assertThat(service.status(ownerId,s.sessionId()).status()).isEqualTo(SurfSession.State.FAILED);
        assertThat(service.retry(ownerId,s.sessionId()).status()).isEqualTo(SurfSession.State.ANALYZING);
        mvc.perform(get("/surfing/sessions/{id}/analysis-status",s.sessionId()).header("Authorization",auth(viewerId))).andExpect(status().isNotFound());
        mvc.perform(post("/surfing/sessions")).andExpect(status().isUnauthorized());
    }
    @Test void timeoutEndsAtDeadlineAndExcludedDomainBoundariesAreSafe() {
        String token=service.connect(ownerId).extensionToken();
        var s=service.start(ownerId,token);
        surfSessions.findById(s.sessionId()).orElseThrow().touch(Instant.now().minusSeconds(1810));
        assertThat(service.status(ownerId,s.sessionId()).status()).isEqualTo(SurfSession.State.ANALYZING);
        assertThat(service.blocked(ownerId,"https://www.instagram.com/user")).isTrue();
        assertThat(service.blocked(ownerId,"https://instagram.com.evil.test")).isFalse();
        assertThatThrownBy(()->service.blocked(ownerId,"javascript:alert(1)")).hasMessageContaining("HTTP");
    }
}
