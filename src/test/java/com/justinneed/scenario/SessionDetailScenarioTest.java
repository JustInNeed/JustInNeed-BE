package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * 세션 상세 화면 (/sessions/{id}). FE: src/features/sessions/SessionDetail.tsx (404 · 403 을 따로 보여줌),
 * AddHashtagPopover(최대 10개 · 형식 · 중복), SessionEditor(제목 · 본문 편집 · 30초 자동 저장).
 */
@DisplayName("세션 상세 화면")
class SessionDetailScenarioTest extends ScenarioTestSupport {

    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";
    private static final String INVALID_HASHTAG = "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다.";

    @Test
    @DisplayName("상세를 열면 기본 정보 · 태그(저장 순서) · 줄글 마크다운 · 출처(title/url/excerpt)가 온다")
    void detailFields() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(rome.getId()))
                .andExpect(jsonPath("$.data.title").value("로마 3박 4일 동선 짜기"))
                .andExpect(jsonPath("$.data.startedAt").value("2026-04-26T21:14:00"))
                .andExpect(jsonPath("$.data.endedAt").value("2026-04-26T22:48:00"))
                .andExpect(jsonPath("$.data.pageCount").value(18))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.isFavorite").value(true))
                .andExpect(jsonPath("$.data.isPublic").value(false))
                .andExpect(jsonPath("$.data.tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")))
                .andExpect(jsonPath("$.data.summary.markdown").value(ROME_MARKDOWN))
                // FE SessionSummary.heading 은 deprecated — BE 도 보내지 않는다
                .andExpect(jsonPath("$.data.summary.heading").doesNotExist())
                .andExpect(jsonPath("$.data.sources", hasSize(4)))
                .andExpect(jsonPath("$.data.sources[0].title").value("콜로세움 공식 예약"))
                .andExpect(jsonPath("$.data.sources[0].url").value("https://www.colosseo.it/en/"))
                .andExpect(jsonPath("$.data.sources[0].excerpt").value("입장권은 30분 단위로 예약한다."))
                .andExpect(jsonPath("$.data.sources[*].url").value(contains(
                        "https://www.colosseo.it/en/",
                        "https://www.museivaticani.va/",
                        "https://www.turismoroma.it/en/places/trastevere",
                        "https://www.tripadvisor.co.kr/Restaurants-g187791-Rome_Lazio.html")));
    }

    @Test
    @DisplayName("요약이 아직 없는 세션은 summary=null · sources=[] 로 와서 FE 가 빈 줄글을 보여준다")
    void detailWithoutSummary() throws Exception {
        Member me = member("sprout");
        BrowsingSession bare = session(me, "방금 끝난 세션", "2026-04-27T09:00:00", "2026-04-27T09:10:00", 2,
                false, false, List.of(), List.of(), null);

        mvc.perform(get("/sessions/{id}", bare.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.data.sources", hasSize(0)))
                .andExpect(jsonPath("$.data.tags", hasSize(0)));
    }

    @Test
    @DisplayName("없는 세션 id 로 열면 404 '세션을 찾을 수 없습니다.' → FE '세션을 찾을 수 없어요'")
    void unknownSessionIs404() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/sessions/{id}", 999_999).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("숫자가 아닌 id(/sessions/abc)는 400 '요청 형식이 올바르지 않습니다.'")
    void nonNumericIdIs400() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/sessions/abc").with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("다른 사람의 세션은 공개 여부와 상관없이 /sessions/{id} 로 열 수 없고(403 또는 404) 내용도 새지 않는다")
    void foreignSessionIsBlocked() throws Exception {
        Member me = member("sprout");
        Wanderer wanderer = seedWanderer();

        for (BrowsingSession foreign : List.of(wanderer.prague(), wanderer.slow())) {
            mvc.perform(get("/sessions/{id}", foreign.getId()).with(as(me)))
                    .andExpect(status().is(anyOf(is(403), is(404))))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    @Disabled("FE 기대와 다름: FE SessionDetail 은 403 을 '볼 수 없는 세션이에요 · 비공개 세션이라 볼 수 없어요'로 따로 보여주지만 "
            + "BE GET /sessions/{id} 는 남의 세션을 모두 404 로 응답 — 403 으로 나눌지 팀 결정 후 켠다")
    @DisplayName("다른 사람의 비공개 세션을 열면 403 을 받아 FE 가 '비공개 세션이라 볼 수 없어요'를 보여준다")
    void foreignPrivateSessionIs403() throws Exception {
        Member me = member("sprout");
        Wanderer wanderer = seedWanderer();

        mvc.perform(get("/sessions/{id}", wanderer.slow().getId()).with(as(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("다른 사람 세션을 수정 · 삭제하려 하면 404 이고 원본은 그대로다")
    void foreignSessionCannotBeChanged() throws Exception {
        Member me = member("sprout");
        Wanderer wanderer = seedWanderer();
        Long id = wanderer.prague().getId();

        mvc.perform(patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("title", "내 거"))))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        mvc.perform(delete("/sessions/{id}", id).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));

        mvc.perform(get("/sessions/{id}", id).with(as(wanderer.member())))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.title").value("프라하 1박 일정 짜기"));
    }

    @Test
    @DisplayName("해시태그 하나를 지우면(REC-7-3) 나머지 태그가 원래 순서대로 저장된다")
    void removeOneTagKeepsOrder() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me))
                        .with(json(Map.of("tags", List.of("로마", "바티칸", "먹거리")))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.tags").value(contains("로마", "바티칸", "먹거리")));

        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.tags").value(contains("로마", "바티칸", "먹거리")));
    }

    @Test
    @DisplayName("해시태그를 드래그로 옮기면(REC-7-4) 보낸 순서 그대로 저장된다")
    void reorderTags() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me))
                        .with(json(Map.of("tags", List.of("먹거리", "로마", "콜로세움", "바티칸")))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.tags").value(contains("먹거리", "로마", "콜로세움", "바티칸")));
    }

    @Test
    @DisplayName("해시태그 추가(REC-7-2): 10개까지 저장되고 앞뒤 공백은 지워진다")
    void addTagsUpToTen() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();
        List<String> ten = List.of("로마", "콜로세움", "바티칸", "먹거리", "판테온", "트레비", "젤라또", "포로로마노", "테르미니", " 스페인계단 ");

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("tags", ten))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.tags", hasSize(10)))
                .andExpect(jsonPath("$.data.tags[9]").value("스페인계단"));
    }

    @Test
    @DisplayName("해시태그가 10개를 넘으면 400 '해시태그는 최대 10개까지 추가할 수 있습니다.' 이고 기존 태그는 그대로다")
    void moreThanTenTagsIsRejected() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();
        List<String> eleven = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11");

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("tags", eleven))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "해시태그는 최대 10개까지 추가할 수 있습니다."));

        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"#로마", "콜로세움 투어", "가나다라마바사아자차카", "rome!", ""})
    @DisplayName("형식이 틀린 해시태그('#' · 공백 · 특수문자 · 11자 이상 · 빈 값)는 400 — FE hashtag.ts 와 같은 규칙")
    void invalidTagIsRejected(String tag) throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me))
                        .with(json(Map.of("tags", List.of("로마", tag)))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_HASHTAG));
    }

    @Test
    @DisplayName("대소문자만 다른 같은 태그를 넣으면 400 '이미 등록된 해시태그입니다.'")
    void duplicateTagIgnoringCaseIsRejected() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me))
                        .with(json(Map.of("tags", List.of("Rome", "rome")))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."));
    }

    @Test
    @DisplayName("즐겨찾기만 보내면 즐겨찾기만 바뀌고 제목 · 태그 · 본문 · 공개는 그대로다 (보낸 필드만 수정)")
    void partialUpdateKeepsOtherFields() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("isFavorite", false))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isFavorite").value(false))
                .andExpect(jsonPath("$.data.title").value("로마 3박 4일 동선 짜기"))
                .andExpect(jsonPath("$.data.tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")))
                .andExpect(jsonPath("$.data.summary.markdown").value(ROME_MARKDOWN))
                .andExpect(jsonPath("$.data.isPublic").value(false))
                .andExpect(jsonPath("$.data.sources", hasSize(4)));
    }

    @Test
    @DisplayName("공개 토글(COM-1-3)을 켜면 isPublic=true, 다시 끄면 false 로 저장된다")
    void togglePublic() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("isPublic", true))))
                .andExpect(jsonPath("$.data.isPublic").value(true));
        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.isPublic").value(true));
        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("isPublic", false))))
                .andExpect(jsonPath("$.data.isPublic").value(false));
    }

    @Test
    @DisplayName("편집 저장(REC-5-1 · 5-2): 제목과 본문을 함께 바꾸면 둘 다 반영되고, 30초 자동 저장처럼 같은 내용을 또 보내도 결과가 같다")
    void editTitleAndMarkdown() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();
        Map<String, Object> edit = Map.of(
                "title", "로마 3박 4일 최종 동선",
                "editedMarkdown", "# 로마 최종\n\n## 1일차\n콜로세움 09:00 입장");

        for (int autosave = 0; autosave < 2; autosave++) {
            mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(edit)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.title").value("로마 3박 4일 최종 동선"))
                    .andExpect(jsonPath("$.data.summary.markdown").value("# 로마 최종\n\n## 1일차\n콜로세움 09:00 입장"));
        }

        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.title").value("로마 3박 4일 최종 동선"))
                .andExpect(jsonPath("$.data.summary.markdown").value("# 로마 최종\n\n## 1일차\n콜로세움 09:00 입장"));
    }

    @Test
    @DisplayName("요약이 없던 세션에 본문을 저장하면 summary 가 새로 생긴다")
    void markdownCreatesSummary() throws Exception {
        Member me = member("sprout");
        BrowsingSession bare = session(me, "방금 끝난 세션", "2026-04-27T09:00:00", "2026-04-27T09:10:00", 2,
                false, false, List.of(), List.of(), null);

        mvc.perform(patch("/sessions/{id}", bare.getId()).with(as(me)).with(json(Map.of("editedMarkdown", "# 직접 쓴 메모"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.summary.markdown").value("# 직접 쓴 메모"));
    }

    @Test
    @DisplayName("제목을 비우면 400 '제목을 입력해 주세요.', 100자를 넘기면 400 '제목은 최대 100자까지 입력할 수 있습니다.'")
    void titleValidation() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("title", "   "))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요."));
        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("title", "가".repeat(101)))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "제목은 최대 100자까지 입력할 수 있습니다."));
        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("title", "가".repeat(100)))))
                .andExpect(okEnvelope());
    }

    @Test
    @DisplayName("세션을 삭제(REC-6)하면 상세 · 목록에서 사라지고, 다시 삭제하면 404")
    void deleteSession() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(delete("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(nullValue()));

        mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data", hasSize(6)))
                .andExpect(jsonPath("$.data[*].id").value(not(hasItem(rome.getId().intValue()))));
        mvc.perform(delete("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    @Test
    @Disabled("API 미구현: 세션 상세 sources[].visitedAt (A표 '출처 방문 시각' 반영 결정, 필드명은 제안) — 구현되면 켠다")
    @DisplayName("출처마다 방문 시각(visitedAt)이 오고 방문한 순서대로 정렬돼 FE 가 시간순으로 보여준다")
    void sourcesHaveVisitedAt() throws Exception {
        Member me = member("sprout");
        BrowsingSession rome = seedMockSessions(me).rome();

        var detail = mvc.perform(get("/sessions/{id}", rome.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sources[*].visitedAt", everyItem(notNullValue())))
                .andExpect(jsonPath("$.data.sources[*].visitedAt",
                        everyItem(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"))));
        List<String> visitedAt = read(detail, "$.data.sources[*].visitedAt");
        assertThat(visitedAt).isSorted();
    }
}
