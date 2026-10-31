package com.justinneed.scenario;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.card.CardCounts;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 카드 숫자의 실제 구현과 상관없이, 프로필 숫자가 작성자 카드의 합인지 본다. */
@DisplayName("프로필 나도 탐색중 수")
class ProfileExploringCountScenarioTest extends ScenarioTestSupport {

    @MockitoBean private CardCounts counts;

    @Test
    @DisplayName("작성자 공개 카드 전체의 나도 탐색중 수를 더한다")
    void sumsExploringCountOfOwnerCards() throws Exception {
        Member me = member("sprout");
        Wanderer wanderer = seedWanderer();
        given(counts.counts(argThat(CardIds::hasFourCards), eq(me.getId()))).willReturn(Map.of(
                wanderer.prague().getId(), new CardCounts.Count(9, false, 9, null, 3, true),
                wanderer.vienna().getId(), new CardCounts.Count(9, false, 9, null, 2, false)));

        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploringCount").value(5))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    private static final class CardIds {
        private static boolean hasFourCards(Collection<Long> ids) {
            return ids.size() == 4;
        }
    }
}
