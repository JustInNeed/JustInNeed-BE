package com.justinneed.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 정책 #24: API 명세 파일(레포 루트 openapi.json)이 지금 코드와 같은지 검사한다.
 * FE 는 이 파일로 타입을 만든다. 운영 Swagger 는 꺼져 있어 이 파일이 FE 가 보는 명세다.
 *
 * <pre>
 * 컨트롤러 · DTO 를 바꿔 이 테스트가 실패하면 명세를 다시 만들어 같이 커밋한다:
 *   UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiSpecTest'
 * </pre>
 * 실행 환경마다 달라지는 servers(호스트 주소)는 빼고, 키를 정렬해 저장한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecTest {

    private static final Path SPEC_FILE = Path.of("openapi.json");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("레포 루트 openapi.json 이 지금 코드의 API 명세와 같다")
    void specFileMatchesCode() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String current = normalize(body);

        if (System.getenv("UPDATE_OPENAPI") != null) {
            Files.writeString(SPEC_FILE, current, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(SPEC_FILE))
                .as("openapi.json 이 없습니다. UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiSpecTest' 로 만드세요.")
                .isTrue();
        assertThat(Files.readString(SPEC_FILE, StandardCharsets.UTF_8))
                .as("API 가 바뀌었는데 openapi.json 이 그대로입니다. UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiSpecTest' 로 다시 만들어 같이 커밋하세요.")
                .isEqualTo(current);
    }

    private static String normalize(String body) throws Exception {
        JsonNode tree = JSON.readTree(body);
        ((ObjectNode) tree).remove("servers");
        // 트리를 Map 으로 바꿔 ORDER_MAP_ENTRIES_BY_KEYS 가 모든 단계의 키를 정렬하게 한다
        Object sorted = JSON.treeToValue(tree, Object.class);
        return JSON.writeValueAsString(sorted) + "\n";
    }
}
