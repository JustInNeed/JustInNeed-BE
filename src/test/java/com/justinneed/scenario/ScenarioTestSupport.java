package com.justinneed.scenario;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 화면 시나리오 테스트 공통 기반: "FE 화면이 이 상황에서 API 를 부르면 이렇게 응답한다"를 고정한다.
 *
 * <ul>
 *     <li>시드 데이터는 FE 목 데이터(fe/src/test/fixtures/design.ts)를 닮게 만든다.</li>
 *     <li>전용 H2 DB(scenario)를 써서 다른 테스트 클래스가 커밋해 둔 데이터(user_id=1 세션 등)와 섞이지 않는다.
 *         DEFAULT_NULL_ORDERING=HIGH 로 운영 Postgres 와 같은 null 정렬을 쓴다.</li>
 *     <li>테스트마다 트랜잭션 안에서 돌고 끝나면 롤백된다.</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:scenario;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class ScenarioTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MemberRepository members;

    @Autowired
    protected BrowsingSessionRepository sessions;

    @Autowired
    protected CommunityProfileRepository communityProfiles;

    @Autowired
    protected JwtTokenProvider jwt;

    // ---------- 회원 / 인증 ----------

    protected Member member(String nickname) {
        return member(nickname, SocialProvider.KAKAO, null);
    }

    protected Member member(String nickname, SocialProvider provider, String email) {
        return members.saveAndFlush(new Member(provider, "scenario-" + UUID.randomUUID(), nickname, email));
    }

    protected String bearer(Member member) {
        return "Bearer " + jwt.createAccessToken(member.getId());
    }

    /** 이 회원으로 로그인한 FE 요청 (Authorization: Bearer access 토큰). */
    protected RequestPostProcessor as(Member member) {
        return bearerToken(jwt.createAccessToken(member.getId()));
    }

    protected static RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /** FE 가 보내는 JSON 본문. 문자열이면 그대로, 아니면 직렬화해서 보낸다. */
    protected RequestPostProcessor json(Object body) {
        byte[] content = (body instanceof String raw ? raw : objectMapper.writeValueAsString(body))
                .getBytes(StandardCharsets.UTF_8);
        return request -> {
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setCharacterEncoding(StandardCharsets.UTF_8.name());
            request.setContent(content);
            return request;
        };
    }

    // ---------- 응답 확인 ----------

    /** 성공 envelope: 200 + success=true. */
    protected static ResultMatcher okEnvelope() {
        return allOf(status().isOk(), jsonPath("$.success").value(true));
    }

    /** 실패 envelope: FE 는 message 를 그대로 토스트 · 인라인 문구로 보여준다. */
    protected static ResultMatcher errorEnvelope(HttpStatus expected, String message) {
        return allOf(
                status().is(expected.value()),
                jsonPath("$.success").value(false),
                jsonPath("$.data").value(nullValue()),
                jsonPath("$.message").value(message));
    }

    private static ResultMatcher allOf(ResultMatcher... matchers) {
        return result -> {
            for (ResultMatcher matcher : matchers) {
                matcher.match(result);
            }
        };
    }

    protected static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    protected static Long readLong(ResultActions result, String path) throws Exception {
        return ((Number) read(result, path)).longValue();
    }

    /** JSON 숫자는 Integer 로 읽히므로 id 비교용으로 바꾼다. */
    protected static Integer[] ids(Long... ids) {
        Integer[] result = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = ids[i].intValue();
        }
        return result;
    }

    protected static Integer[] ids(BrowsingSession... sessions) {
        Integer[] result = new Integer[sessions.length];
        for (int i = 0; i < sessions.length; i++) {
            result[i] = sessions[i].getId().intValue();
        }
        return result;
    }

    // ---------- 세션 시드 ----------

    protected BrowsingSession session(Member owner, String title, String startedAt, String endedAt, int pageCount,
            boolean favorite, boolean isPublic, List<String> tags, List<Source> sources, String markdown) {
        return session(owner, title, LocalDateTime.parse(startedAt), LocalDateTime.parse(endedAt), pageCount,
                favorite, isPublic, tags, sources, markdown);
    }

    protected BrowsingSession session(Member owner, String title, LocalDateTime startedAt, LocalDateTime endedAt,
            int pageCount, boolean favorite, boolean isPublic, List<String> tags, List<Source> sources,
            String markdown) {
        BrowsingSession session = new BrowsingSession(owner.getId(), title, startedAt);
        session.complete(endedAt, pageCount);
        session.update(null, null, isPublic, favorite, tags);
        session.replaceSources(sources);
        if (markdown != null) {
            new Summary(session, markdown);
        }
        return sessions.saveAndFlush(session);
    }

    /**
     * 상태를 직접 정한 세션 (진행 중 · 실패). 상태는 수집 · 분석 흐름에서만 바뀌므로 테스트에선 필드를 바로 정한다.
     * endedAt 이 null 이면 종료 시각이 없는 세션.
     */
    protected BrowsingSession sessionWithStatus(Member owner, String title, String startedAt, String endedAt,
            SessionStatus status, List<String> tags) {
        BrowsingSession session = new BrowsingSession(owner.getId(), title, LocalDateTime.parse(startedAt));
        if (endedAt != null) {
            session.complete(LocalDateTime.parse(endedAt), 1);
        }
        session.update(null, null, false, false, tags);
        ReflectionTestUtils.setField(session, "status", status);
        return sessions.saveAndFlush(session);
    }

    protected static final String ROME_MARKDOWN = """
            # 로마 3박 4일 동선

            ## 1일차 — 콜로세움 · 포로 로마노
            오전 첫 타임으로 콜로세움을 예약하고 포로 로마노까지 걸어서 본다.

            ## 2일차 — 바티칸
            바티칸 박물관은 일요일에 쉬므로 평일 오전에 간다.""";

    protected static final List<Source> ROME_SOURCES = List.of(
            new Source("콜로세움 공식 예약", "https://www.colosseo.it/en/", "입장권은 30분 단위로 예약한다.",
                    LocalDateTime.parse("2026-04-26T21:16:00")),
            new Source("바티칸 박물관", "https://www.museivaticani.va/", "일요일은 휴관, 마지막 주 일요일만 연다.",
                    LocalDateTime.parse("2026-04-26T21:40:00")),
            new Source("트라스테베레 산책", "https://www.turismoroma.it/en/places/trastevere", "저녁 산책 코스로 좋다.",
                    LocalDateTime.parse("2026-04-26T22:05:00")),
            new Source("로마 맛집", "https://www.tripadvisor.co.kr/Restaurants-g187791-Rome_Lazio.html", "카르보나라 맛집 모음.",
                    LocalDateTime.parse("2026-04-26T22:31:00")));

    /**
     * FE 목 MOCK_SESSIONS(9001~9007)와 같은 제목 · 시각 · 페이지 수 · 즐겨찾기 · 태그. 정렬 확인을 위해 일부러 섞어서 저장한다.
     */
    protected MockSessions seedMockSessions(Member owner) {
        BrowsingSession paris = session(owner, "파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)",
                "2026-04-24T10:05:00", "2026-04-24T11:30:00", 14, true, false,
                List.of("파리", "미술관", "루브르", "오르세"),
                List.of(new Source("루브르 박물관", "https://www.louvre.fr/en", "수요일 · 금요일은 야간 개장."),
                        new Source("오르세 미술관", "https://www.musee-orsay.fr/en", "인상파 컬렉션이 모여 있다.")),
                "# 파리 미술관 비교");
        BrowsingSession prague = session(owner, "프라하 1박 일정 짜기",
                "2026-04-20T23:05:00", "2026-04-20T23:32:00", 6, false, false,
                List.of("프라하", "카를교", "체코"),
                List.of(new Source("프라하 성", "https://www.hrad.cz/en/prague-castle-for-visitors", "성 내부는 통합권으로 본다.")),
                "# 프라하 1박");
        BrowsingSession rome = session(owner, "로마 3박 4일 동선 짜기",
                "2026-04-26T21:14:00", "2026-04-26T22:48:00", 18, true, false,
                List.of("로마", "콜로세움", "바티칸", "먹거리"), ROME_SOURCES, ROME_MARKDOWN);
        BrowsingSession visa = session(owner, "쉥겐 비자 & 입국 서류",
                "2026-04-21T09:40:00", "2026-04-21T10:08:00", 5, false, false,
                List.of("비자", "쉥겐", "ETIAS"),
                List.of(new Source("ETIAS 안내", "https://travel-europe.europa.eu/etias_en", "2026년부터 사전 허가가 필요하다.")),
                "# 쉥겐 비자");
        BrowsingSession flight = session(owner, "유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)",
                "2026-04-25T14:02:00", "2026-04-25T15:21:00", 11, false, false,
                List.of("항공권", "스카이스캐너", "예산"),
                List.of(new Source("스카이스캐너", "https://www.skyscanner.co.kr/", "다구간 검색으로 비교한다."),
                        new Source("카약", "https://www.kayak.co.kr/", "가격 변동 알림을 켠다.")),
                "# 항공권 비교");
        BrowsingSession barca = session(owner, "바르셀로나 가우디 투어",
                "2026-04-22T22:30:00", "2026-04-22T23:15:00", 9, false, false,
                List.of("바르셀로나", "가우디", "사그라다파밀리아"),
                List.of(new Source("사그라다 파밀리아", "https://sagradafamilia.org/en/", "탑 입장은 별도 예약.")),
                "# 가우디 투어");
        BrowsingSession rail = session(owner, "유레일패스 vs 개별 구매",
                "2026-04-23T19:12:00", "2026-04-23T19:51:00", 7, false, false,
                List.of("유레일", "예산", "기차"),
                List.of(new Source("Booking.com", "https://www.booking.com/", "역 근처 숙소 비교.")),
                "# 유레일 비교");
        return new MockSessions(rome, flight, paris, rail, barca, visa, prague);
    }

    /** MOCK_SESSIONS 순서 = 종료 시각 최신순. */
    protected record MockSessions(BrowsingSession rome, BrowsingSession flight, BrowsingSession paris,
            BrowsingSession rail, BrowsingSession barca, BrowsingSession visa, BrowsingSession prague) {

        List<BrowsingSession> newestFirst() {
            return List.of(rome, flight, paris, rail, barca, visa, prague);
        }
    }

    // ---------- 다른 사용자(wanderer) 시드 ----------

    /**
     * FE otherProfile("wanderlust") 을 닮은 다른 사용자. 닉네임 규칙(영문 시작 3~8자) 때문에 "wanderer" 로 줄였다.
     * <ul>
     *     <li>공개 세션: 프라하 · 비엔나 · 야간열차 (+ 카페 태그가 섞인 세션)</li>
     *     <li>비공개 해시태그: 카페, 슬로우트래블 → 다른 사람에겐 태그와 그 세션 · 노드가 안 보인다 (COM-1-2)</li>
     *     <li>비공개 세션: 슬로우트래블 메모</li>
     *     <li>핀고정(공유) 세션: 프라하 → 카페 → 야간열차 순 (카페 세션은 다른 사람에겐 빠진다)</li>
     *     <li>관심 해시태그: 프라하, 비엔나, 야간열차, 카페투어, 카페(비공개)</li>
     * </ul>
     */
    protected Wanderer seedWanderer() {
        Member wanderer = member("wanderer");
        BrowsingSession prague = session(wanderer, "프라하 1박 일정 짜기",
                "2026-04-20T23:05:00", "2026-04-20T23:32:00", 6, false, true,
                List.of("프라하", "카를교", "체코"),
                List.of(new Source("프라하 성", "https://www.hrad.cz/en/prague-castle-for-visitors", "성 내부는 통합권으로 본다."),
                        new Source("프라하 관광청", "https://www.prague.eu/en", "카를교는 새벽이 한산하다.")),
                "# 프라하 1박\n\n카를교 → 프라하 성 순서로 걷는다.");
        BrowsingSession vienna = session(wanderer, "비엔나 미술관 동선",
                "2026-04-18T10:00:00", "2026-04-18T11:00:00", 8, false, true,
                List.of("비엔나"),
                List.of(new Source("비엔나 관광청", "https://www.wien.info/en", "링 슈트라세를 따라 걷는다."),
                        new Source("벨베데레", "https://www.belvedere.at/en", "클림트 키스가 있다.")),
                "# 비엔나 미술관");
        BrowsingSession cafe = session(wanderer, "빈 카페 투어",
                "2026-04-17T15:00:00", "2026-04-17T16:00:00", 4, false, true,
                List.of("비엔나", "카페"),
                List.of(new Source("카페 센트럴", "https://www.cafecentral.wien/en/", "멜랑주를 시킨다.")),
                "# 빈 카페");
        BrowsingSession nightjet = session(wanderer, "나이트젯 야간열차 예약",
                "2026-04-19T21:00:00", "2026-04-19T21:40:00", 5, false, true,
                List.of("야간열차", "프라하"),
                List.of(new Source("ÖBB 나이트젯", "https://www.nightjet.com/en", "침대칸은 일찍 마감된다."),
                        new Source("체코 철도", "https://www.cd.cz/en/", "프라하 중앙역 출발.")),
                "# 야간열차");
        BrowsingSession slow = session(wanderer, "슬로우트래블 메모",
                "2026-04-16T20:00:00", "2026-04-16T20:30:00", 3, false, false,
                List.of("슬로우트래블"),
                List.of(new Source("이탈리아 소도시", "https://www.italia.it/en", "오르비에토 하루.")),
                "# 메모");

        CommunityProfile profile = new CommunityProfile(wanderer.getId());
        profile.replaceHiddenHashtags(List.of("카페", "슬로우트래블"));
        profile.replaceInterests(List.of("프라하", "비엔나", "야간열차", "카페투어", "카페"));
        profile.replacePinnedSessionIds(List.of(prague.getId(), cafe.getId(), nightjet.getId()));
        communityProfiles.saveAndFlush(profile);
        return new Wanderer(wanderer, prague, vienna, cafe, nightjet, slow);
    }

    protected record Wanderer(Member member, BrowsingSession prague, BrowsingSession vienna, BrowsingSession cafe,
            BrowsingSession nightjet, BrowsingSession slow) {

        Long id() {
            return member.getId();
        }
    }

    /** 커뮤니티 해시태그 id 는 주인 계정 기준 GET /community/me/hashtags 로 얻는다. */
    protected Long hashtagId(Member owner, String hashtag) throws Exception {
        String body = mvc.perform(get("/community/me/hashtags").with(as(owner)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Number> found = JsonPath.read(body, "$.data[?(@.hashtag == '" + hashtag + "')].hashtagId");
        if (found.isEmpty()) {
            throw new IllegalStateException("해시태그 없음: " + hashtag);
        }
        return found.get(0).longValue();
    }

    /** 마인드맵 노드 id 는 GET /community/profiles/{id}/mindmap 의 sourceUrl 로 찾는다. */
    protected Long nodeId(Member viewer, Member owner, String sourceUrl) throws Exception {
        String body = mvc.perform(get("/community/profiles/{id}/mindmap", owner.getId()).with(as(viewer)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Number> found = JsonPath.read(body, "$.data.nodes[?(@.sourceUrl == '" + sourceUrl + "')].nodeId");
        if (found.isEmpty()) {
            throw new IllegalStateException("노드 없음: " + sourceUrl);
        }
        return found.get(0).longValue();
    }
}
