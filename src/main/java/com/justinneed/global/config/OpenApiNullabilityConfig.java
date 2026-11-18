package com.justinneed.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;

/**
 * 정책 #24: API 명세(openapi.json)에 응답 필드의 "항상 옴 · null 일 수 있음"을 적는다. FE 는 이 명세로 타입을 만든다.
 *
 * <ul>
 *   <li>응답 타입(이름이 Request 로 끝나지 않는 스키마)의 필드는 모두 required.
 *   <li>{@code @Nullable}(org.jspecify) 이 붙은 필드는 null 도 허용 (명세 3.1: type 에 "null" 추가).
 *   <li>{@code @JsonInclude(NON_NULL)} 클래스의 {@code @Nullable} 필드는 null 이면 빠지므로 required 에서 뺀다.
 *   <li>요청 타입은 건드리지 않는다 (필수 여부는 검증 어노테이션이 정한다).
 * </ul>
 *
 * 응답 필드가 null 이 될 수 있으면 반드시 {@code @Nullable} 을 붙인다. 붙이지 않은 필드가 테스트 응답에서 null 로 나오면
 * ResponseNullabilityCheck(테스트)가 실패시킨다.
 */
@Configuration
public class OpenApiNullabilityConfig {

    private static final String BASE_PACKAGE = "com.justinneed";
    private static final String API_RESPONSE = "ApiResponse";

    @Bean
    public OpenApiCustomizer nullabilityCustomizer() {
        Map<String, Class<?>> types = schemaTypes();
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents()
                    .getSchemas()
                    .forEach((name, schema) -> apply(name, schema, types));
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(String name, Schema schema, Map<String, Class<?>> types) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty() || name.endsWith("Request")) {
            return;
        }
        Class<?> type = types.get(name);
        if (type == null && name.startsWith(API_RESPONSE)) {
            type = types.get(API_RESPONSE);
        }
        if (type == null) {
            return;
        }
        Map<String, Boolean> members = members(type);
        boolean omitNulls = omitsNulls(type);
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> entry : new ArrayList<>(properties.entrySet())) {
            Boolean nullable = members.get(entry.getKey());
            if (nullable == null) {
                continue; // 클래스에서 찾지 못한 필드는 그대로 둔다 (OpenApiSpecTest 가 알린다)
            }
            if (!nullable) {
                required.add(entry.getKey());
            } else if (!omitNulls) {
                required.add(entry.getKey());
                properties.put(entry.getKey(), allowNull(entry.getValue()));
            }
        }
        schema.setRequired(required.isEmpty() ? null : new ArrayList<>(new TreeSet<>(required)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema allowNull(Schema property) {
        if (property.get$ref() != null) {
            Schema ref = new JsonSchema().$ref(property.get$ref());
            Schema nullType = new JsonSchema();
            nullType.addType("null");
            return new JsonSchema().anyOf(List.of(ref, nullType));
        }
        if (property.getTypes() == null || property.getTypes().isEmpty()) {
            return property; // 타입이 정해지지 않은 값(아무 값)은 이미 null 을 포함한다
        }
        property.addType("null");
        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            property.getEnum().add(null);
        }
        return property;
    }

    /**
     * 필드 이름 → null 가능 여부. @JsonUnwrapped 필드는 안의 필드를 펼친다. record 도 필드로 읽는다 (@JsonUnwrapped 처럼 FIELD
     * 대상 어노테이션은 record 컴포넌트가 아니라 필드에 붙는다).
     */
    static Map<String, Boolean> members(Class<?> type) {
        Map<String, Boolean> members = new LinkedHashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                add(members, field.getName(), field, field.getAnnotatedType(), field.getType());
            }
        }
        return members;
    }

    private static void add(
            Map<String, Boolean> members,
            String name,
            AnnotatedElement element,
            AnnotatedType annotatedType,
            Class<?> rawType) {
        if (element.isAnnotationPresent(JsonUnwrapped.class)) {
            members.putAll(members(rawType));
            return;
        }
        members.putIfAbsent(name, annotatedType.isAnnotationPresent(Nullable.class));
    }

    static boolean omitsNulls(Class<?> type) {
        JsonInclude include = type.getAnnotation(JsonInclude.class);
        return include != null && include.value() == JsonInclude.Include.NON_NULL;
    }

    /**
     * 명세 스키마 이름 → BE 클래스. 이름은 @Schema(name) 이 있으면 그것, 없으면 클래스 이름 (SchemaNameUniquenessTest 가 겹침을
     * 막는다).
     */
    static Map<String, Class<?>> schemaTypes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return true;
                    }
                };
        // 테스트 클래스(build/classes/java/test)는 뺀다. 테스트 안의 같은 이름 클래스가 잡히지 않게
        scanner.addIncludeFilter(
                (reader, factory) -> !reader.getResource().getURL().toString().contains("/test/"));
        ClassLoader loader = OpenApiNullabilityConfig.class.getClassLoader();
        Map<String, Class<?>> types = new HashMap<>();
        for (var definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName(), false, loader);
                if (type.isInterface() || type.isAnnotation() || type.isEnum()) {
                    continue;
                }
                io.swagger.v3.oas.annotations.media.Schema schema =
                        type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
                String name =
                        schema != null && !schema.name().isBlank()
                                ? schema.name()
                                : type.getSimpleName();
                types.putIfAbsent(name, type);
            } catch (ClassNotFoundException | LinkageError e) {
                // 읽을 수 없는 클래스는 건너뛴다
            }
        }
        return types;
    }
}
