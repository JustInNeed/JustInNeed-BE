package com.justinneed.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.RefreshToken;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.repository.RefreshTokenRepository;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.global.common.ApiResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 서버 · 실제 스레드로 보내는 인증 요청. 동시 요청은 트랜잭션이 각각 커밋돼야 재현되므로
 * ScenarioTestSupport(@Transactional) 를 쓰지 않고, 전용 H2 DB 에 커밋한다.
 * 동시 요청이 겹치는 정도는 매번 달라서 한 판이 아니라 여러 판을 돌린다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:auth-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@DisplayName("실제 서버로 확인하는 인증 요청")
class AuthOverHttpTest {

    private static final int ROUNDS = 10;
    private static final String INVALID_REFRESH_MESSAGE = "유효하지 않은 리프레시 토큰입니다.";

    @LocalServerPort
    private int port;

    @Autowired
    private MemberRepository members;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private JwtTokenProvider jwt;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("같은 refresh 토큰으로 동시에 재발급하면 한 요청만 200, 나머지는 401 이고 새 토큰 행 하나만 남으며 그 토큰으로 다시 재발급된다")
    void concurrentRefreshWithSameToken() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long memberId = newMember().getId();
            String refreshToken = authTokenService.issue(memberId).refreshToken();

            List<HttpResponse<String>> responses = race(refresh(refreshToken), refresh(refreshToken));

            assertThat(statuses(responses)).containsExactly(200, 401);
            HttpResponse<String> loser = responses.get(1);
            assertThat(JsonPath.<Boolean>read(loser.body(), "$.success")).isFalse();
            assertThat(JsonPath.<String>read(loser.body(), "$.message")).isEqualTo(INVALID_REFRESH_MESSAGE);
            assertThat(refreshRowsOf(memberId)).hasSize(1);

            String newRefreshToken = JsonPath.read(responses.get(0).body(), "$.data.refreshToken");
            assertThat(send(refresh(newRefreshToken)).statusCode()).isEqualTo(200);
            assertThat(send(refresh(refreshToken)).statusCode()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("access 토큰이 만료돼 refresh 토큰만 보내는 로그아웃을 동시에 해도(버튼 연타) 둘 다 200 이고 토큰 행은 남지 않는다")
    void concurrentLogoutWithSameToken() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long memberId = newMember().getId();
            String refreshToken = authTokenService.issue(memberId).refreshToken();

            List<HttpResponse<String>> responses = race(logout(refreshToken), logout(refreshToken));

            assertThat(statuses(responses)).containsExactly(200, 200);
            assertThat(refreshRowsOf(memberId)).isEmpty();
        }
    }

    @Test
    @DisplayName("두 회원이 같은 닉네임을 동시에 저장하면 한 명만 200, 다른 한 명은 409 '이미 사용 중인 닉네임입니다.' 이고 닉네임 행은 하나다")
    void concurrentSameNickname() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String nickname = "race" + round;

            List<HttpResponse<String>> responses = race(
                    updateNickname(newMember(), nickname),
                    updateNickname(newMember(), nickname));

            assertThat(statuses(responses)).containsExactly(200, 409);
            assertThat(JsonPath.<String>read(responses.get(0).body(), "$.data.nickname")).isEqualTo(nickname);
            assertThat(responses.get(1).body())
                    .isEqualTo(errorJson("이미 사용 중인 닉네임입니다."));
            assertThat(members.findAll()).filteredOn(member -> nickname.equals(member.getNickname())).hasSize(1);
        }
    }

    @Test
    @DisplayName("실제 서버(Tomcat)의 401 도 UTF-8 JSON 공통 에러 응답이고, FE 출처면 CORS 헤더가 붙는다")
    void unauthorizedOverRealServer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/members/me"))
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue("application/json;charset=UTF-8");
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .hasValue("http://localhost:3000");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(errorJson("로그인이 만료되었습니다. 다시 로그인해 주세요."));
    }

    private Member newMember() {
        return members.save(new Member(SocialProvider.KAKAO, "http-" + UUID.randomUUID(), null, null));
    }

    private List<RefreshToken> refreshRowsOf(Long memberId) {
        return refreshTokens.findAll().stream()
                .filter(row -> row.getMemberId().equals(memberId))
                .toList();
    }

    private HttpRequest refresh(String refreshToken) {
        return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private HttpRequest logout(String refreshToken) {
        return post("/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private HttpRequest updateNickname(Member member, String nickname) {
        return HttpRequest.newBuilder(uri("/members/me/nickname"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(member.getId()))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"" + nickname + "\"}"))
                .build();
    }

    private HttpRequest post(String path, String json) {
        return HttpRequest.newBuilder(uri(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 두 요청을 최대한 같은 순간에 보내고 응답을 상태 코드 오름차순으로 돌려준다. */
    private List<HttpResponse<String>> race(HttpRequest first, HttpRequest second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (HttpRequest request : List.of(first, second)) {
            Callable<HttpResponse<String>> call = () -> {
                ready.countDown();
                start.await();
                return send(request);
            };
            futures.add(pool.submit(call));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        responses.sort(Comparator.comparingInt(HttpResponse::statusCode));
        return responses;
    }

    private String errorJson(String message) {
        return objectMapper.writeValueAsString(ApiResponse.error(message));
    }

    private List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).toList();
    }
}
