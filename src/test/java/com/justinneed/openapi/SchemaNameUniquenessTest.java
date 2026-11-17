package com.justinneed.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * 정책 #24: API 명세(openapi.json)의 스키마 이름은 기본으로 클래스 이름만 쓴다. 다른 클래스 안의
 * Mindmaps · Graph · Node 처럼 이름이 같은 타입이 둘 이상이면 springdoc 이 하나로 합쳐 응답 모양이 틀려진다.
 * BE 코드(com.justinneed, 테스트 제외)의 타입끼리 명세 이름이 겹치지 않는지 검사한다.
 *
 * <pre>
 * 겹치면 다른 클래스 안에 든 타입에 감싸는 클래스 이름을 붙인 이름을 단다:
 *   @Schema(name = "CardSessionsServiceMindmaps") record Mindmaps(...)
 * </pre>
 */
class SchemaNameUniquenessTest {

    @Test
    @DisplayName("BE 타입끼리 API 명세 스키마 이름이 겹치지 않는다")
    void schemaNamesAreUnique() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> !reader.getResource().getURL().toString().contains("/test/"));

        Map<String, Set<String>> byName = new TreeMap<>();
        for (var definition : scanner.findCandidateComponents("com.justinneed")) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
            if (type.isAnonymousClass() || type.isSynthetic() || type.isAnnotation() || type.isInterface()) {
                continue;
            }
            Schema schema = type.getAnnotation(Schema.class);
            String name = schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
            byName.computeIfAbsent(name, key -> new TreeSet<>()).add(type.getName());
        }

        Map<String, Set<String>> duplicated = new TreeMap<>();
        byName.forEach((name, types) -> {
            if (types.size() > 1) {
                duplicated.put(name, types);
            }
        });
        assertThat(byName).as("검사할 타입을 찾지 못했습니다").isNotEmpty();
        assertThat(duplicated)
                .as("명세 스키마 이름이 겹칩니다. 다른 클래스 안의 타입에 @Schema(name = \"감싸는클래스+이름\") 을 붙이세요")
                .isEmpty();
    }
}
