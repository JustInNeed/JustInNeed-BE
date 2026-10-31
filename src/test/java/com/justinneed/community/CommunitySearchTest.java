package com.justinneed.community;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

class CommunitySearchTest extends CommunityTestSupport {
    private static final String MINDMAPS = "/community/search/mindmaps";
    private static final String PROFILES = "/community/search/profiles";
    private static final String HASHTAGS = "/community/search/hashtags";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 12, 0);

    private ResultActions search(String path, String q, String... params) throws Exception {
        var request = get(path).param("q", q).header("Authorization", auth(viewerId));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private BrowsingSession card(Long owner, String title, int minutes, String... tags) {
        LocalDateTime at = BASE.plusMinutes(minutes);
        BrowsingSession session = new BrowsingSession(owner, title, at.minusMinutes(5));
        session.complete(at, 1);
        session.update(null, null, true, false, List.of(tags));
        session.replaceSources(List.of(new Source("글 제목", "https://example.com/" + title, "발췌")));
        new Summary(session, "본문");
        BrowsingSession saved = sessions.saveAndFlush(session);
        cardSync.sync(saved);
        return saved;
    }

    private void visibility(Long owner, NodeVisibility visibility, String... hidden) {
        CommunityProfile profile = profiles.findById(owner).orElseGet(() -> new CommunityProfile(owner));
        profile.changeVisibility(visibility);
        profile.replaceHiddenHashtags(List.of(hidden));
        profiles.saveAndFlush(profile);
    }

    private Long member(String providerId, String nickname) {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, providerId, nickname, null)).getId();
    }

    private void profileOrder(String q, Long... ids) throws Exception {
        search(PROFILES, q, "size", "100").andExpect(jsonPath("$.data.profiles[*].userId")
                .value(contains(Arrays.stream(ids).map(Long::intValue).toArray(Integer[]::new))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ava", "JAVA", "#java", "  #Spr  ", "#  spr"})
    void mindmapsMatchPartOfATagIgnoringCaseAndDecoration(String q) throws Exception {
        var session = card(ownerId, "제목", 0, "JavaSpring");
        search(MINDMAPS, q)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(session.getId().intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "a%c", "\"", "[", ","})
    void searchTextIsNotAPatternOrJsonSyntax(String q) throws Exception {
        card(ownerId, "abc", 0, "abc");
        card(ownerId, "def", 1, "abc", "def");
        search(MINDMAPS, q).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        search(PROFILES, q).andExpect(jsonPath("$.data.profiles", hasSize(0)));
        search(HASHTAGS, q).andExpect(jsonPath("$.data.hashtags", hasSize(0)));
    }

    @Test
    void mindmapsMatchNicknameAndOnlyContentOwnersTitle() throws Exception {
        var urlOnly = card(ownerId, "비밀스런 제목", 0, "태그");
        Long contentOwner = member("content", "민수");
        visibility(contentOwner, NodeVisibility.CONTENT);
        var content = card(contentOwner, "공개된 제목", 1, "태그");

        search(MINDMAPS, "공개된").andExpect(jsonPath("$.data.mindmaps[*].mindmapId")
                .value(contains(content.getId().intValue())));
        search(MINDMAPS, "비밀스런").andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        search(MINDMAPS, "OWN").andExpect(jsonPath("$.data.mindmaps[*].mindmapId")
                .value(contains(urlOnly.getId().intValue())))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()));
        search(MINDMAPS, "민").andExpect(jsonPath("$.data.mindmaps[*].mindmapId")
                .value(contains(content.getId().intValue())));
    }

    @Test
    void hiddenTagsNeverMatchButTheCardStaysUnderItsOtherTags() throws Exception {
        var session = card(ownerId, "제목", 0, "Java", "Secret");
        visibility(ownerId, NodeVisibility.URL_ONLY, "secret");

        search(MINDMAPS, "secr").andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        search(PROFILES, "secr").andExpect(jsonPath("$.data.profiles", hasSize(0)));
        search(HASHTAGS, "secr").andExpect(jsonPath("$.data.hashtags", hasSize(0)));
        search(MINDMAPS, "java")
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(session.getId().intValue())))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags").value(contains("Java")));
    }

    @Test
    void privateAndDeletedSessionsAreNotCards() throws Exception {
        session(ownerId, false, "Private");
        var deleted = session(ownerId, true, "Deleted");
        deleted.delete();
        cardSync.sync(deleted);

        for (String q : List.of("Private", "Deleted")) {
            search(MINDMAPS, q).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
            search(PROFILES, q).andExpect(jsonPath("$.data.profiles", hasSize(0)));
            search(HASHTAGS, q).andExpect(jsonPath("$.data.hashtags", hasSize(0)));
        }
    }

    @Test
    void withdrawnOwnersAreLeftOutEvenIfTheirCardRowsRemain() throws Exception {
        var gone = members.findById(ownerId).orElseThrow();
        card(ownerId, "a", 0, "태그");
        gone.withdraw("이유", LocalDateTime.now());
        members.saveAndFlush(gone);

        for (String q : List.of("태그", "a")) {
            search(MINDMAPS, q).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
            search(PROFILES, q).andExpect(jsonPath("$.data.profiles", hasSize(0)));
            search(HASHTAGS, q).andExpect(jsonPath("$.data.hashtags", hasSize(0)));
        }
    }

    @Test
    void withdrawnOwnersDoNotTakeAPageSlot() throws Exception {
        var kept = card(viewerId, "a", 0, "태그");
        card(ownerId, "b", 1, "태그");
        var gone = members.findById(ownerId).orElseThrow();
        gone.withdraw("이유", LocalDateTime.now());
        members.saveAndFlush(gone);

        search(MINDMAPS, "태그", "size", "1")
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(kept.getId().intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void mindmapsComeByRecencyThenNewestIdAndIncludeMyOwn() throws Exception {
        var older = card(ownerId, "a", 0, "태그");
        var mine = card(viewerId, "b", 5, "태그");
        var tieLow = card(ownerId, "c", 3, "태그");
        var tieHigh = card(ownerId, "d", 3, "태그");

        search(MINDMAPS, "태그").andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(
                mine.getId().intValue(), tieHigh.getId().intValue(), tieLow.getId().intValue(), older.getId().intValue())));
    }

    @Test
    void pagingCountsOnlyRealMatches() throws Exception {
        visibility(ownerId, NodeVisibility.URL_ONLY, "숨김태그");
        var first = card(viewerId, "a", 9, "숨김태그");
        var second = card(viewerId, "b", 8, "숨김태그");
        // 글자로는 걸리지만 비공개라 세면 안 되는 카드가 사이사이 낀다
        card(ownerId, "x", 7, "숨김태그");
        var third = card(viewerId, "c", 6, "숨김태그");
        card(ownerId, "y", 5, "숨김태그");
        var fourth = card(viewerId, "d", 4, "숨김태그");
        card(ownerId, "z", 3, "숨김태그");

        search(MINDMAPS, "숨김", "size", "2")
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(
                        first.getId().intValue(), second.getId().intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        search(MINDMAPS, "숨김", "size", "2", "page", "1")
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(
                        third.getId().intValue(), fourth.getId().intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        search(MINDMAPS, "숨김", "size", "2", "page", "2")
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        search(MINDMAPS, "숨김", "size", "100", "page", String.valueOf(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    void profilesShowPublicTopTagsAndCardCount() throws Exception {
        card(ownerId, "a", 0, "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "숨김");
        card(ownerId, "b", 1, "일곱", "여섯", "다섯");
        card(ownerId, "c", 2, "일곱", "여섯", "숨김");
        visibility(ownerId, NodeVisibility.URL_ONLY, "숨김");

        search(PROFILES, "일곱")
                .andExpect(jsonPath("$.data.profiles", hasSize(1)))
                .andExpect(jsonPath("$.data.profiles[0].userId").value(ownerId))
                .andExpect(jsonPath("$.data.profiles[0].nickname").value("Owner"))
                .andExpect(jsonPath("$.data.profiles[0].mindmapCount").value(3))
                // 많은 순 → 같으면 이름 순
                .andExpect(jsonPath("$.data.profiles[0].hashtags").value(contains("여섯", "일곱", "다섯", "넷", "둘")))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void profilesNeedACardAndMatchTagsOrNicknameButNotTitles() throws Exception {
        Long contentOwner = member("content", "민수");
        visibility(contentOwner, NodeVisibility.CONTENT);
        card(contentOwner, "제목글자", 0, "태그");
        // 카드 없는 회원: 닉네임 · 관심사가 맞아도 안 나온다
        member("cardless", "민수2");
        var interested = new CommunityProfile(viewerId);
        interested.replaceInterests(List.of("태그"));
        profiles.saveAndFlush(interested);

        search(PROFILES, "민수").andExpect(jsonPath("$.data.profiles[*].userId").value(contains(contentOwner.intValue())));
        search(PROFILES, "태").andExpect(jsonPath("$.data.profiles[*].userId").value(contains(contentOwner.intValue())));
        search(PROFILES, "제목글자").andExpect(jsonPath("$.data.profiles", hasSize(0)));
    }

    @Test
    void profilesPageAndIncludeMe() throws Exception {
        card(ownerId, "a", 0, "태그");
        card(ownerId, "b", 1, "태그");
        card(viewerId, "c", 2, "태그");

        search(PROFILES, "태그", "size", "1")
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(ownerId.intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        search(PROFILES, "태그", "size", "1", "page", "1")
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(viewerId.intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void profilesWithMoreMatchingCardsComeFirstEvenIfOlder() throws Exception {
        card(ownerId, "a", 100, "태그");
        card(viewerId, "b", 0, "태그");
        card(viewerId, "c", 1, "태그");

        profileOrder("태그", viewerId, ownerId);
    }

    @Test
    void sameCardCountComesByMostRecentMatchingCard() throws Exception {
        card(ownerId, "a", 0, "태그");
        card(ownerId, "b", 5, "태그");
        card(viewerId, "c", 1, "태그");
        card(viewerId, "d", 9, "태그");

        profileOrder("태그", viewerId, ownerId);
    }

    @Test
    void sameCountAndRecencyComeByIdAscending() throws Exception {
        // 카드는 id 큰 회원이 먼저 만든다. 읽은 순서가 아니라 id 로 갈려야 한다
        card(viewerId, "a", 0, "태그");
        card(ownerId, "b", 0, "태그");

        profileOrder("태그", ownerId, viewerId);
    }

    @Test
    void recencyOnlyLooksAtMatchingCards() throws Exception {
        card(ownerId, "a", 0, "태그");
        card(ownerId, "b", 100, "다른글");
        card(viewerId, "c", 5, "태그");

        profileOrder("태그", viewerId, ownerId);
    }

    @Test
    void nicknameMatchDoesNotMakeEveryCardMatch() throws Exception {
        Long nickname = member("nick", "여행가");
        card(nickname, "a", 0, "여행");
        card(nickname, "b", 100, "다른글");
        card(nickname, "c", 101, "다른글");
        Long tagged = member("tagged", "민수");
        card(tagged, "d", 5, "여행");

        // 닉네임 회원의 카드 3장 중 걸린 건 1장, 그 카드의 활동은 민수보다 오래됐다
        profileOrder("여행", tagged, nickname);
    }

    @Test
    void nicknameOnlyMatchesComeAfterCardMatchesByTheirRecentActivity() throws Exception {
        Long older = member("older", "여행가A");
        card(older, "a", 50, "다른글");
        Long newer = member("newer", "여행가B");
        card(newer, "b", 40, "다른글");
        card(newer, "c", 100, "다른글");
        Long tied = member("tied", "여행가C");
        card(tied, "d", 100, "다른글");
        Long tagged = member("tagged", "민수");
        card(tagged, "e", 0, "여행");

        profileOrder("여행", tagged, newer, tied, older);
    }

    @Test
    void contentTitlesCountAsMatchingCardsButUrlOnlyTitlesDoNot() throws Exception {
        Long urlOnly = member("url-only", "가");
        card(urlOnly, "여행 일기", 90, "다른글");
        card(urlOnly, "b", 100, "여행");
        Long content = member("content", "나");
        visibility(content, NodeVisibility.CONTENT);
        card(content, "여행 일기", 1, "다른글");
        card(content, "d", 0, "여행");
        Long single = member("single", "다");
        card(single, "e", 50, "여행");

        // 내용 공개 회원은 제목으로 걸린 카드까지 2장. URL만 공개한 회원의 제목은 세지 않는다
        profileOrder("여행", content, urlOnly, single);
    }

    @Test
    void contentTitleOnNicknameMatchIsAMatchingCard() throws Exception {
        Long recent = member("recent", "여행자");
        card(recent, "b", 100, "다른글");
        Long nickname = member("nick", "여행가");
        visibility(nickname, NodeVisibility.CONTENT);
        card(nickname, "여행 일기", 0, "다른글");

        profileOrder("여행", nickname, recent);
    }

    @Test
    void urlOnlyTitleOnNicknameMatchIsNotAMatchingCard() throws Exception {
        Long recent = member("recent", "여행자");
        card(recent, "b", 100, "다른글");
        Long nickname = member("nick", "여행가");
        card(nickname, "여행 일기", 0, "다른글");

        // 둘 다 닉네임으로만 걸려 0장이다. 공개하지 않은 제목은 순서로도 드러나면 안 된다
        profileOrder("여행", recent, nickname);
    }

    @Test
    void titleOnlyMatchesStayOutAndTakeNoPageSlot() throws Exception {
        Long titled = member("titled", "가");
        visibility(titled, NodeVisibility.CONTENT);
        card(titled, "여행 일기", 100, "다른글");
        card(titled, "여행 일기2", 101, "다른글");
        Long tagged = member("tagged", "나");
        card(tagged, "b", 0, "여행");

        search(PROFILES, "여행", "size", "1")
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(tagged.intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void hiddenTagsNeitherCountNorRefreshRecency() throws Exception {
        visibility(ownerId, NodeVisibility.URL_ONLY, "숨김여행");
        card(ownerId, "a", 0, "여행");
        card(ownerId, "b", 100, "숨김여행");
        card(ownerId, "c", 101, "숨김여행");
        card(viewerId, "d", 5, "여행");

        profileOrder("여행", viewerId, ownerId);
    }

    @Test
    void aCardWithManyMatchingTagsCountsOnce() throws Exception {
        visibility(ownerId, NodeVisibility.CONTENT);
        card(ownerId, "여행", 0, "여행", "여행사진", "국내여행");
        card(viewerId, "b", 5, "여행");

        profileOrder("여행", viewerId, ownerId);
    }

    @Test
    void matchingCardsAreTheOnesMindmapSearchReturns() throws Exception {
        visibility(ownerId, NodeVisibility.CONTENT, "숨김여행");
        card(ownerId, "a", 0, "여행");
        card(ownerId, "여행 일기", 1, "다른글");
        card(ownerId, "c", 2, "숨김여행");
        card(ownerId, "d", 3, "다른글");
        card(viewerId, "여행 일기", 4, "다른글");
        card(viewerId, "f", 5, "여행");
        Long third = member("third", "가");
        card(third, "g", 0, "여행");
        card(third, "h", 1, "여행");
        card(third, "i", 2, "여행");

        // 카드 검색 결과 2 + 1 + 3 장이 그대로 프로필 순서가 된다
        search(MINDMAPS, "여행", "size", "100").andExpect(jsonPath("$.data.mindmaps", hasSize(6)));
        profileOrder("여행", third, ownerId, viewerId);
    }

    @Test
    void orderCoversCardsBeyondTheFirstReadBatch() throws Exception {
        // 200장씩 읽어 카드 수를 세면 첫 묶음에서는 100 대 100, 활동은 ownerId 가 앞선다
        for (int i = 0; i < 100; i++) {
            card(viewerId, "v" + i, i, "태그");
            card(ownerId, "o" + i, 1000 + i, "태그");
        }
        card(viewerId, "last", 0, "태그");

        profileOrder("태그", viewerId, ownerId);
    }

    @Test
    void profilePagesFollowTheRankingAndHasNextIsAccurate() throws Exception {
        Long nickname = member("nick", "여행가");
        card(nickname, "a", 0, "다른글");
        Long hidden = member("hidden", "가");
        visibility(hidden, NodeVisibility.URL_ONLY, "여행");
        card(hidden, "b", 100, "여행");
        Long one = member("one", "나");
        card(one, "c", 50, "여행");
        Long two = member("two", "다");
        card(two, "d", 1, "여행");
        card(two, "e", 2, "여행");
        Long three = member("three", "라");
        card(three, "f", 1, "여행");
        card(three, "g", 2, "여행");
        card(three, "h", 3, "여행");

        search(PROFILES, "여행", "size", "2")
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(three.intValue(), two.intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        search(PROFILES, "여행", "size", "2", "page", "1")
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(one.intValue(), nickname.intValue())))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        search(PROFILES, "여행", "size", "2", "page", "2")
                .andExpect(jsonPath("$.data.profiles", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        search(PROFILES, "여행", "size", "3")
                .andExpect(jsonPath("$.data.hasNext").value(true));
        search(PROFILES, "여행", "size", "4")
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void hashtagsCountCardsAcrossPeople() throws Exception {
        card(ownerId, "a", 0, "Java", "JavaSpring", "숨김자바");
        card(ownerId, "b", 1, "Java");
        card(viewerId, "c", 2, "java", "JavaBean");
        card(viewerId, "d", 3, "JavaBean");
        card(viewerId, "e", 4, "Kotlin");
        visibility(ownerId, NodeVisibility.URL_ONLY, "숨김자바");

        search(HASHTAGS, "#JAVA")
                .andExpect(jsonPath("$.data.hashtags[*].tag").value(contains("Java", "JavaBean", "JavaSpring")))
                .andExpect(jsonPath("$.data.hashtags[*].count").value(contains(3, 2, 1)));
        search(HASHTAGS, "자바").andExpect(jsonPath("$.data.hashtags", hasSize(0)));
    }

    @Test
    void hashtagsCountACardOnceEvenIfItRepeatsATag() throws Exception {
        card(ownerId, "a", 0, "Dup", "dup");

        search(HASHTAGS, "dup").andExpect(jsonPath("$.data.hashtags[*].count").value(contains(1)));
    }

    @Test
    void hashtagsBreakTiesByNameAndHonourLimit() throws Exception {
        for (int i = 0; i < 22; i++) {
            card(ownerId, "t" + i, i, "태그" + (char) ('a' + i));
        }
        search(HASHTAGS, "태그")
                .andExpect(jsonPath("$.data.hashtags[*].tag").value(contains("태그a", "태그b", "태그c", "태그d", "태그e")));
        search(HASHTAGS, "태그", "limit", "20").andExpect(jsonPath("$.data.hashtags", hasSize(20)));
        search(HASHTAGS, "태그", "limit", "21").andExpect(status().isBadRequest());
        search(HASHTAGS, "태그", "limit", "0").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "#", " # ", "\u0000"})
    void blankSearchIsKorean400(String q) throws Exception {
        for (String path : List.of(MINDMAPS, PROFILES, HASHTAGS)) {
            search(path, q).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("검색어를 입력해 주세요."));
        }
    }

    @Test
    void searchRequiresAuthenticationAndValidPaging() throws Exception {
        for (String path : List.of(MINDMAPS, PROFILES, HASHTAGS)) {
            mvc.perform(get(path).param("q", "Java")).andExpect(status().isUnauthorized());
        }
        search(PROFILES, "Java", "page", "-1").andExpect(status().isBadRequest());
        search(MINDMAPS, "Java", "size", "101").andExpect(status().isBadRequest());
    }
}
