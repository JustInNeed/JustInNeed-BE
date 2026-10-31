package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.settings.collection.domain.ExcludedUrl;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 설정 화면 (/settings): 데이터 수집(수집 제외 URL) · 확장 프로그램 · 외부 플랫폼 · 계정(탈퇴).
 * FE: src/features/settings/excludedUrl.ts(ExcludedUrlParser 와 같은 규칙이라고 적힘), SettingsView.tsx(최대 100개),
 * WithdrawSection.tsx(confirmText "WITHDRAW").
 */
@DisplayName("설정 화면")
class SettingsScenarioTest extends ScenarioTestSupport {

    private static final String EXCLUDED_URLS = "/settings/collection/excluded-urls";
    private static final String INVALID_URL = "올바른 URL 형식이 아닙니다.";
    private static final String URL_NOT_FOUND = "수집 제외 URL을 찾을 수 없습니다.";

    @Autowired
    private ExcludedUrlRepository excludedUrls;

    @Autowired
    private AuthTokenService authTokenService;

    private ResultActions postUrl(Member me, String url) throws Exception {
        return mvc.perform(post(EXCLUDED_URLS).with(as(me)).with(json(Map.of("url", url))));
    }

    private Long addUrlId(Member me, String url) throws Exception {
        return readLong(postUrl(me, url).andExpect(status().isCreated()), "$.data.excludedUrlId");
    }

    private Long urlId(Member me, String url) throws Exception {
        List<Number> found = read(mvc.perform(get(EXCLUDED_URLS).with(as(me))),
                "$.data.excludedUrls[?(@.url == '" + url + "')].excludedUrlId");
        return found.get(0).longValue();
    }

    @Nested
    @DisplayName("데이터 수집: 수집 제외 URL")
    class ExcludedUrls {

        @Test
        @DisplayName("처음 열면 기본 제외 URL 5개가 체크된 채로 도메인 순으로 온다 (MEM-5-1)")
        void defaultsOnFirstOpen() throws Exception {
            Member me = member("sprout");

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.message").value("수집 제외 URL 목록을 조회했습니다."))
                    .andExpect(jsonPath("$.data.code").value(200))
                    .andExpect(jsonPath("$.data.excludedUrls[*].url").value(contains(
                            "instagram.com", "mail.google.com", "tiktok.com", "twitter.com", "youtube.com/shorts")))
                    .andExpect(jsonPath("$.data.excludedUrls[0].excludedUrlId").isNumber())
                    .andExpect(jsonPath("$.data.excludedUrls[4].domain").value("youtube.com"))
                    .andExpect(jsonPath("$.data.excludedUrls[*].isDefault")
                            .value(contains(true, true, true, true, true)))
                    .andExpect(jsonPath("$.data.excludedUrls[*].isExcluded")
                            .value(contains(true, true, true, true, true)));
        }

        @Test
        @DisplayName("URL 을 추가하면 201 과 표시형 URL(프로토콜 · 끝 슬래시 제거, 소문자 도메인)이 체크된 상태로 온다 (MEM-5-3)")
        void addedUrlIsNormalized() throws Exception {
            Member me = member("sprout");

            mvc.perform(post(EXCLUDED_URLS).with(as(me)).with(json(Map.of("url", "HTTPS://Tistory.com/feed/"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("수집 제외 URL이 추가되었습니다."))
                    .andExpect(jsonPath("$.data.code").value(201))
                    .andExpect(jsonPath("$.data.excludedUrlId").isNumber())
                    .andExpect(jsonPath("$.data.url").value("tistory.com/feed"))
                    .andExpect(jsonPath("$.data.domain").value("tistory.com"))
                    .andExpect(jsonPath("$.data.isDefault").value(false))
                    .andExpect(jsonPath("$.data.isExcluded").value(true))
                    .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));
        }

        @Test
        @DisplayName("정규화하면 같은 URL(기본 URL 포함)을 또 추가하면 409 '이미 등록된 URL입니다.'")
        void duplicateUrl() throws Exception {
            Member me = member("sprout");
            addUrlId(me, "https://tistory.com/feed");

            postUrl(me, "https://TISTORY.com/feed/").andExpect(errorEnvelope(HttpStatus.CONFLICT, "이미 등록된 URL입니다."));
            postUrl(me, "https://www.instagram.com/").andExpect(errorEnvelope(HttpStatus.CONFLICT, "이미 등록된 URL입니다."));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"ftp://tistory.com", "https://tistory .com", "https://user@tistory.com",
                "https://localhost", "https://tistory.c", "javascript:alert(1)", "not-a-url"})
        @DisplayName("형식이 틀린 URL(http/https 아님 · 공백 · 계정정보 · TLD 없음)은 400 '올바른 URL 형식이 아닙니다.'")
        void invalidUrl(String url) throws Exception {
            Member me = member("sprout");

            postUrl(me, url).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_URL));
        }

        @Test
        @DisplayName("URL 칸을 비우고 추가하면 400 'URL을 입력해 주세요.'")
        void blankUrl() throws Exception {
            Member me = member("sprout");

            postUrl(me, "").andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "URL을 입력해 주세요."));
            postUrl(me, "   ").andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "URL을 입력해 주세요."));
        }

        @Test
        @DisplayName("기본 URL 포함 100개가 차면 더 추가할 수 없다 (400 'URL은 최대 100개까지 등록할 수 있습니다.')")
        void limitIsHundredIncludingDefaults() throws Exception {
            Member me = member("sprout");
            mvc.perform(get(EXCLUDED_URLS).with(as(me))).andExpect(okEnvelope()); // 기본 URL 5개 생성
            List<ExcludedUrl> filler = new ArrayList<>();
            for (int i = 0; i < 94; i++) {
                filler.add(new ExcludedUrl(me.getId(), "site" + i + ".com", "site" + i + ".com", false));
            }
            excludedUrls.saveAllAndFlush(filler); // 기본 5 + 94 = 99개

            postUrl(me, "https://hundredth.com").andExpect(status().isCreated());
            postUrl(me, "https://over.com")
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "URL은 최대 100개까지 등록할 수 있습니다."));
        }

        @Test
        @DisplayName("체크를 해제하면(MEM-5-2) 수집 제외가 꺼지고, 목록에서 체크된 항목 뒤로 간다")
        void uncheckedGoLast() throws Exception {
            Member me = member("sprout");
            Long tistory = addUrlId(me, "https://tistory.com/feed");
            addUrlId(me, "https://naver.com/blog");
            Long mail = urlId(me, "mail.google.com");

            mvc.perform(patch(EXCLUDED_URLS + "/selections").with(as(me))
                            .with(json(Map.of("excludedUrlIds", List.of(mail, tistory), "isExcluded", false))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.message").value("URL 선택 상태가 변경되었습니다."))
                    .andExpect(jsonPath("$.data.updatedCount").value(2))
                    .andExpect(jsonPath("$.data.isExcluded").value(false))
                    .andExpect(jsonPath("$.data.updatedUrlIds").value(contains(ids(mail, tistory))));

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(jsonPath("$.data.excludedUrls[*].url").value(contains(
                            "instagram.com", "naver.com/blog", "tiktok.com", "twitter.com", "youtube.com/shorts",
                            "mail.google.com", "tistory.com/feed")))
                    .andExpect(jsonPath("$.data.excludedUrls[*].isExcluded")
                            .value(contains(true, true, true, true, true, false, false)));
        }

        @Test
        @DisplayName("같은 도메인 URL 이 붙어서 URL 순으로 온다")
        void groupedByDomain() throws Exception {
            Member me = member("sprout");
            // FE 목 DEFAULT_EXCLUDED 의 사용자 추가 URL
            for (String url : List.of("https://tistory.com/feed", "https://tistory.com/skin", "https://tistory.com",
                    "https://naver.com/blog")) {
                addUrlId(me, url);
            }

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(jsonPath("$.data.excludedUrls[*].url").value(contains(
                            "instagram.com", "mail.google.com", "naver.com/blog",
                            "tiktok.com", "tistory.com", "tistory.com/feed", "tistory.com/skin",
                            "twitter.com", "youtube.com/shorts")));
        }

        @Test
        @DisplayName("선택 삭제(MEM-5-4): 내가 추가한 URL 은 지워지고 기본 URL 은 체크만 해제된다")
        void deleteKeepsDefaults() throws Exception {
            Member me = member("sprout");
            Long tistory = addUrlId(me, "https://tistory.com/feed");
            Long instagram = urlId(me, "instagram.com");

            mvc.perform(delete(EXCLUDED_URLS).with(as(me))
                            .with(json(Map.of("excludedUrlIds", List.of(instagram, tistory)))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.message").value("선택한 URL이 삭제되었습니다."))
                    .andExpect(jsonPath("$.data.deletedUrlIds").value(contains(ids(tistory))))
                    .andExpect(jsonPath("$.data.uncheckedDefaultUrlIds").value(contains(ids(instagram))))
                    .andExpect(jsonPath("$.data.deletedCount").value(1));

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(jsonPath("$.data.excludedUrls[*].url").value(not(hasItem("tistory.com/feed"))))
                    .andExpect(jsonPath("$.data.excludedUrls[?(@.url == 'instagram.com')].isExcluded")
                            .value(contains(false)));
        }

        @Test
        @DisplayName("다른 사람 URL id 나 없는 id 가 섞이면 변경 · 삭제 모두 404 이고 내 URL 도 그대로다")
        void foreignIdIs404() throws Exception {
            Member me = member("sprout");
            Member other = member("wanderer");
            Long mine = addUrlId(me, "https://tistory.com/feed");
            Long theirs = addUrlId(other, "https://naver.com/blog");

            mvc.perform(patch(EXCLUDED_URLS + "/selections").with(as(me))
                            .with(json(Map.of("excludedUrlIds", List.of(mine, theirs), "isExcluded", false))))
                    .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, URL_NOT_FOUND));
            mvc.perform(delete(EXCLUDED_URLS).with(as(me)).with(json(Map.of("excludedUrlIds", List.of(mine, 999_999)))))
                    .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, URL_NOT_FOUND));

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(jsonPath("$.data.excludedUrls[?(@.url == 'tistory.com/feed')].isExcluded")
                            .value(contains(true)));
        }

        @Test
        @DisplayName("아무것도 고르지 않고 삭제 · 변경하거나 체크 여부를 빠뜨리면 400 안내 문구")
        void emptySelection() throws Exception {
            Member me = member("sprout");
            Long mail = urlId(me, "mail.google.com");

            mvc.perform(delete(EXCLUDED_URLS).with(as(me)).with(json(Map.of("excludedUrlIds", List.of()))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "삭제할 URL을 선택해 주세요."));
            mvc.perform(patch(EXCLUDED_URLS + "/selections").with(as(me))
                            .with(json(Map.of("excludedUrlIds", List.of(), "isExcluded", true))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "변경할 URL을 선택해 주세요."));
            mvc.perform(patch(EXCLUDED_URLS + "/selections").with(as(me)).with(json(Map.of("excludedUrlIds", List.of(mail)))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "수집 제외 적용 여부를 입력해 주세요."));
        }

        @Test
        @DisplayName("프로토콜 없이 입력한 URL(tistory.com/feed)도 받아서 표시형으로 저장한다")
        void urlWithoutScheme() throws Exception {
            Member me = member("sprout");

            postUrl(me, "tistory.com/feed")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.url").value("tistory.com/feed"))
                    .andExpect(jsonPath("$.data.domain").value("tistory.com"));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"instagram.com", "https://instagram.com", "http://instagram.com",
                "https://www.instagram.com/", "HTTP://WWW.Instagram.com:443"})
        @DisplayName("프로토콜 · www · 끝 슬래시 · 기본 포트만 다른 URL 은 같은 URL 이라 409 다")
        void sameUrlInOtherForms(String url) throws Exception {
            Member me = member("sprout");
            mvc.perform(get(EXCLUDED_URLS).with(as(me))).andExpect(okEnvelope()); // 기본 instagram.com

            postUrl(me, url).andExpect(errorEnvelope(HttpStatus.CONFLICT, "이미 등록된 URL입니다."));
        }

        @Test
        @DisplayName("저장할 수 없을 만큼 긴 URL 은 500 이 아니라 400 '올바른 URL 형식이 아닙니다.' 다")
        void tooLongUrl() throws Exception {
            Member me = member("sprout");

            postUrl(me, "https://tistory.com/" + "a".repeat(3000))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_URL));
        }

        @Test
        @DisplayName("기본 제외 URL 은 디자인 목처럼 5개이고 100개가 넘어도 채운다")
        void defaultsMatchDesign() throws Exception {
            Member me = member("sprout");
            List<ExcludedUrl> filler = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                filler.add(new ExcludedUrl(me.getId(), "site" + i + ".com", "site" + i + ".com", false));
            }
            excludedUrls.saveAllAndFlush(filler);

            mvc.perform(get(EXCLUDED_URLS).with(as(me)))
                    .andExpect(jsonPath("$.data.excludedUrls[?(@.isDefault == true)].url").value(containsInAnyOrder(
                            "instagram.com", "mail.google.com", "youtube.com/shorts", "twitter.com", "tiktok.com")))
                    .andExpect(jsonPath("$.data.excludedUrls", hasSize(105)));
        }
    }

    @Nested
    @DisplayName("확장 프로그램")
    class Extension {

        @Test
        @DisplayName("팝업 노출(MEM-6)을 끄면 바뀐 값만 담아 응답하고, 다시 켜면 true 로 온다")
        void popupVisibleToggle() throws Exception {
            Member me = member("sprout");

            mvc.perform(patch("/settings/extension/popup-visible").with(as(me)).with(json(Map.of("enabled", false))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value(me.getId()))
                    .andExpect(jsonPath("$.data.popupVisible").value(false))
                    .andExpect(jsonPath("$.data.updatedAt").value(notNullValue()))
                    .andExpect(jsonPath("$.data.autoEndNotificationEnabled").doesNotExist())
                    .andExpect(jsonPath("$.data.manualScrapEnabled").doesNotExist());
            mvc.perform(patch("/settings/extension/popup-visible").with(as(me)).with(json(Map.of("enabled", true))))
                    .andExpect(jsonPath("$.data.popupVisible").value(true));
        }

        @Test
        @DisplayName("토글 값(enabled)을 빠뜨리면 400")
        void missingEnabled() throws Exception {
            Member me = member("sprout");

            mvc.perform(patch("/settings/extension/popup-visible").with(as(me)).with(json("{}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").isString());
        }

        @Test
        @DisplayName("자동 종료 알림 · 수동 스크랩 토글도 같은 모양으로 그 값만 담아 응답한다 (A표 보류 항목이지만 API 는 있음)")
        void otherToggles() throws Exception {
            Member me = member("sprout");

            mvc.perform(patch("/settings/extension/auto-end-notification").with(as(me)).with(json(Map.of("enabled", false))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.autoEndNotificationEnabled").value(false))
                    .andExpect(jsonPath("$.data.popupVisible").doesNotExist());
            mvc.perform(patch("/settings/extension/manual-scrap").with(as(me)).with(json(Map.of("enabled", false))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.manualScrapEnabled").value(false))
                    .andExpect(jsonPath("$.data.popupVisible").doesNotExist());
        }

        @Test
        @Disabled("API 미구현: GET /settings/extension 현재 설정값 조회 (D표 '확장 프로그램 설정 조회' 반영 결정, "
                + "필드명은 PATCH 응답과 같게 제안) — 구현되면 켠다")
        @DisplayName("설정 화면을 열면 확장 프로그램 설정 현재 값(처음엔 모두 켜짐)을 불러온다")
        void currentExtensionSettings() throws Exception {
            Member me = member("sprout");

            mvc.perform(get("/settings/extension").with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.popupVisible").value(true))
                    .andExpect(jsonPath("$.data.autoEndNotificationEnabled").value(true))
                    .andExpect(jsonPath("$.data.manualScrapEnabled").value(true));
            mvc.perform(patch("/settings/extension/popup-visible").with(as(me)).with(json(Map.of("enabled", false))))
                    .andExpect(okEnvelope());
            mvc.perform(get("/settings/extension").with(as(me)))
                    .andExpect(jsonPath("$.data.popupVisible").value(false))
                    .andExpect(jsonPath("$.data.autoEndNotificationEnabled").value(true));
        }
    }

    @Nested
    @DisplayName("외부 플랫폼 연동")
    class Integrations {

        @Test
        @DisplayName("연동 전엔 Notion · Google Docs · 카카오톡 3개가 모두 미연결로 온다 (MEM-7-1)")
        void allDisconnectedAtFirst() throws Exception {
            Member me = member("sprout");

            mvc.perform(get("/settings/integrations").with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.integrations[*].provider").value(contains("notion", "googleDocs", "kakaoTalk")))
                    .andExpect(jsonPath("$.data.integrations[*].isConnected").value(contains(false, false, false)))
                    .andExpect(jsonPath("$.data.integrations[0].email").value(nullValue()));
        }

        @Test
        @DisplayName("이메일을 등록하면(MEM-7-3) 201 과 연결됨 상태가 오고 상태 목록에도 반영된다")
        void registerEmail() throws Exception {
            Member me = member("sprout");

            mvc.perform(post("/settings/integrations/notion/email").with(as(me))
                            .with(json(Map.of("email", "Sprout@Example.com"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.provider").value("notion"))
                    .andExpect(jsonPath("$.data.email").value("sprout@example.com"))
                    .andExpect(jsonPath("$.data.isConnected").value(true))
                    .andExpect(jsonPath("$.data.connectedAt").value(notNullValue()));

            mvc.perform(get("/settings/integrations").with(as(me)))
                    .andExpect(jsonPath("$.data.integrations[*].isConnected").value(contains(true, false, false)))
                    .andExpect(jsonPath("$.data.integrations[0].email").value("sprout@example.com"));
        }

        @Test
        @DisplayName("이메일 형식이 틀리면 400, 다른 사람이 이미 연동한 이메일이면 409, 모르는 플랫폼이면 400")
        void registerEmailFailures() throws Exception {
            Member me = member("sprout");
            Member other = member("wanderer");
            mvc.perform(post("/settings/integrations/notion/email").with(as(other))
                    .with(json(Map.of("email", "shared@example.com")))).andExpect(status().isCreated());

            mvc.perform(post("/settings/integrations/notion/email").with(as(me)).with(json(Map.of("email", "not-an-email"))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "올바른 이메일 형식이 아닙니다."));
            mvc.perform(post("/settings/integrations/notion/email").with(as(me)).with(json(Map.of("email", "shared@example.com"))))
                    .andExpect(errorEnvelope(HttpStatus.CONFLICT, "이미 연동된 이메일입니다."));
            mvc.perform(post("/settings/integrations/slack/email").with(as(me)).with(json(Map.of("email", "me@example.com"))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "지원하지 않는 외부 플랫폼입니다."));
        }

        @Test
        @DisplayName("연동 가이드(MEM-7-2)를 열면 제목과 단계별 안내가 온다")
        void guide() throws Exception {
            Member me = member("sprout");

            mvc.perform(get("/settings/integrations/notion/guide").with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.provider").value("notion"))
                    .andExpect(jsonPath("$.data.guideTitle").value("Notion 연동 방법"))
                    .andExpect(jsonPath("$.data.guideSteps", hasSize(4)));
        }
    }

    @Nested
    @DisplayName("계정: 회원 탈퇴")
    class Withdraw {

        @Test
        @DisplayName("확인 문구를 정확히 WITHDRAW 로 입력하면 탈퇴되고 message 로 완료 문구가 온다")
        void withdrawSucceeds() throws Exception {
            Member me = member("sprout");
            TokenResponse login = authTokenService.issue(me.getId());

            mvc.perform(delete("/settings/account").with(bearerToken(login.accessToken()))
                            .with(json(Map.of("confirmText", "WITHDRAW", "reason", "졸업해서요"))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.message").value("계정 탈퇴가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.userId").value(me.getId()))
                    .andExpect(jsonPath("$.data.withdrawn").value(true))
                    .andExpect(jsonPath("$.data.withdrawnAt").value(notNullValue()));
        }

        @Test
        @DisplayName("탈퇴하면 쓰던 access · refresh 토큰이 모두 무효가 된다")
        void withdrawRevokesTokens() throws Exception {
            Member me = member("sprout");
            TokenResponse login = authTokenService.issue(me.getId());

            mvc.perform(delete("/settings/account").with(bearerToken(login.accessToken()))
                    .with(json(Map.of("confirmText", "WITHDRAW")))).andExpect(okEnvelope());

            mvc.perform(get("/members/me").with(bearerToken(login.accessToken()))).andExpect(status().isUnauthorized());
            mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", login.refreshToken()))))
                    .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."));
        }

        @Test
        @DisplayName("탈퇴하면 계정의 소셜 식별자 · 닉네임 · 이메일이 비워져, 그 닉네임을 다른 사람이 바로 쓸 수 있다")
        void withdrawFreesNickname() throws Exception {
            Member me = member("sprout", SocialProvider.KAKAO, "sprout@kakao.com");
            Member other = member(null);

            mvc.perform(delete("/settings/account").with(as(me))
                    .with(json(Map.of("confirmText", "WITHDRAW", "reason", "졸업해서요")))).andExpect(okEnvelope());

            Member withdrawn = members.findById(me.getId()).orElseThrow();
            assertThat(withdrawn.isWithdrawn()).isTrue();
            assertThat(withdrawn.getProviderId()).isEqualTo("withdrawn:" + me.getId());
            assertThat(withdrawn.getNickname()).isNull();
            assertThat(withdrawn.getEmail()).isNull();
            mvc.perform(patch("/members/me/nickname").with(as(other)).with(json(Map.of("nickname", "sprout"))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.nickname").value("sprout"));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"withdraw", "WITHDRAW ", "탈퇴"})
        @DisplayName("확인 문구가 틀리면 400 '탈퇴 확인 문구가 올바르지 않습니다.' 이고 계정은 그대로다")
        void wrongConfirmText(String confirmText) throws Exception {
            Member me = member("sprout");

            mvc.perform(delete("/settings/account").with(as(me)).with(json(Map.of("confirmText", confirmText))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구가 올바르지 않습니다."));

            mvc.perform(get("/members/me").with(as(me))).andExpect(okEnvelope());
        }

        @Test
        @DisplayName("확인 문구를 비우면 400 '탈퇴 확인 문구를 입력해 주세요.', 사유가 500자를 넘으면 400")
        void withdrawValidation() throws Exception {
            Member me = member("sprout");

            mvc.perform(delete("/settings/account").with(as(me)).with(json(Map.of("confirmText", ""))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구를 입력해 주세요."));
            mvc.perform(delete("/settings/account").with(as(me))
                            .with(json(Map.of("confirmText", "WITHDRAW", "reason", "가".repeat(501)))))
                    .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "탈퇴 사유는 최대 500자까지 입력할 수 있습니다."));
        }

        @Test
        @DisplayName("로그인하지 않고 탈퇴를 요청하면 401")
        void withdrawRequiresLogin() throws Exception {
            mvc.perform(delete("/settings/account").with(json(Map.of("confirmText", "WITHDRAW"))))
                    .andExpect(status().isUnauthorized());
        }
    }
}
