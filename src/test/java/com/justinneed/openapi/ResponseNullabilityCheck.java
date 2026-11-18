package com.justinneed.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

/**
 * 정책 #24: 테스트가 MockMvc 로 받은 모든 성공 응답(2xx JSON)을 API 명세(레포 루트 openapi.json)와 대조한다. 명세가 "항상
 * 옴"(required) · "null 아님"으로 적은 필드가 응답에서 빠지거나 null 이면 그 테스트를 실패시킨다.
 *
 * <p>실패하면 그 필드가 정말 null 일 수 있는지 보고, 그렇다면 DTO 필드에 {@code @Nullable}(org.jspecify) 을 붙인 뒤 명세를 다시 만든다:
 * {@code UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiSpecTest'}
 *
 * <p>@AutoConfigureMockMvc 를 쓰는 테스트 전체에 자동으로 걸린다 (테스트 코드의 @Component 라 운영에는 들어가지 않는다).
 */
@Component
class ResponseNullabilityCheck implements MockMvcBuilderCustomizer {

    private static final Path SPEC_FILE = Path.of("openapi.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REF_PREFIX = "#/components/schemas/";
    private static JsonNode spec;

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.alwaysDo(ResponseNullabilityCheck::check);
    }

    static void check(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String contentType = result.getResponse().getContentType();
        if (status < 200
                || status >= 300
                || contentType == null
                || !contentType.contains("json")
                || result.getRequest().getRequestURI().startsWith("/v3/api-docs")) {
            return;
        }
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return;
        }
        JsonNode schema =
                responseSchema(
                        result.getRequest().getMethod(), result.getRequest().getRequestURI());
        if (schema == null) {
            return; // 명세에 없는 경로는 OpenApiSpecTest 몫
        }
        List<String> violations = new ArrayList<>();
        walk(JSON.readTree(body), schema, "$", violations);
        if (!violations.isEmpty()) {
            throw new AssertionError(
                    "응답이 API 명세의 null 규칙과 다릅니다 ("
                            + result.getRequest().getMethod()
                            + " "
                            + result.getRequest().getRequestURI()
                            + "). 정말 null 일 수 있으면 DTO 필드에 @Nullable 을 붙이고 명세를 다시 만드세요:\n  "
                            + String.join("\n  ", violations));
        }
    }

    private static JsonNode responseSchema(String method, String uri) throws IOException {
        JsonNode paths = spec().path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String regex = entry.getKey().replaceAll("\\{[^/]+}", "[^/]+");
            if (!Pattern.matches(regex, uri)) {
                continue;
            }
            JsonNode responses = entry.getValue().path(method.toLowerCase()).path("responses");
            for (Iterator<Map.Entry<String, JsonNode>> codes = responses.fields();
                    codes.hasNext(); ) {
                Map.Entry<String, JsonNode> code = codes.next();
                if (!code.getKey().startsWith("2")) {
                    continue;
                }
                for (JsonNode media : code.getValue().path("content")) {
                    if (media.has("schema")) {
                        return media.get("schema");
                    }
                }
            }
        }
        return null;
    }

    private static void walk(JsonNode value, JsonNode schema, String at, List<String> out)
            throws IOException {
        schema = resolve(schema);
        if (schema.has("anyOf")) {
            if (value.isNull()) {
                return; // anyOf 는 null 허용 표시에만 쓴다
            }
            for (JsonNode option : schema.get("anyOf")) {
                if (!isNullType(option)) {
                    walk(value, option, at, out);
                }
            }
            return;
        }
        if (value.isNull()) {
            if (!allowsNull(schema)) {
                out.add(at + " 가 null (명세: null 아님)");
            }
            return;
        }
        if (value.isArray() && schema.has("items")) {
            int i = 0;
            for (JsonNode item : value) {
                walk(item, schema.get("items"), at + "[" + i++ + "]", out);
            }
            return;
        }
        if (!value.isObject() || !schema.has("properties")) {
            return;
        }
        for (JsonNode name : schema.path("required")) {
            if (!value.has(name.asText())) {
                out.add(at + "." + name.asText() + " 가 빠짐 (명세: required)");
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.get("properties").fields();
                it.hasNext(); ) {
            Map.Entry<String, JsonNode> property = it.next();
            if (value.has(property.getKey())) {
                walk(
                        value.get(property.getKey()),
                        property.getValue(),
                        at + "." + property.getKey(),
                        out);
            }
        }
    }

    private static JsonNode resolve(JsonNode schema) throws IOException {
        while (schema.has("$ref")) {
            String name = schema.get("$ref").asText().substring(REF_PREFIX.length());
            schema = spec().path("components").path("schemas").path(name);
        }
        return schema;
    }

    private static boolean isNullType(JsonNode schema) {
        JsonNode type = schema.get("type");
        return type != null && type.isTextual() && type.asText().equals("null");
    }

    private static boolean allowsNull(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return true; // 타입이 없는 스키마(아무 값)
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.asText().equals("null")) {
                    return true;
                }
            }
            return false;
        }
        return type.asText().equals("null");
    }

    private static synchronized JsonNode spec() throws IOException {
        if (spec == null) {
            spec = JSON.readTree(Files.readString(SPEC_FILE, StandardCharsets.UTF_8));
        }
        return spec;
    }
}
