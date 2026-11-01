package com.justinneed.scenario;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 크롬 확장 background 의 요청 (#114). 크롬은 확장 요청에 Origin: chrome-extension://<ID> 를 붙인다.
 * 허용 목록에 없으면 Spring 이 403 "Invalid CORS request" 로 거절해 확장 로그인이 안 된다.
 */
@DisplayName("크롬 확장 출처 CORS")
class ExtensionCorsScenarioTest extends ScenarioTestSupport {

    private static final String EXTENSION_ORIGIN = "chrome-extension://pgmclbgdbaiigahdhpnieemifhgoikpa";
    private static final String OTHER_EXTENSION_ORIGIN = "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FE_ORIGIN = "http://localhost:3000";

    @Test
    @DisplayName("확장 출처의 연결 코드 교환은 CORS 에 막히지 않고 원래 응답(틀린 코드 = 401)에 CORS 헤더가 붙는다")
    void extensionTokenExchange() throws Exception {
        mvc.perform(post("/auth/token")
                        .header(HttpHeaders.ORIGIN, EXTENSION_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wrong-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXTENSION_ORIGIN));
    }

    @Test
    @DisplayName("확장 출처의 사전 요청(preflight)도 통과한다")
    void extensionPreflight() throws Exception {
        mvc.perform(options("/members/me")
                        .header(HttpHeaders.ORIGIN, EXTENSION_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXTENSION_ORIGIN));
    }

    @Test
    @DisplayName("다른 확장 ID 는 여전히 403")
    void otherExtensionRejected() throws Exception {
        mvc.perform(get("/members/me").header(HttpHeaders.ORIGIN, OTHER_EXTENSION_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("확장 출처를 더해도 FE 출처는 그대로 허용된다")
    void feOriginStillAllowed() throws Exception {
        mvc.perform(options("/members/me")
                        .header(HttpHeaders.ORIGIN, FE_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FE_ORIGIN));
    }
}
