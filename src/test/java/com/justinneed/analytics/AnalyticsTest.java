package com.justinneed.analytics;
import com.justinneed.community.CommunityTestSupport;
import com.justinneed.surfing.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AnalyticsTest extends CommunityTestSupport {
    @Autowired AnalyticsService analytics;
    @Autowired SurfService surfing;
    @Autowired SurfSessionRepository surfSessions;
    @Autowired SurfEventRepository surfEvents;
    @MockitoBean SurfModel model;
    @Test void timelineIncludesVisitsFromSessionsStartingBeforeSelectedDate() {
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        var s=new SurfSession(ownerId,today.minusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        s.end(Instant.now(),"USER");
        surfSessions.saveAndFlush(s);
        surfEvents.saveAndFlush(new SurfEvent(s.getId(),ownerId,new SurfRequests.Event("midnight",SurfEvent.Type.PAGE_VIEW,
            "https://example.com","page","text",Instant.now(),0)));
        assertThat(analytics.timeline(ownerId,today,today).visits()).hasSize(1);
    }
    @Test void categoriesRespectDomainBoundariesAndOfficialPatterns() {
        assertThat(DomainCategory.classify("https://docs.spring.io/a")).isEqualTo(DomainCategory.OFFICIAL);
        assertThat(DomainCategory.classify("https://github.com.evil.test")).isEqualTo(DomainCategory.OTHER);
        assertThat(DomainCategory.classify("https://blog.naver.com/a")).isEqualTo(DomainCategory.BLOG);
        assertThat(DomainCategory.classify("https://www.reddit.com/r/java")).isEqualTo(DomainCategory.COMMUNITY);
    }
    @Test void endpointsAggregateOnlyOwnersRecordsAndModelClassifications() throws Exception {
        var token=surfing.connect(ownerId).extensionToken();
        var s=surfing.start(ownerId,token);
        surfing.collect(ownerId,s.sessionId(),token,new SurfRequests.Batch(List.of(
            new SurfRequests.Event("v1",SurfEvent.Type.PAGE_VIEW,"https://velog.io/a","Blog","text",Instant.now(),30000),
            new SurfRequests.Event("v2",SurfEvent.Type.PAGE_VIEW,"https://docs.spring.io/a","Docs","text",Instant.now(),30000))));
        when(model.analyze(anyList())).thenReturn(new SurfModel.Result("Session","Summary",List.of("Java"),
            Map.of("v1",SurfEvent.Behavior.DEEP_READING,"v2",SurfEvent.Behavior.READING)));
        surfing.end(ownerId,s.sessionId(),token,SurfRequests.EndReason.USER);
        surfing.process(ownerId,s.sessionId());
        var source=analytics.sources(ownerId,null,null);
        assertThat(source.categories().stream().mapToLong(AnalyticsService.Category::visits).sum()).isEqualTo(2);
        assertThat(source.totalDeepReadingMillis()).isEqualTo(30000);
        assertThat(source.topDeepReadingDomain()).isEqualTo("velog.io");
        assertThat(analytics.timeline(ownerId,null,null).visits()).hasSize(2);
        assertThat(analytics.timeline(ownerId,null,null).visits().get(0).hashtags()).containsExactly("Java");
        assertThat(analytics.reading(ownerId,null,null).distribution().stream().mapToDouble(AnalyticsService.BehaviorCount::percent).sum()).isEqualTo(100);
        assertThat(analytics.time(ownerId,null,null).hours()).hasSize(24);
        assertThat(analytics.exploration(ownerId,null,null).sessions().get(0).uniqueUrls()).isEqualTo(2);
        assertThat(analytics.timeline(viewerId,null,null).visits()).isEmpty();
        mvc.perform(get("/analytics/source-statistics").header("Authorization",auth(ownerId))).andExpect(status().isOk());
        var recordId=surfing.status(ownerId,s.sessionId()).recordId();
        sessions.findById(recordId).orElseThrow().delete();
        assertThat(analytics.timeline(ownerId,null,null).visits()).isEmpty();
    }
    @Test void emptyDataDateLimitsAndAuthenticationAreHandled() throws Exception {
        assertThat(analytics.reading(ownerId,null,null).dominant()).isEqualTo(SurfEvent.Behavior.UNKNOWN);
        assertThat(analytics.sources(ownerId,null,null).totalDeepReadingMillis()).isZero();
        assertThat(analytics.time(ownerId,null,null).recommendedReadingHour()).isNull();
        mvc.perform(get("/analytics/timeline").param("from",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(7).toString())
            .header("Authorization",auth(ownerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/analytics/reading-behaviors").param("from","wrong")
            .header("Authorization",auth(ownerId))).andExpect(status().isBadRequest());
        for(var path:List.of("source-statistics","timeline","time-patterns","reading-behaviors","exploration-patterns","tips"))
            mvc.perform(get("/analytics/"+path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/analytics/tips").header("Authorization",auth(ownerId))).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").isNotEmpty());
    }
}
