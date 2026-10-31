package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.SqlCapture;
import com.justinneed.community.card.CardCounts;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 카드 숫자는 한 번에 읽는다: 카드 수가 늘어도 쿼리 수가 늘지 않는다. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-card-counts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.justinneed.community.SqlCapture"
})
@DisplayName("카드 숫자")
class CommunityCardCountsScenarioTest extends ScenarioTestSupport {

    @Autowired private CardCounts counts;
    @Autowired private CommunityCardReader reader;

    private List<Long> cards(Member owner, int size) {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < size; i++) {
            BrowsingSession card = session(owner, "카드 " + i, "2026-04-20T10:00:00", "2026-04-20T11:00:00", 1, false, true,
                    List.of("묶음"), List.of(new Source("글", "https://example.com/" + i, "발췌")), "본문");
            ids.add(card.getId());
        }
        return ids;
    }

    private int countQueries(List<Long> ids, Long viewer) {
        SqlCapture.clear();
        assertThat(counts.counts(ids, viewer)).hasSize(ids.size());
        return SqlCapture.all().size();
    }

    @Test
    @DisplayName("50장을 읽어도 2장을 읽을 때와 쿼리 수가 같다. 카드마다 숫자가 따로 나온다")
    void noNPlusOne() throws Exception {
        Member owner = member("owner");
        Member viewer = member("viewer");
        var ids = cards(owner, 50);
        for (Long id : ids) {
            mvc.perform(patch("/community/mindmaps/{id}/like", id).with(as(viewer)).with(json(Map.of("liked", true))));
            mvc.perform(patch("/community/mindmaps/{id}/exploring", id).with(as(viewer)).with(json(Map.of("exploring", true))));
            mvc.perform(patch("/community/mindmaps/{id}/bookmark", id).with(as(viewer)).with(json(Map.of("bookmarked", true))));
        }

        int few = countQueries(ids.subList(0, 2), viewer.getId());
        int many = countQueries(ids, viewer.getId());

        assertThat(many).isEqualTo(few).isLessThanOrEqualTo(6);
        var found = counts.counts(ids, viewer.getId());
        assertThat(found.values()).allSatisfy(count -> {
            assertThat(count.likeCount()).isEqualTo(1);
            assertThat(count.likedByMe()).isTrue();
            assertThat(count.exploringCount()).isEqualTo(1);
            assertThat(count.exploringByMe()).isTrue();
            assertThat(count.bookmarkCount()).isEqualTo(1);
            assertThat(count.bookmarkId()).isNotNull();
        });
        assertThat(found.values().stream().map(CardCounts.Count::bookmarkId).distinct()).hasSize(50);
    }

    @Test
    @DisplayName("보는 사람이 없어도 숫자는 나오고, 빈 목록이면 쿼리를 보내지 않는다")
    void noViewer() throws Exception {
        Member owner = member("owner");
        Member viewer = member("viewer");
        var ids = cards(owner, 1);
        mvc.perform(patch("/community/mindmaps/{id}/like", ids.get(0)).with(as(viewer)).with(json(Map.of("liked", true))));

        assertThat(counts.counts(ids, null).get(ids.get(0)).likeCount()).isEqualTo(1);
        assertThat(counts.counts(ids, null).get(ids.get(0)).likedByMe()).isFalse();
        assertThat(reader.card(ids.get(0), null).orElseThrow().likeCount()).isEqualTo(1);

        SqlCapture.clear();
        assertThat(counts.counts(List.of(), viewer.getId())).isEmpty();
        assertThat(SqlCapture.all()).isEmpty();
    }
}
