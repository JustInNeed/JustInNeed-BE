package com.justinneed.community;

import static org.assertj.core.api.Assertions.*;
import com.justinneed.auth.domain.*;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.reaction.*;
import com.justinneed.community.recommend.TrendingService;
import com.justinneed.session.management.domain.*;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:community_committed;MODE=PostgreSQL;LOCK_TIMEOUT=10000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CommunityCommitTest {
    @Autowired MemberRepository members;
    @Autowired BrowsingSessionRepository sessions;
    @Autowired PublicGraphService graphs;
    @Autowired BookmarkCommandService bookmarks;
    @Autowired BookmarkQueryService queries;
    @Autowired CommunityBookmarkRepository bookmarkRows;
    @Autowired InteractionService interactions;
    @Autowired TrendingService trending;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @Test
    void simultaneousWritesAreIdempotentAndSnapshotRoundTripsAcrossTransactions() throws Exception {
        var owner = member();
        var actor = member();
        var s = session(owner);
        var graph = graphs.graph(owner, null);
        var node = graph.nodes().get(0).nodeId();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Long> save = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                interactions.like(actor, owner, true);
                interactions.exploring(actor, TargetType.NODE, node, true);
                return bookmarks.save(actor, TargetType.NODE, List.of(node), SaveType.MINI_MINDMAP, owner).bookmarkId();
            };
            var first = pool.submit(save);
            var second = pool.submit(save);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Long id = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(graphs.profile(owner, actor).likeCount()).isEqualTo(1);
            assertThat(interactions.status(actor, TargetType.NODE, node).exploringCount()).isEqualTo(1);
            var snapshot = queries.mindmaps(actor, 0, 10).mindmaps().get(0);
            assertThat(snapshot.nodes()).hasSize(1);
            assertThat(snapshot.hashtags()).hasSize(1);
            assertThat(snapshot.edges()).hasSize(1);
            assertThat(snapshot.nodes().get(0).content()).isNull();

            new TransactionTemplate(manager).executeWithoutResult(status -> sessions.findById(s).orElseThrow()
                    .update(null, null, false, null, null, null));
            assertThat(interactions.exploring(actor, TargetType.NODE, node, false).exploring()).isFalse();
            assertThat(graphs.original(actor, id).highlightedNodeIds()).isEmpty();
            assertThat(queries.mindmaps(actor, 0, 10).mindmaps().get(0)).isEqualTo(snapshot);
            assertThat(trending.trending(TrendingService.Period.TODAY, 10).hashtags()).isEmpty();
            bookmarks.delete(actor, id);
            assertThat(bookmarkRows.findById(id)).isEmpty();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void oppositeDirectionBookmarksAcquireLocksWithoutDeadlocking() throws Exception {
        var left = member();
        var right = member();
        session(left);
        session(right);
        var leftNode = graphs.graph(left, null).nodes().get(0).nodeId();
        var rightNode = graphs.graph(right, null).nodes().get(0).nodeId();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                start.await();
                return bookmarks.save(left, TargetType.NODE, List.of(rightNode), SaveType.MINI_MINDMAP, right);
            });
            var b = pool.submit(() -> {
                start.await();
                return bookmarks.save(right, TargetType.NODE, List.of(leftNode), SaveType.MINI_MINDMAP, left);
            });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).bookmarkId()).isNotNull();
            assertThat(b.get(20, TimeUnit.SECONDS).bookmarkId()).isNotNull();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void removedOwnerDoesNotBreakTrending() {
        var owner = member();
        var actor = member();
        session(owner);
        var node = graphs.graph(owner, null).nodes().get(0).nodeId();
        interactions.exploring(actor, TargetType.NODE, node, true);
        members.deleteById(owner);
        assertThatCode(() -> trending.trending(TrendingService.Period.TODAY, 10)).doesNotThrowAnyException();
    }

    private Long member() {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, UUID.randomUUID().toString(), "Test", null)).getId();
    }
    private Long session(Long owner) {
        var s = new BrowsingSession(owner, "private", LocalDateTime.now());
        s.update(null, null, true, null, List.of("Java"), null);
        s.replaceSources(List.of(new Source("private", "https://example.com", "private")));
        return sessions.saveAndFlush(s).getId();
    }
}
