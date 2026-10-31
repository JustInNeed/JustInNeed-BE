package com.justinneed.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.settings.collection.domain.ExcludedUrl;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import com.justinneed.settings.extension.domain.ExtensionSetting;
import com.justinneed.settings.extension.repository.ExtensionSettingRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

/**
 * 실제 서버 · 실제 스레드로 보내는 설정 요청. 동시 요청은 트랜잭션이 각각 커밋돼야 재현되므로
 * ScenarioTestSupport(@Transactional) 를 쓰지 않고, 전용 H2 DB 에 커밋한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:settings-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@DisplayName("실제 서버로 확인하는 설정 요청")
class SettingsOverHttpTest {

    private static final int ROUNDS = 10;
    private static final int THREADS = 4;
    private static final String EXCLUDED_URLS = "/settings/collection/excluded-urls";
    private static final List<String> TOGGLES = List.of("popup-visible", "auto-end-notification", "manual-scrap");

    @LocalServerPort
    private int port;

    @Autowired
    private MemberRepository members;

    @Autowired
    private ExcludedUrlRepository excludedUrls;

    @Autowired
    private ExtensionSettingRepository extensionSettings;

    @Autowired
    private JwtTokenProvider jwt;

    private final HttpClient client = HttpClient.newHttpClient();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("설정 화면을 동시에 처음 열어도 모두 200 이고 기본 URL 은 5개다")
    void concurrentFirstOpen() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Member member = newMember();
            HttpRequest open = request(member, EXCLUDED_URLS).GET().build();

            List<HttpResponse<String>> responses = race(List.of(open, open, open, open));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(excludedUrls.countByMemberId(member.getId())).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("같은 URL 을 동시에 추가하면 하나만 201, 나머지는 409 다")
    void concurrentSameUrl() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Member member = newMember();
            HttpRequest add = addUrl(member, "tistory.com/feed");

            List<HttpResponse<String>> responses = race(List.of(add, add, add, add));

            assertThat(statuses(responses)).containsExactly(201, 409, 409, 409);
        }
    }

    @Test
    @DisplayName("99개인 회원이 서로 다른 URL 을 동시에 추가해도 100개를 넘지 않는다")
    void concurrentAddKeepsLimit() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Member member = newMember();
            send(request(member, EXCLUDED_URLS).GET().build());
            List<ExcludedUrl> filler = new ArrayList<>();
            for (int i = 0; i < 94; i++) {
                filler.add(new ExcludedUrl(member.getId(), "site" + i + ".com", "site" + i + ".com", false));
            }
            excludedUrls.saveAll(filler);

            List<HttpResponse<String>> responses = race(List.of(addUrl(member, "race-a.com"), addUrl(member, "race-b.com"),
                    addUrl(member, "race-c.com"), addUrl(member, "race-d.com")));

            assertThat(statuses(responses)).containsExactly(201, 400, 400, 400);
            assertThat(excludedUrls.countByMemberId(member.getId())).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("확장 토글 3개를 동시에 처음 저장해도 모두 200 이고 세 값 모두 남는다")
    void concurrentToggles() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Member member = newMember();
            List<HttpRequest> saves = TOGGLES.stream().map(toggle -> saveToggle(member, toggle)).toList();

            List<HttpResponse<String>> responses = race(saves);

            assertThat(statuses(responses)).containsOnly(200);
            ExtensionSetting saved = extensionSettings.findByMemberId(member.getId()).orElseThrow();
            assertThat(saved.isPopupVisible()).isFalse();
            assertThat(saved.isAutoEndNotificationEnabled()).isFalse();
            assertThat(saved.isManualScrapEnabled()).isFalse();
        }
    }

    private Member newMember() {
        return members.save(new Member(SocialProvider.KAKAO, UUID.randomUUID().toString(), null, null));
    }

    private HttpRequest addUrl(Member member, String url) {
        return request(member, EXCLUDED_URLS)
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + url + "\"}"))
                .build();
    }

    private HttpRequest saveToggle(Member member, String toggle) {
        return request(member, "/settings/extension/" + toggle)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"enabled\":false}"))
                .build();
    }

    private HttpRequest.Builder request(Member member, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(member.getId()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private List<HttpResponse<String>> race(List<HttpRequest> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (HttpRequest request : requests) {
            Callable<HttpResponse<String>> call = () -> {
                ready.countDown();
                go.await();
                return send(request);
            };
            futures.add(pool.submit(call));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }

    private List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).sorted().toList();
    }
}
