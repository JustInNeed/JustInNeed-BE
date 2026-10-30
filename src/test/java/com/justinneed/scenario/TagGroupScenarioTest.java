package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 세션 목록 화면의 해시태그 그룹 보기 (REC-10-2, REC-7-4, REC-7-5).
 * FE: SessionsView.saveGroup — 만들 때 이름을 앞 3개 태그로 지어 보내고, 수정할 땐 hashtags 만 보낸다. GroupEditor 최대 10개.
 * 시드 그룹은 FE 목 GRAPH_TAG_GROUPS 와 같다.
 */
@DisplayName("해시태그 그룹 화면")
class TagGroupScenarioTest extends ScenarioTestSupport {

    private static final String INVALID_ORDER = "정렬할 그룹 목록이 올바르지 않습니다.";
    private static final String GROUP_NOT_FOUND = "해시태그 그룹을 찾을 수 없습니다.";

    private static final String ROME = "로마 3박 4일 동선 짜기";
    private static final String FLIGHT = "유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)";
    private static final String PARIS = "파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)";

    @Autowired
    private TagGroupRepository tagGroups;

    private TagGroup group(Member owner, String name, List<String> hashtags, int position) {
        return tagGroups.saveAndFlush(new TagGroup(owner.getId(), name, hashtags, position));
    }

    @Test
    @DisplayName("그룹 보기: 그룹은 position 순, 그룹마다 해시태그가 하나라도 겹치는 세션이 최신순으로 묶여 온다")
    void groupsWithMatchingSessions() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        group(me, "React 학습", List.of("React", "TypeScript", "훅", "상태관리"), 1);
        group(me, "유럽 여행 준비", List.of("로마", "파리", "콜로세움", "항공권", "숙소"), 0);

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("유럽 여행 준비"))
                .andExpect(jsonPath("$.data[0].position").value(0))
                .andExpect(jsonPath("$.data[0].hashtags").value(contains("로마", "파리", "콜로세움", "항공권", "숙소")))
                .andExpect(jsonPath("$.data[0].sessions[*].title").value(contains(ROME, FLIGHT, PARIS)))
                // 그룹 안 세션도 목록 카드와 같은 필드
                .andExpect(jsonPath("$.data[0].sessions[0].pageCount").value(18))
                .andExpect(jsonPath("$.data[0].sessions[0].tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")))
                .andExpect(jsonPath("$.data[1].name").value("React 학습"))
                .andExpect(jsonPath("$.data[1].sessions", hasSize(0)));
    }

    @Test
    @DisplayName("그룹 안 세션도 목록과 같은 순서: 진행 중 세션이 맨 위, 나머지는 실패 세션을 포함해 종료 시각 최신순, 종료 시각이 없으면 맨 뒤 — 조회 · 생성 · 수정 · 순서 변경 응답 모두")
    void groupSessionsFollowListOrder() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        sessionWithStatus(me, "로마 근교 당일치기", "2026-04-10T09:00:00", null, SessionStatus.ANALYZING, List.of("로마"));
        // 로마(04-26 22:48 종료)와 항공권(04-25 15:21 종료) 사이
        sessionWithStatus(me, "파리 숙소 비교", "2026-04-25T20:00:00", "2026-04-25T21:00:00", SessionStatus.FAILED,
                List.of("파리"));
        sessionWithStatus(me, "항공권 마일리지", "2026-04-27T09:00:00", null, SessionStatus.FAILED, List.of("항공권"));
        TagGroup europe = group(me, "유럽", List.of("로마", "파리", "항공권"), 0);
        String[] expected = {"로마 근교 당일치기", ROME, "파리 숙소 비교", FLIGHT, PARIS, "항공권 마일리지"};

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].sessions[*].title").value(contains(expected)));
        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me))
                        .with(json(Map.of("hashtags", List.of("항공권", "파리", "로마")))))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains(expected)));
        Long copyId = readLong(mvc.perform(post("/tag-groups").with(as(me))
                        .with(json(Map.of("name", "유럽 사본", "hashtags", List.of("로마", "파리", "항공권")))))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains(expected))), "$.data.id");
        mvc.perform(patch("/tag-groups/order").with(as(me))
                        .with(json(Map.of("groupIds", List.of(copyId, europe.getId())))))
                .andExpect(jsonPath("$.data[0].sessions[*].title").value(contains(expected)))
                .andExpect(jsonPath("$.data[1].sessions[*].title").value(contains(expected)));
    }

    @Test
    @DisplayName("그룹 만들기 해시태그 후보(GET /sessions/hashtags): 내 세션 전체 태그를 목록 순서대로 처음 나온 순서로, 대소문자 무시 중복 없이 준다")
    void hashtagCandidatesFollowListOrder() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        // 진행 중 세션이 목록 맨 위라 이 세션 태그가 먼저 나오고, 표기도 처음 나온 'etias' 가 남는다 (비자 세션의 'ETIAS' 는 빠짐)
        sessionWithStatus(me, "분석 중", "2026-04-01T09:00:00", null, SessionStatus.ANALYZING, List.of("etias", "Spring"));

        mvc.perform(get("/sessions/hashtags").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(contains(
                        "etias", "Spring",
                        "로마", "콜로세움", "바티칸", "먹거리",
                        "항공권", "스카이스캐너", "예산",
                        "파리", "미술관", "루브르", "오르세",
                        // 유레일 세션의 '예산' 은 이미 나왔으므로 빠진다
                        "유레일", "기차",
                        "바르셀로나", "가우디", "사그라다파밀리아",
                        "비자", "쉥겐",
                        "프라하", "카를교", "체코")));
    }

    @Test
    @DisplayName("해시태그 후보에 삭제한 세션 · 다른 사람 세션의 태그는 안 나온다")
    void hashtagCandidatesExcludeDeletedAndForeign() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        session(member("wanderer"), "동유럽 3주 살이", "2026-04-27T09:00:00", "2026-04-27T10:00:00", 30, false, true,
                List.of("동유럽", "프라하"), List.of(), null);
        mvc.perform(delete("/sessions/{id}", mock.paris().getId()).with(as(me))).andExpect(okEnvelope());

        mvc.perform(get("/sessions/hashtags").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data", hasSize(18)))
                .andExpect(jsonPath("$.data").value(not(hasItem("파리"))))
                .andExpect(jsonPath("$.data").value(not(hasItem("루브르"))))
                .andExpect(jsonPath("$.data").value(not(hasItem("동유럽"))))
                .andExpect(jsonPath("$.data").value(hasItem("프라하")));
    }

    @Test
    @DisplayName("세션이 없으면 해시태그 후보는 빈 배열 — /sessions/{id} 로 잡혀 400 이 나지 않는다")
    void hashtagCandidatesEmpty() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/sessions/hashtags").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("로그인하지 않고 해시태그 후보를 부르면 401")
    void hashtagCandidatesRequireLogin() throws Exception {
        mvc.perform(get("/sessions/hashtags")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("그룹 해시태그는 대소문자를 무시하고 세션 태그와 맞춘다 (etias → ETIAS)")
    void matchingIgnoresCase() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        group(me, "입국 서류", List.of("etias"), 0);

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].sessions[*].title").value(contains("쉥겐 비자 & 입국 서류")));
    }

    @Test
    @DisplayName("그룹 만들기(REC-7-5): FE 처럼 이름을 앞 3개 태그로 지어 보내면 맨 뒤 position 으로 생기고 매칭 세션이 함께 온다")
    void createGroupLikeFe() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        group(me, "React 학습", List.of("React"), 0);

        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of(
                        "name", "로마, 파리, 항공권",
                        "hashtags", List.of("로마", "파리", "항공권")))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("로마, 파리, 항공권"))
                .andExpect(jsonPath("$.data.hashtags").value(contains("로마", "파리", "항공권")))
                .andExpect(jsonPath("$.data.position").value(1))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains(ROME, FLIGHT, PARIS)));
    }

    @Test
    @DisplayName("그룹 수정: hashtags 만 보내면(REC-7-4 드래그 순서) 그 순서로 저장되고 이름은 그대로다")
    void reorderHashtagsInsideGroup() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        TagGroup europe = group(me, "로마, 파리, 항공권", List.of("로마", "파리", "항공권"), 0);

        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me))
                        .with(json(Map.of("hashtags", List.of("항공권", "로마", "파리")))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.name").value("로마, 파리, 항공권"))
                .andExpect(jsonPath("$.data.hashtags").value(contains("항공권", "로마", "파리")))
                .andExpect(jsonPath("$.data.position").value(0));

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].hashtags").value(contains("항공권", "로마", "파리")));
    }

    @Test
    @DisplayName("그룹에서 해시태그를 빼면 그 태그로만 묶였던 세션도 빠진다")
    void removingHashtagShrinksSessions() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        TagGroup europe = group(me, "유럽", List.of("로마", "파리", "항공권"), 0);

        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me)).with(json(Map.of("hashtags", List.of("로마")))))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains(ROME)));
    }

    @Test
    @DisplayName("세션 상세에서 태그를 지우거나 세션을 삭제하면 그룹 보기에서도 빠진다")
    void groupFollowsSessionChanges() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        group(me, "유럽", List.of("로마", "파리"), 0);

        mvc.perform(patch("/sessions/{id}", mock.rome().getId()).with(as(me)).with(json(Map.of("tags", List.of("먹거리")))))
                .andExpect(okEnvelope());
        mvc.perform(delete("/sessions/{id}", mock.paris().getId()).with(as(me))).andExpect(okEnvelope());

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].sessions", hasSize(0)));
    }

    @Test
    @DisplayName("그룹을 지우면(확인 팝업 후) 남은 그룹 position 이 0 부터 다시 매겨진다")
    void deleteGroupCompactsPositions() throws Exception {
        Member me = member("sprout");
        TagGroup first = group(me, "유럽", List.of("로마"), 0);
        TagGroup middle = group(me, "React", List.of("React"), 1);
        TagGroup last = group(me, "예산", List.of("예산"), 2);

        mvc.perform(delete("/tag-groups/{id}", middle.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(nullValue()));

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(first.getId(), last.getId()))))
                .andExpect(jsonPath("$.data[*].position").value(contains(0, 1)));
    }

    @Test
    @DisplayName("그룹 순서 변경(위로 이동): 내 그룹 id 를 전부 한 번씩 보내면 그 순서로 position 0.. 이 매겨져 온다")
    void reorderGroups() throws Exception {
        Member me = member("sprout");
        TagGroup a = group(me, "유럽", List.of("로마"), 0);
        TagGroup b = group(me, "React", List.of("React"), 1);
        TagGroup c = group(me, "예산", List.of("예산"), 2);

        mvc.perform(patch("/tag-groups/order").with(as(me))
                        .with(json(Map.of("groupIds", List.of(c.getId(), a.getId(), b.getId())))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].name").value(contains("예산", "유럽", "React")))
                .andExpect(jsonPath("$.data[*].position").value(contains(0, 1, 2)));
    }

    @Test
    @DisplayName("순서 변경에 그룹이 빠지거나 · 중복되거나 · 남의 그룹이 섞이면 400 '정렬할 그룹 목록이 올바르지 않습니다.' 이고 순서는 그대로다")
    void reorderMustContainEveryGroupExactlyOnce() throws Exception {
        Member me = member("sprout");
        TagGroup a = group(me, "유럽", List.of("로마"), 0);
        TagGroup b = group(me, "React", List.of("React"), 1);
        TagGroup c = group(me, "예산", List.of("예산"), 2);
        TagGroup foreign = group(member("wanderer"), "프라하", List.of("프라하"), 0);

        List<List<Long>> invalid = List.of(
                List.of(b.getId(), a.getId()),
                List.of(a.getId(), a.getId(), b.getId()),
                List.of(a.getId(), b.getId(), c.getId(), c.getId()),
                List.of(a.getId(), b.getId(), foreign.getId()),
                List.of(a.getId(), b.getId(), c.getId(), 999_999L));
        for (List<Long> groupIds : invalid) {
            mvc.perform(patch("/tag-groups/order").with(as(me)).with(json(Map.of("groupIds", groupIds))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_ORDER));
        }

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(a.getId(), b.getId(), c.getId()))));
    }

    @Test
    @DisplayName("빈 순서 목록을 보내면 400 '정렬할 그룹 목록을 입력해 주세요.'")
    void emptyOrderIsRejected() throws Exception {
        Member me = member("sprout");
        group(me, "유럽", List.of("로마"), 0);

        mvc.perform(patch("/tag-groups/order").with(as(me)).with(json(Map.of("groupIds", List.of()))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "정렬할 그룹 목록을 입력해 주세요."));
    }

    @Test
    @DisplayName("그룹 이름이 없거나 50자를 넘으면 400 안내 문구")
    void groupNameValidation() throws Exception {
        Member me = member("sprout");

        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of("hashtags", List.of("로마")))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "그룹 이름을 입력해 주세요."));
        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of("name", "가".repeat(51), "hashtags", List.of("로마")))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "그룹 이름은 최대 50자까지 입력할 수 있습니다."));
    }

    @Test
    @DisplayName("그룹 이름을 빈 값 · 공백으로 수정하면 400 '그룹 이름을 입력해 주세요.' 이고 이름 · 해시태그는 그대로다")
    void blankNameUpdateIsRejected() throws Exception {
        Member me = member("sprout");
        TagGroup europe = group(me, "유럽 여행 준비", List.of("로마", "파리"), 0);

        for (String blank : List.of("", "   ", "\t", " \n ")) {
            mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me))
                            .with(json(Map.of("name", blank, "hashtags", List.of("항공권")))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "그룹 이름을 입력해 주세요."));
        }
        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me)).with(json(Map.of("name", "가".repeat(51)))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "그룹 이름은 최대 50자까지 입력할 수 있습니다."));

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].name").value("유럽 여행 준비"))
                .andExpect(jsonPath("$.data[0].hashtags").value(contains("로마", "파리")));
    }

    @Test
    @DisplayName("그룹 수정에 name 을 안 보내면 이름은 그대로, 보내면 바뀐다 (앞뒤 공백이 있어도 글자가 있으면 통과)")
    void nameIsOptionalOnUpdate() throws Exception {
        Member me = member("sprout");
        TagGroup europe = group(me, "유럽 여행 준비", List.of("로마", "파리"), 0);

        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me)).with(json(Map.of("hashtags", List.of("파리")))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.name").value("유럽 여행 준비"))
                .andExpect(jsonPath("$.data.hashtags").value(contains("파리")));
        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me)).with(json("{\"name\": null}")))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.name").value("유럽 여행 준비"));
        mvc.perform(patch("/tag-groups/{id}", europe.getId()).with(as(me)).with(json(Map.of("name", " 파리만 "))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.name").value(" 파리만 "));

        mvc.perform(get("/tag-groups").with(as(me)))
                .andExpect(jsonPath("$.data[0].name").value(" 파리만 "))
                .andExpect(jsonPath("$.data[0].hashtags").value(contains("파리")));
    }

    @Test
    @DisplayName("그룹 해시태그가 10개를 넘거나 · 형식이 틀리거나 · 중복이면 400 이고 그룹은 안 생긴다")
    void groupHashtagValidation() throws Exception {
        Member me = member("sprout");
        List<String> eleven = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11");

        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of("name", "많음", "hashtags", eleven))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "해시태그는 최대 10개까지 추가할 수 있습니다."));
        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of("name", "형식", "hashtags", List.of("#로마")))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST,
                        "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다."));
        mvc.perform(post("/tag-groups").with(as(me)).with(json(Map.of("name", "중복", "hashtags", List.of("Rome", "rome")))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."));

        mvc.perform(get("/tag-groups").with(as(me))).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("다른 사람 그룹은 내 그룹 보기에 안 나오고, 수정 · 삭제하면 404 '해시태그 그룹을 찾을 수 없습니다.'")
    void foreignGroupIsInvisible() throws Exception {
        Member me = member("sprout");
        TagGroup foreign = group(member("wanderer"), "프라하", List.of("프라하"), 0);

        mvc.perform(get("/tag-groups").with(as(me))).andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(patch("/tag-groups/{id}", foreign.getId()).with(as(me)).with(json(Map.of("hashtags", List.of("로마")))))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        mvc.perform(delete("/tag-groups/{id}", foreign.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
    }
}
