package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.SqlCapture;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 프로필 수정 화면의 저장 버튼 (PUT /community/me/profile): 소개 · 관심사 · 노드 공개 범위 · 해시태그 공개 · 고정 세션을 한 번에 저장한다.
 * 기반 클래스처럼 테스트 트랜잭션 안에서 돌면 요청이 그 트랜잭션에 합류해, 실패한 요청의 변경이 영속성 컨텍스트에 남아 롤백을 확인할 수 없다.
 * 그래서 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다 (만든 행은 매번 지운다).
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile-save;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.justinneed.community.SqlCapture"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("프로필 한 번에 저장")
class ProfileSaveScenarioTest extends ScenarioTestSupport {

    private static final String NOT_FOUND = "요청한 정보를 찾을 수 없습니다.";
    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";
    private static final String BIO_TOO_LONG = "소개는 120자까지 입력할 수 있습니다.";
    private static final String MALFORMED = "요청 형식이 올바르지 않습니다.";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CommunityProfiles profileLock;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        for (String table : List.of("community_nodes", "community_cards", "community_hashtags", "community_profiles",
                "summaries", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    private static Map<String, Object> body(String bio, String visibility, List<String> interests,
            List<Long> hiddenHashtagIds, List<Long> pinnedSessionIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("bio", bio);
        body.put("visibility", visibility);
        body.put("interests", interests);
        body.put("hiddenHashtagIds", hiddenHashtagIds);
        body.put("pinnedSessionIds", pinnedSessionIds);
        return body;
    }

    private static Map<String, Object> valid() {
        return body("소개", "URL_ONLY", List.of(), List.of(), List.of());
    }

    private ResultActions save(Member me, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/community/me/profile").with(as(me)).with(json(body)));
    }

    private BrowsingSession session(Member owner, String title, boolean isPublic, String... tags) {
        return session(owner, title, "2026-04-20T10:00:00", "2026-04-20T11:00:00", 1, false, isPublic, List.of(tags),
                List.of(new Source(title, "https://example.com/" + title, "발췌")), "본문");
    }

    private List<String> savedHidden(Member me) {
        return communityProfiles.findById(me.getId()).orElseThrow().getHiddenHashtags();
    }

    private void seedHidden(Member me, String... hidden) {
        CommunityProfile profile = new CommunityProfile(me.getId());
        profile.replaceHiddenHashtags(List.of(hidden));
        communityProfiles.saveAndFlush(profile);
    }

    private void expectPublic(Member me, BrowsingSession session, boolean isPublic) throws Exception {
        mvc.perform(get("/sessions/{id}", session.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.isPublic").value(isPublic));
    }

    @Test
    @DisplayName("소개 · 관심사 · 공개 범위 · 해시태그 공개 · 고정 세션이 한 번에 저장돼 기존 조회 API 로 보이고, 응답 data 는 없다")
    void savesEverything() throws Exception {
        Member me = member("sprout");
        Member viewer = member("foodtrip");
        BrowsingSession rome = session(me, "로마", true, "로마", "먹거리");
        BrowsingSession paris = session(me, "파리", true, "파리");
        Long hiddenId = hashtagId(me, "먹거리");

        save(me, body("  소개입니다  ", "CONTENT", List.of(" 유럽여행 ", "HCI"), List.of(hiddenId, hiddenId),
                List.of(paris.getId(), rome.getId())))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(nullValue()));

        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.bio").value("소개입니다"))
                .andExpect(jsonPath("$.data.interests").value(contains("유럽여행", "HCI")))
                .andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(paris, rome))));
        mvc.perform(get("/community/me/visibility").with(as(me)))
                .andExpect(jsonPath("$.data.visibilityScope").value("CONTENT"));
        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '먹거리')].isPublic").value(contains(false)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].isPublic").value(contains(true)));
        assertThat(savedHidden(me)).containsExactly("먹거리");
    }

    @Test
    @DisplayName("마지막 칸(고정 세션)에서 실패하면 앞서 처리한 소개 · 관심사 · 공개 범위 · 해시태그 공개가 모두 저장되지 않는다")
    void badPinRollsEverythingBack() throws Exception {
        Member me = member("sprout");
        Member viewer = member("foodtrip");
        BrowsingSession rome = session(me, "로마", true, "로마");
        BrowsingSession secret = session(me, "비밀", false, "비밀");
        Long romeTag = hashtagId(me, "로마");
        save(me, body("처음", "URL_ONLY", List.of("HCI"), List.of(), List.of(rome.getId()))).andExpect(okEnvelope());

        save(me, body("바뀐 소개", "CONTENT", List.of("새관심"), List.of(romeTag), List.of(secret.getId())))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "공개한 세션만 고정할 수 있습니다."));

        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.bio").value("처음"))
                .andExpect(jsonPath("$.data.interests").value(contains("HCI")))
                .andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(rome))));
        mvc.perform(get("/community/me/visibility").with(as(me)))
                .andExpect(jsonPath("$.data.visibilityScope").value("URL_ONLY"));
        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].isPublic").value(contains(true)));
        // 뒤에서 실패해도 관심사에서 만든 해시태그 행이 남지 않는다
        assertThat(communityTags.index(me.getId())).doesNotContainKey("새관심");
    }

    @Test
    @DisplayName("프로필 행이 없는 회원의 첫 저장이 실패하면 프로필 행도 남지 않는다")
    void firstSaveFailureLeavesNoProfile() throws Exception {
        Member me = member("sprout");

        save(me, body("소개", "CONTENT", List.of("HCI"), List.of(), List.of(999_999L)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));

        assertThat(communityProfiles.existsById(me.getId())).isFalse();
    }

    @Test
    @DisplayName("관심사가 11개면 400 이고 기존 관심사는 그대로다")
    void tooManyInterests() throws Exception {
        Member me = member("sprout");
        save(me, body("소개", "URL_ONLY", List.of("HCI"), List.of(), List.of())).andExpect(okEnvelope());
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("태그" + i);
        }

        save(me, body("바뀐 소개", "URL_ONLY", eleven, List.of(), List.of()))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "해시태그는 최대 10개까지 추가할 수 있습니다."));

        mvc.perform(get("/community/profiles/{id}/interests", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data").value(contains("HCI")));
        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.bio").value("소개"));
    }

    @Test
    @DisplayName("관심사 형식이 틀리면 400, 대소문자만 다른 중복이면 400")
    void interestValidation() throws Exception {
        Member me = member("sprout");

        save(me, body("소개", "URL_ONLY", List.of("#유럽"), List.of(), List.of()))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST,
                        "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다."));
        save(me, body("소개", "URL_ONLY", List.of("HCI", "hci"), List.of(), List.of()))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."));
    }

    @Test
    @DisplayName("관심사는 보낸 목록으로 통째로 바뀌고 보낸 순서를 따른다")
    void interestsAreReplaced() throws Exception {
        Member me = member("sprout");
        save(me, body("소개", "URL_ONLY", List.of("가", "나", "다"), List.of(), List.of())).andExpect(okEnvelope());

        save(me, body("소개", "URL_ONLY", List.of("다", "라"), List.of(), List.of())).andExpect(okEnvelope());

        mvc.perform(get("/community/profiles/{id}/interests", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data").value(contains("다", "라")));
    }

    @Test
    @DisplayName("저장으로 새로 넣은 관심사는 id 가 생겨 숨기기 · 삭제 · 다음 저장에 쓸 수 있다")
    void newInterestIsUsable() throws Exception {
        Member me = member("sprout");

        save(me, body("소개", "URL_ONLY", List.of("유럽여행"), List.of(), List.of())).andExpect(okEnvelope());

        Long id = hashtagId(me, "유럽여행");
        mvc.perform(get("/community/me/interests").with(as(me)))
                .andExpect(jsonPath("$.data[*].interestId").value(contains(id.intValue())));
        save(me, body("소개", "URL_ONLY", List.of("유럽여행"), List.of(id), List.of())).andExpect(okEnvelope());
        assertThat(savedHidden(me)).containsExactly("유럽여행");
        mvc.perform(delete("/community/me/interests/{id}", id).with(as(me))).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("이번 저장에서 뺀 관심사도 저장 전 목록의 해시태그라 같은 저장에서 비공개로 보낼 수 있다")
    void hidingInterestRemovedInSameSave() throws Exception {
        Member me = member("sprout");
        save(me, body("소개", "URL_ONLY", List.of("유럽여행"), List.of(), List.of())).andExpect(okEnvelope());
        Long id = hashtagId(me, "유럽여행");

        save(me, body("소개", "URL_ONLY", List.of(), List.of(id), List.of())).andExpect(okEnvelope());

        assertThat(savedHidden(me)).containsExactly("유럽여행");
    }

    @Test
    @DisplayName("비공개 해시태그는 보낸 목록으로 통째로 바뀌어 빠진 태그는 공개로 돌아가고, 영문은 소문자로 저장한다")
    void hiddenAreReplaced() throws Exception {
        Member me = member("sprout");
        session(me, "로마", true, "로마", "Pasta");
        Long rome = hashtagId(me, "로마");
        Long pasta = hashtagId(me, "Pasta");
        save(me, body("소개", "URL_ONLY", List.of(), List.of(rome), List.of())).andExpect(okEnvelope());

        save(me, body("소개", "URL_ONLY", List.of(), List.of(pasta), List.of())).andExpect(okEnvelope());

        assertThat(savedHidden(me)).containsExactly("pasta");
        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].isPublic").value(contains(true)))
                .andExpect(jsonPath("$.data[?(@.hashtag == 'Pasta')].isPublic").value(contains(false)));
    }

    @Test
    @DisplayName("다른 사람의 해시태그 id · 없는 id 면 404")
    void foreignHashtag() throws Exception {
        Member me = member("sprout");
        Member other = member("foodtrip");
        session(other, "남의 세션", true, "남의태그");
        Long foreign = hashtagId(other, "남의태그");

        save(me, body("소개", "URL_ONLY", List.of(), List.of(foreign), List.of()))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
        save(me, body("소개", "URL_ONLY", List.of(), List.of(999_999L), List.of()))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    @Test
    @DisplayName("태그 행이 없는 옛 비공개 항목은 id 가 없어 화면이 못 보내므로 저장해도 지워지지 않는다")
    void legacyHiddenEntryIsKept() throws Exception {
        Member me = member("sprout");
        session(me, "로마", true, "로마");
        seedHidden(me, "옛태그", "로마");
        assertThat(communityTags.index(me.getId())).doesNotContainKey("옛태그");

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of())).andExpect(okEnvelope());

        assertThat(savedHidden(me)).containsExactly("옛태그");
    }

    @Test
    @DisplayName("비공개 세션을 고정하면 400, 저장해도 세션을 공개로 바꾸지 않는다")
    void privateSessionRejected() throws Exception {
        Member me = member("sprout");
        BrowsingSession secret = session(me, "비밀", false, "비밀");

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(secret.getId())))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "공개한 세션만 고정할 수 있습니다."));

        expectPublic(me, secret, false);
    }

    @Test
    @DisplayName("남의 세션 · 지운 세션 · 없는 세션을 고정하면 404, 분석이 안 끝난 세션이면 409")
    void pinErrors() throws Exception {
        Member me = member("sprout");
        Member other = member("foodtrip");
        BrowsingSession foreign = session(other, "남의 세션", true, "남의태그");
        BrowsingSession deleted = session(me, "지운 세션", true, "지운태그");
        mvc.perform(delete("/sessions/{id}", deleted.getId()).with(as(me))).andExpect(okEnvelope());
        BrowsingSession analyzing = sessionWithStatus(me, "분석 중", "2026-04-20T10:00:00", "2026-04-20T11:00:00",
                SessionStatus.ANALYZING, List.of());

        for (Long id : List.of(foreign.getId(), deleted.getId(), 999_999L)) {
            save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(id)))
                    .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        }
        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(analyzing.getId())))
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, "분석이 끝난 세션만 수정할 수 있습니다."));
    }

    @Test
    @DisplayName("고정 세션은 보낸 순서로 저장되고 다시 보내면 그 순서로 바뀐다")
    void pinOrder() throws Exception {
        Member me = member("sprout");
        Member viewer = member("foodtrip");
        BrowsingSession a = session(me, "가", true, "가");
        BrowsingSession b = session(me, "나", true, "나");
        BrowsingSession c = session(me, "다", true, "다");

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(c.getId(), a.getId(), b.getId())))
                .andExpect(okEnvelope());
        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(c, a, b))));

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(b.getId(), c.getId(), a.getId())))
                .andExpect(okEnvelope());
        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(b, c, a))));
    }

    @Test
    @DisplayName("같은 세션 id 가 두 번 오면 400 이고 고정 목록은 그대로다")
    void duplicatePin() throws Exception {
        Member me = member("sprout");
        BrowsingSession a = session(me, "가", true, "가");
        BrowsingSession b = session(me, "나", true, "나");
        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(b.getId()))).andExpect(okEnvelope());

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(a.getId(), a.getId())))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "고정할 세션 목록이 올바르지 않습니다."));

        assertThat(communityProfiles.findById(me.getId()).orElseThrow().getPinnedSessionIds())
                .containsExactly(b.getId());
    }

    @Test
    @DisplayName("저장은 세션 공개 상태를 바꾸지 않는다: 고정에서 빠진 공개 세션은 공개로 남고, 빈 목록이면 고정이 모두 풀린다")
    void sessionPublicStateUnchanged() throws Exception {
        Member me = member("sprout");
        BrowsingSession a = session(me, "가", true, "가");
        BrowsingSession b = session(me, "나", true, "나");
        BrowsingSession secret = session(me, "비밀", false, "비밀");
        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(a.getId(), b.getId()))).andExpect(okEnvelope());

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of(a.getId()))).andExpect(okEnvelope());
        expectPublic(me, a, true);
        expectPublic(me, b, true);
        expectPublic(me, secret, false);

        save(me, body("소개", "URL_ONLY", List.of(), List.of(), List.of())).andExpect(okEnvelope());
        expectPublic(me, a, true);
        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.pinnedSessions", empty()));
    }

    @Test
    @DisplayName("고정 세션 검증은 세션 수와 상관없이 쿼리 한 번이다")
    void pinValidationIsOneQuery() throws Exception {
        Member me = member("sprout");
        List<Long> pins = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pins.add(session(me, "세션" + i, true, "태그" + i).getId());
        }

        SqlCapture.clear();
        save(me, body("소개", "URL_ONLY", List.of(), List.of(), pins)).andExpect(okEnvelope());

        assertThat(SqlCapture.all()).filteredOn(sql -> sql.contains("from sessions") && sql.contains(".id in ("))
                .hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "bio, 소개를 입력해 주세요.",
            "visibility, 공개 범위를 입력해 주세요.",
            "interests, 관심 해시태그 목록을 입력해 주세요.",
            "hiddenHashtagIds, 비공개 해시태그 목록을 입력해 주세요.",
            "pinnedSessionIds, 고정할 세션 목록을 입력해 주세요."
    })
    @DisplayName("필드가 빠지면 400")
    void missingField(String field, String message) throws Exception {
        Member me = member("sprout");
        Map<String, Object> body = valid();
        body.remove(field);

        save(me, body).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, message));
    }

    @ParameterizedTest
    @CsvSource({
            "interests, 관심 해시태그가 올바르지 않습니다.",
            "hiddenHashtagIds, 해시태그가 올바르지 않습니다.",
            "pinnedSessionIds, 세션이 올바르지 않습니다."
    })
    @DisplayName("목록에 null 이 있으면 400")
    void nullElement(String field, String message) throws Exception {
        Member me = member("sprout");
        Map<String, Object> body = valid();
        body.put(field, Arrays.asList((Object) null));

        save(me, body).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"full\"", "\"content\"", "\"\"", "1", "[]"})
    @DisplayName("없는 공개 범위 값이면 500 이 아니라 400 한글 안내이고, 숫자를 순서로 받아 CONTENT 가 되지 않는다")
    void unknownVisibility(String value) throws Exception {
        Member me = member("sprout");
        String raw = "{\"bio\":\"소개\",\"visibility\":" + value
                + ",\"interests\":[],\"hiddenHashtagIds\":[],\"pinnedSessionIds\":[]}";

        mvc.perform(put("/community/me/profile").with(as(me)).with(json(raw)))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, MALFORMED));

        assertThat(communityProfiles.existsById(me.getId())).isFalse();
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresLogin() throws Exception {
        mvc.perform(put("/community/me/profile").with(json(valid()))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("소개는 120자까지 저장되고 비워도 되며, 121자면 400 이다 (앞뒤 공백을 지우기 전 길이로 잰다)")
    void bioLength() throws Exception {
        Member me = member("sprout");
        Map<String, Object> full = valid();
        full.put("bio", "가".repeat(120));
        save(me, full).andExpect(okEnvelope());

        Map<String, Object> over = valid();
        over.put("bio", "나".repeat(121));
        save(me, over).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, BIO_TOO_LONG));
        Map<String, Object> padded = valid();
        padded.put("bio", " ".repeat(10) + "나".repeat(115));
        save(me, padded).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, BIO_TOO_LONG));
        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.bio").value("가".repeat(120)));

        Map<String, Object> blank = valid();
        blank.put("bio", "   ");
        save(me, blank).andExpect(okEnvelope());
        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.bio").value(""));
    }

    @Test
    @DisplayName("기존 PATCH 소개 수정은 그대로 동작한다")
    void patchBioStays() throws Exception {
        Member me = member("sprout");

        mvc.perform(patch("/community/me/profile").with(as(me)).with(json(Map.of("bio", "소개"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bio").value("소개"));
    }

    @Test
    @DisplayName("다른 요청이 프로필을 잡고 있으면 기다렸다가 최신 행을 읽어, 그 요청이 넣은 옛 비공개 항목을 덮지 않는다")
    void waitsForProfileLock() throws Exception {
        Member me = member("sprout");
        seedHidden(me);
        List<Future<Integer>> response = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            profileLock.lock(me.getId()).replaceHiddenHashtags(List.of("옛태그"));
            communityProfiles.flush();
            response.add(send(me, body("저장", "CONTENT", List.of("관심"), List.of(), List.of())));
            pause();
        });

        assertThat(response.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(200);
        CommunityProfile saved = communityProfiles.findById(me.getId()).orElseThrow();
        assertThat(saved.getBio()).isEqualTo("저장");
        assertThat(saved.getHiddenHashtags()).containsExactly("옛태그");
    }

    @Test
    @DisplayName("저장 두 개가 겹쳐도 둘 다 성공하고 어느 한쪽 내용이 통째로 남으며 같은 새 관심사의 태그 행은 하나다")
    void twoSavesOverlap() throws Exception {
        Member me = member("sprout");
        BrowsingSession a = session(me, "가", true, "가");
        BrowsingSession b = session(me, "나", true, "나");
        Map<String, Object> first = body("첫째", "CONTENT", List.of("공통", "첫째"), List.of(), List.of(a.getId()));
        Map<String, Object> second = body("둘째", "URL_ONLY", List.of("공통", "둘째"), List.of(), List.of(b.getId()));
        List<Future<Integer>> responses = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from members where id = ? for update", Long.class, me.getId());
            responses.add(send(me, first));
            pause();
            responses.add(send(me, second));
            pause();
        });

        for (Future<Integer> response : responses) {
            assertThat(response.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        CommunityProfile saved = communityProfiles.findById(me.getId()).orElseThrow();
        if ("첫째".equals(saved.getBio())) {
            assertThat(saved.getInterests()).containsExactly("공통", "첫째");
            assertThat(saved.getPinnedSessionIds()).containsExactly(a.getId());
        } else {
            assertThat(saved.getBio()).isEqualTo("둘째");
            assertThat(saved.getInterests()).containsExactly("공통", "둘째");
            assertThat(saved.getPinnedSessionIds()).containsExactly(b.getId());
        }
        assertThat(jdbc.queryForObject("select count(*) from community_hashtags where owner_id = ? and name = '공통'",
                Integer.class, me.getId())).isEqualTo(1);
    }

    private Future<Integer> send(Member me, Map<String, Object> body) {
        return pool.submit(() -> mvc.perform(put("/community/me/profile").with(as(me)).with(json(body)))
                .andReturn().getResponse().getStatus());
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
