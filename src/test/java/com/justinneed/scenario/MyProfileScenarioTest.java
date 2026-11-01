package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 마이프로필 본인 전용 조회 (/community/me/profile · sessions · mindmaps · mindmap).
 * 공개 프로필 API 는 본인에게도 방문자 규칙이고, 여기는 비공개 세션까지 전부 보여주며 세션마다 공개 여부를 표시한다.
 */
@DisplayName("마이프로필 본인 조회")
class MyProfileScenarioTest extends ScenarioTestSupport {

    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";

    @Autowired private SessionResultRepository results;
    @Autowired private JdbcTemplate jdbc;

    private Member me;
    private Member viewer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        viewer = member("foodtrip");
    }

    private void setPublic(BrowsingSession session, boolean isPublic) throws Exception {
        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(Map.of("isPublic", isPublic))))
                .andExpect(okEnvelope());
    }

    private void pin(BrowsingSession session) throws Exception {
        mvc.perform(post("/community/me/pinned-sessions").with(as(me)).with(json(Map.of("sessionId", session.getId()))))
                .andExpect(status().isCreated());
    }

    private void hide(String hashtag) throws Exception {
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", hashtagId(me, hashtag)).with(as(me))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());
    }

    private ResultActions profile() throws Exception {
        return mvc.perform(get("/community/me/profile").with(as(me))).andExpect(okEnvelope());
    }

    private ResultActions mySessions(String query) throws Exception {
        return mvc.perform(get("/community/me/sessions" + query).with(as(me)));
    }

    /** AI 결과 저장 모양. 노드는 키워드만 정하면 되는 경우가 많아 키워드를 id 로 쓴다. */
    private void analyzed(BrowsingSession session, List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
            List<Map<String, Object>> selected) {
        results.saveAndFlush(new SessionResult(session.getId(), session.getUserId()));
        jdbc.update("update session_results set mindmap = JSON '"
                + objectMapper.writeValueAsString(Map.of("nodes", nodes, "edges", edges))
                + "', selected_units = JSON '" + objectMapper.writeValueAsString(selected)
                + "' where session_id = ?", session.getId());
    }

    private void keywords(BrowsingSession session, String... keywords) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < keywords.length; i++) {
            nodes.add(node("topic:" + i, "topic", keywords[i]));
        }
        analyzed(session, nodes, List.of(), List.of());
    }

    private static Map<String, Object> node(String id, String type, String keyword) {
        return Map.of("id", id, "type", type, "keyword", keyword);
    }

    private static Map<String, Object> edge(String from, String to, String type) {
        return Map.of("from", from, "to", to, "type", type);
    }

    private BrowsingSession tagged(String title, String startedAt, String... tags) {
        return session(me, title, startedAt, startedAt, 1, false, false, List.of(tags), List.of(), "# " + title);
    }

    // ---------- 세션 범위 ----------

    @Test
    @DisplayName("비공개 세션은 내 목록에 isPublic false 로 오고, 공개 API 로는 작성자 본인에게도 나오지 않는다")
    void privateSessionsAreOwnerOnly() throws Exception {
        MockSessions mock = seedMockSessions(me);
        setPublic(mock.rome(), true);

        mySessions("").andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.newestFirst().toArray(BrowsingSession[]::new)))))
                .andExpect(jsonPath("$.data.sessions[0].isPublic").value(true))
                .andExpect(jsonPath("$.data.sessions[1].isPublic").value(false))
                .andExpect(jsonPath("$.data.sessions[1].title").value(mock.flight().getTitle()))
                .andExpect(jsonPath("$.data.sessions[1].tags").value(contains("항공권", "스카이스캐너", "예산")))
                .andExpect(jsonPath("$.data.sessions[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.sessions[0].pageCount").value(18))
                .andExpect(jsonPath("$.data.sessions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.sessions[*].isPinned", everyItem(is(false))));
        mvc.perform(get("/community/me/mindmaps").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[1].mindmapId").value(mock.flight().getId()))
                .andExpect(jsonPath("$.data.mindmaps[1].isPublic").value(false));

        for (Member visitor : List.of(me, viewer)) {
            mvc.perform(get("/community/profiles/{id}/mindmaps", me.getId()).with(as(visitor)))
                    .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(mock.rome()))));
            mvc.perform(get("/community/mindmaps/{id}/sessions", mock.flight().getId()).with(as(visitor)))
                    .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        }
        mvc.perform(get("/community/profiles/{id}/mindmap", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.nodes[*].sourceUrl").value(not(hasItem("https://www.skyscanner.co.kr/"))));
    }

    @Test
    @DisplayName("지운 세션 · 완료되지 않은 세션 · 남의 세션은 내 세션에 들어가지 않는다")
    void onlyMyCompletedLiveSessions() throws Exception {
        BrowsingSession kept = tagged("남는 세션", "2026-04-20T10:00:00", "로마");
        BrowsingSession deleted = tagged("지운 세션", "2026-04-21T10:00:00", "로마");
        keywords(deleted, "지운 노드");
        sessionWithStatus(me, "분석 중", "2026-04-22T10:00:00", "2026-04-22T11:00:00", SessionStatus.ANALYZING, List.of("로마"));
        sessionWithStatus(me, "실패", "2026-04-23T10:00:00", "2026-04-23T11:00:00", SessionStatus.FAILED, List.of("로마"));
        session(viewer, "남의 세션", "2026-04-24T10:00:00", "2026-04-24T11:00:00", 1, false, true, List.of("로마"), List.of(), "# 남");
        mvc.perform(delete("/sessions/{id}", deleted.getId()).with(as(me))).andExpect(okEnvelope());

        mySessions("").andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(kept))));
        mvc.perform(get("/community/me/mindmaps").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(kept))));
        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].nodeCount").value(contains(0)));
        mvc.perform(get("/community/me/mindmap").with(as(me))).andExpect(jsonPath("$.data.nodes", empty()));
    }

    // ---------- 프로필 헤더 ----------

    @Test
    @DisplayName("프로필 헤더: 전체 노드 수는 홈 통계와 같고, 공유 세션 수는 공개 세션 수, 비공개 세션 노드도 포함한다")
    void headerMatchesHome() throws Exception {
        MockSessions mock = seedMockSessions(me);
        keywords(mock.rome(), "콜로세움", "바티칸");
        // 비공개 세션. 표기만 다른 '콜 로 세 움' 은 같은 노드다
        keywords(mock.paris(), "콜 로 세 움", "루브르");
        setPublic(mock.rome(), true);
        setPublic(mock.flight(), true);
        mvc.perform(patch("/community/me/profile").with(as(me)).with(json(Map.of("bio", "소개")))).andExpect(okEnvelope());
        mvc.perform(patch("/community/profiles/{id}/like", me.getId()).with(as(viewer)).with(json(Map.of("liked", true))))
                .andExpect(okEnvelope());

        Long homeTotal = readLong(mvc.perform(get("/home").with(as(me))).andExpect(okEnvelope()), "$.data.stats.totalNodes");
        assertThat(homeTotal).isEqualTo(3);

        profile().andExpect(jsonPath("$.data.userId").value(me.getId()))
                .andExpect(jsonPath("$.data.nickname").value("sprout"))
                .andExpect(jsonPath("$.data.bio").value("소개"))
                .andExpect(jsonPath("$.data.visibility").value("URL_ONLY"))
                .andExpect(jsonPath("$.data.totalNodeCount").value(homeTotal.intValue()))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(2))
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.pinnedSessions", empty()));
    }

    @Test
    @DisplayName("세션이 하나도 없어도 프로필 · 목록 · 그래프가 빈 값으로 온다")
    void emptyMember() throws Exception {
        profile().andExpect(jsonPath("$.data.totalNodeCount").value(0))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(0))
                .andExpect(jsonPath("$.data.bio").value(nullValue()))
                .andExpect(jsonPath("$.data.topHashtags", empty()))
                .andExpect(jsonPath("$.data.hashtags", empty()))
                .andExpect(jsonPath("$.data.interests", empty()));
        mySessions("").andExpect(jsonPath("$.data.sessions", empty())).andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/community/me/mindmaps").with(as(me))).andExpect(jsonPath("$.data.mindmaps", empty()));
        mvc.perform(get("/community/me/mindmap").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.code").value(200))
                .andExpect(jsonPath("$.data.nodes", empty()))
                .andExpect(jsonPath("$.data.hashtags", empty()));
    }

    @Test
    @DisplayName("상위 해시태그: 비공개 태그도 isPublic false 로 들어오고, 노드 수 많은 순 → 이름순 5개, 노드 없는 태그는 빠진다")
    void topHashtags() throws Exception {
        BrowsingSession first = tagged("첫 세션", "2026-04-20T10:00:00", "베타", "알파", "비밀", "감마", "델타", "엡실론");
        keywords(first, "하나");
        BrowsingSession second = tagged("둘째 세션", "2026-04-21T10:00:00", "비밀", "감마");
        keywords(second, "둘", "셋");
        tagged("노드 없는 세션", "2026-04-22T10:00:00", "노드없음");
        hide("비밀");

        profile().andExpect(jsonPath("$.data.topHashtags[*].hashtag").value(contains("감마", "비밀", "델타", "베타", "알파")))
                .andExpect(jsonPath("$.data.topHashtags[*].nodeCount").value(contains(3, 3, 1, 1, 1)))
                .andExpect(jsonPath("$.data.topHashtags[*].isPublic").value(contains(true, false, true, true, true)))
                .andExpect(jsonPath("$.data.topHashtags[1].hashtagId").value(hashtagId(me, "비밀")))
                .andExpect(jsonPath("$.data.hashtags", hasSize(7)))
                .andExpect(jsonPath("$.data.hashtags[?(@.hashtag == '노드없음')].nodeCount").value(contains(0)));
    }

    @Test
    @DisplayName("태그 노드 수: 키워드가 겹치면 공백 · 대소문자가 달라도 하나로 세고, 대소문자만 다른 태그는 같은 태그다")
    void tagNodeCountDeduplicates() throws Exception {
        BrowsingSession upper = tagged("대문자 태그", "2026-04-20T10:00:00", "HCI");
        keywords(upper, "Eye Tracking", "사용성", "사용성");
        BrowsingSession lower = tagged("소문자 태그", "2026-04-21T10:00:00", "hci", "기타");
        keywords(lower, "eyetracking", "EYE  TRACKING", "피츠의 법칙");
        Long id = hashtagId(me, "HCI");

        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[?(@.hashtag == 'HCI')].nodeCount").value(contains(3)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '기타')].nodeCount").value(contains(2)));
        // 공개 설정 응답의 노드 수도 같은 값이다
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", id).with(as(me)).with(json(Map.of("isPublic", false))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodeCount").value(3))
                .andExpect(jsonPath("$.data.isPublic").value(false));
        profile().andExpect(jsonPath("$.data.hashtags[?(@.hashtag == 'HCI')].nodeCount").value(contains(3)))
                .andExpect(jsonPath("$.data.hashtags[?(@.hashtag == 'HCI')].code").value(contains(200)))
                .andExpect(jsonPath("$.data.totalNodeCount").value(3));
    }

    @Test
    @DisplayName("대표 해시태그는 {interestId, hashtag} 로 오고, 해시태그 행이 없는 10자 초과 옛 관심사는 지금처럼 빠진다")
    void interests() throws Exception {
        Long europe = readLong(mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "유럽여행"))))
                .andExpect(status().isCreated()), "$.data.interestId");
        CommunityProfile row = communityProfiles.findById(me.getId()).orElseThrow();
        row.replaceInterests(List.of("유럽여행", "열한글자가넘는아주긴관심사"));
        communityProfiles.saveAndFlush(row);

        profile().andExpect(jsonPath("$.data.interests", hasSize(1)))
                .andExpect(jsonPath("$.data.interests[0].interestId").value(europe))
                .andExpect(jsonPath("$.data.interests[0].hashtag").value("유럽여행"))
                .andExpect(jsonPath("$.data.interests[0].code").doesNotExist());
    }

    // ---------- 순서 · 페이지 ----------

    @Test
    @DisplayName("고정 세션이 핀 순서로 맨 앞, 나머지는 최근 활동 최신순. 공개가 풀린 핀은 고정으로 보지 않고 제자리로 간다")
    void pinnedOrdering() throws Exception {
        MockSessions mock = seedMockSessions(me);
        // 종료 시각이 없으면 시작 시각으로 정렬한다: 로마(4.26 22:48) 와 항공권(4.25 15:21) 사이
        BrowsingSession noEnd = sessionWithStatus(me, "종료 없음", "2026-04-26T08:00:00", null, SessionStatus.COMPLETED, List.of());
        for (BrowsingSession session : List.of(mock.barca(), mock.visa(), mock.paris())) {
            setPublic(session, true);
            pin(session);
        }

        mySessions("").andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.barca(), mock.visa(), mock.paris(),
                        mock.rome(), noEnd, mock.flight(), mock.rail(), mock.prague()))))
                .andExpect(jsonPath("$.data.sessions[*].isPinned").value(contains(true, true, true, false, false, false, false, false)));
        mvc.perform(get("/community/me/mindmaps").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(mock.barca(), mock.visa(), mock.paris(),
                        mock.rome(), noEnd, mock.flight(), mock.rail(), mock.prague()))))
                .andExpect(jsonPath("$.data.mindmaps[0].isPinned").value(true))
                .andExpect(jsonPath("$.data.mindmaps[3].isPinned").value(false));
        profile().andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(mock.barca(), mock.visa(), mock.paris()))))
                .andExpect(jsonPath("$.data.pinnedSessions[*].isPinned", everyItem(is(true))))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(3));

        setPublic(mock.visa(), false);

        mySessions("").andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.barca(), mock.paris(),
                        mock.rome(), noEnd, mock.flight(), mock.rail(), mock.visa(), mock.prague()))))
                .andExpect(jsonPath("$.data.sessions[6].isPinned").value(false));
        profile().andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(mock.barca(), mock.paris()))));
    }

    @Test
    @DisplayName("publicOnly=true 면 공개 세션만 같은 순서로 온다")
    void publicOnly() throws Exception {
        MockSessions mock = seedMockSessions(me);
        setPublic(mock.rome(), true);
        setPublic(mock.rail(), true);
        setPublic(mock.visa(), true);
        pin(mock.visa());

        mySessions("?publicOnly=true").andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.visa(), mock.rome(), mock.rail()))))
                .andExpect(jsonPath("$.data.sessions[*].isPublic", everyItem(is(true))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("페이지: hasNext 는 남은 세션이 있을 때만 true 이고, 기본 크기는 12, 범위를 벗어나면 400 이다")
    void paging() throws Exception {
        MockSessions mock = seedMockSessions(me);

        mySessions("?page=0&size=3").andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.rome(), mock.flight(), mock.paris()))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mySessions("?page=2&size=3").andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.prague()))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mySessions("?page=3&size=3").andExpect(jsonPath("$.data.sessions", empty()));
        // 마지막 페이지가 꽉 차도 다음이 없다
        mySessions("?size=7").andExpect(jsonPath("$.data.hasNext").value(false));
        mySessions("?size=6").andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/community/me/mindmaps?page=1&size=5").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(mock.visa(), mock.prague()))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/community/me/mindmaps?size=2").with(as(me))).andExpect(jsonPath("$.data.hasNext").value(true));
        for (int i = 0; i < 6; i++) {
            tagged("세션 " + i, "2026-05-0" + (i + 1) + "T10:00:00");
        }
        mySessions("").andExpect(jsonPath("$.data.sessions", hasSize(12))).andExpect(jsonPath("$.data.hasNext").value(true));

        for (String wrong : List.of("?size=0", "?size=101", "?page=-1")) {
            mySessions(wrong).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "페이지 값이 올바르지 않습니다."));
            mvc.perform(get("/community/me/mindmaps" + wrong).with(as(me)))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "페이지 값이 올바르지 않습니다."));
        }
    }

    // ---------- 카드 ----------

    @Test
    @DisplayName("내 카드: URL만 공개여도 제목 · 요약 · 비공개 태그 · 링크 제목이 보이고 nodeCount 는 AI 노드 수다")
    void cardFields() throws Exception {
        List<Source> sources = new ArrayList<>();
        sources.add(new Source("  ", "https://a.example.com/0", "빈 제목"));
        sources.add(new Source("다시", "https://a.example.com/0", "같은 주소"));
        sources.add(new Source("로컬 파일", "file:///etc/hosts", "http 가 아니다"));
        for (int i = 1; i <= 12; i++) {
            sources.add(new Source("글 " + i, "https://a.example.com/" + i, "내용"));
        }
        BrowsingSession rome = session(me, "로마 동선", "2026-04-26T21:14:00", "2026-04-26T22:48:00", 18, false, false,
                List.of("로마", "비밀"), sources, ROME_MARKDOWN);
        keywords(rome, "하나", "둘", "셋");
        hide("비밀");

        mvc.perform(get("/community/me/mindmaps").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps[0].mindmapId").value(rome.getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.userId").value(me.getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("sprout"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value("로마 동선"))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value("오전 첫 타임으로 콜로세움을 예약하고 포로 로마노까지 걸어서 본다."))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags").value(contains("로마", "비밀")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(3))
                .andExpect(jsonPath("$.data.mindmaps[0].likeCount").value(0))
                .andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].lastActivityAt").value("2026-04-26T22:48:00"))
                .andExpect(jsonPath("$.data.mindmaps[0].isPublic").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].isPinned").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls", hasSize(10)))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[0].url").value("https://a.example.com/0"))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[1].title").value("글 1"))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[*].url").value(not(hasItem("file:///etc/hosts"))));
    }

    @Test
    @DisplayName("공개였다가 비공개로 돌린 세션은 카드 행이 없어도 받았던 좋아요 · 탐색중 · 북마크 숫자가 남는다")
    void countsSurviveGoingPrivate() throws Exception {
        BrowsingSession rome = session(me, "로마", "2026-04-26T21:00:00", "2026-04-26T22:00:00", 1, false, true,
                List.of("로마"), List.of(new Source("글", "https://x.example.com/y", "발췌")), "본문");
        String card = "/community/mindmaps/{id}/";
        mvc.perform(patch(card + "like", rome.getId()).with(as(viewer)).with(json(Map.of("liked", true)))).andExpect(okEnvelope());
        mvc.perform(patch(card + "exploring", rome.getId()).with(as(viewer)).with(json(Map.of("exploring", true)))).andExpect(okEnvelope());
        mvc.perform(patch(card + "bookmark", rome.getId()).with(as(viewer)).with(json(Map.of("bookmarked", true)))).andExpect(okEnvelope());
        setPublic(rome, false);

        mvc.perform(get("/community/profiles/{id}/mindmaps", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps", empty()));
        mvc.perform(get("/community/me/mindmaps").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[0].isPublic").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.mindmaps[0].likedByMe").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].exploringCount").value(1))
                .andExpect(jsonPath("$.data.mindmaps[0].bookmarkCount").value(1));
    }

    // ---------- 관련 세션 ----------

    @Test
    @DisplayName("관련 세션: 그 세션 + 태그가 겹치는 내 세션 최대 2개(최신순). 비공개 세션 · 비공개 태그 · 대소문자만 다른 태그도 겹침으로 본다")
    void relatedSessions() throws Exception {
        BrowsingSession anchor = tagged("기준", "2026-04-20T10:00:00", "비밀", "AI");
        tagged("가장 오래된", "2026-04-19T10:00:00", "비밀");
        BrowsingSession byCase = tagged("대소문자만 다름", "2026-04-22T10:00:00", "ai");
        BrowsingSession byPrivateTag = tagged("비공개 태그", "2026-04-21T10:00:00", "비밀");
        BrowsingSession unrelated = tagged("안 겹침", "2026-04-23T10:00:00", "다른 태그");
        hide("비밀");

        mvc.perform(get("/community/me/mindmaps/{id}/sessions", anchor.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(anchor, byCase, byPrivateTag))))
                .andExpect(jsonPath("$.data.sessions[0].title").value("기준"))
                .andExpect(jsonPath("$.data.sessions[0].tags").value(contains("비밀", "AI")))
                .andExpect(jsonPath("$.data.sessions[0].isPublic").value(false));
        mvc.perform(get("/community/me/mindmaps/{id}/sessions", unrelated.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(unrelated))));
    }

    @Test
    @DisplayName("관련 세션: 남의 세션 · 지운 세션 · 완료되지 않은 세션이면 404")
    void relatedSessionsNotFound() throws Exception {
        BrowsingSession others = session(viewer, "남의 공개 세션", "2026-04-20T10:00:00", "2026-04-20T11:00:00", 1, false, true,
                List.of("로마"), List.of(), "본문");
        BrowsingSession deleted = tagged("지운 세션", "2026-04-21T10:00:00", "로마");
        mvc.perform(delete("/sessions/{id}", deleted.getId()).with(as(me))).andExpect(okEnvelope());
        BrowsingSession analyzing = sessionWithStatus(me, "분석 중", "2026-04-22T10:00:00", null, SessionStatus.ANALYZING, List.of());

        for (BrowsingSession session : List.of(others, deleted, analyzing)) {
            mvc.perform(get("/community/me/mindmaps/{id}/sessions", session.getId()).with(as(me)))
                    .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        }
    }

    // ---------- 전체 마인드맵 ----------

    @Test
    @DisplayName("전체 마인드맵: 비공개 세션의 노드도 출처 · 원문과 함께 오고, hierarchy 만 잇고, 태그는 뿌리에 이으며, nodeId 는 응답 안의 번호다")
    void wholeGraph() throws Exception {
        String url = "https://x.example.com/y";
        String unit = url + "#pid1";
        BrowsingSession rome = session(me, "로마", "2026-04-26T21:00:00", "2026-04-26T22:00:00", 1, false, false,
                List.of("로마", "비밀", "열한글자가넘는아주긴태그"), List.of(new Source("글 제목", url, "발췌")), "본문");
        analyzed(rome,
                List.of(node("root:1", "root", "로마 여행"), node("topic:0", "topic", "콜로세움"), node(unit, "unit", "입장권")),
                List.of(edge("root:1", "topic:0", "hierarchy"), edge("topic:0", unit, "hierarchy"), edge("root:1", unit, "related")),
                List.of(Map.of("unitKey", unit, "pageTitle", "글 제목", "pageUrl", url, "text", "원문 문단")));
        // 다른 세션에 같은 AI 노드 id 가 있어도 번호가 겹치지 않는다
        BrowsingSession paris = session(me, "파리", "2026-04-27T21:00:00", "2026-04-27T22:00:00", 1, false, true,
                List.of("파리"), List.of(), "본문");
        analyzed(paris, List.of(node("root:1", "root", "파리 여행")), List.of(), List.of());
        tagged("노드 없는 세션", "2026-04-28T10:00:00", "노드없음");
        hide("비밀");
        Long romeTag = hashtagId(me, "로마");
        Long secretTag = hashtagId(me, "비밀");
        Long parisTag = hashtagId(me, "파리");
        Integer root = 2;
        Integer topic = 3;
        Integer leaf = 4;

        mvc.perform(get("/community/me/mindmap").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.code").value(200))
                .andExpect(jsonPath("$.data.mindmapId").value(me.getId()))
                .andExpect(jsonPath("$.data.ownerId").value(me.getId()))
                .andExpect(jsonPath("$.data.nickname").value("sprout"))
                .andExpect(jsonPath("$.data.title").value("sprout님의 전체 마인드맵"))
                .andExpect(jsonPath("$.data.topHashtags", empty()))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(containsInAnyOrder("로마", "비밀", "파리")))
                .andExpect(jsonPath("$.data.hashtags[0].ownerId").value(me.getId()))
                .andExpect(jsonPath("$.data.nodes[*].nodeId").value(contains(1, root, topic, leaf)))
                .andExpect(jsonPath("$.data.nodes[*].label").value(contains("파리 여행", "로마 여행", "콜로세움", "입장권")))
                .andExpect(jsonPath("$.data.nodes[*].visibilityScope", everyItem(is("CONTENT"))))
                .andExpect(jsonPath("$.data.nodes[3].sourceUrl").value(url))
                .andExpect(jsonPath("$.data.nodes[3].content").value("원문 문단"))
                .andExpect(jsonPath("$.data.nodes[2].sourceUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.edges", hasSize(5)))
                .andExpect(jsonPath("$.data.edges[?(@.from == 'node:2')].to").value(contains("node:3")))
                .andExpect(jsonPath("$.data.edges[?(@.from == 'node:3')].to").value(contains("node:4")))
                .andExpect(jsonPath("$.data.edges[?(@.from == 'tag:" + romeTag + "')].to").value(contains("node:2")))
                .andExpect(jsonPath("$.data.edges[?(@.from == 'tag:" + secretTag + "')].to").value(contains("node:2")))
                .andExpect(jsonPath("$.data.edges[?(@.from == 'tag:" + parisTag + "')].to").value(contains("node:1")));
    }

    // ---------- 인증 ----------

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresLogin() throws Exception {
        for (String path : List.of("/community/me/profile", "/community/me/sessions", "/community/me/mindmaps",
                "/community/me/mindmaps/1/sessions", "/community/me/mindmap")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("탈퇴한 회원의 토큰은 401")
    void withdrawn() throws Exception {
        mvc.perform(delete("/settings/account").with(as(me)).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());

        mvc.perform(get("/community/me/profile").with(as(me))).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/me/mindmap").with(as(me))).andExpect(status().isUnauthorized());
    }
}
