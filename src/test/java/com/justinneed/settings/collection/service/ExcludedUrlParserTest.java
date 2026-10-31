package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justinneed.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// 입력 · 기대값은 FE excludedUrl.test.ts 와 같은 표다. 한쪽을 바꾸면 다른 쪽도 맞춘다
class ExcludedUrlParserTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "HTTPS://Example.COM/private/ | example.com/private",
            "example.com                  | example.com",
            "http://example.com           | example.com",
            "https://www.example.com/a    | example.com/a",
            "www.example.com              | example.com",
            "https://example.com/         | example.com",
            "https://example.com//        | example.com",
            "https://mail.google.com      | mail.google.com",
            "https://www.mail.google.com  | mail.google.com",
            "www.com                      | www.com",
            "https://example.com:8080/a   | example.com:8080/a",
            "https://example.com:443/a    | example.com/a",
            "http://example.com:80        | example.com",
            "example.com:80               | example.com",
            "https://example.com/s?q=1    | example.com/s?q=1",
            "https://example.com/a?       | example.com/a",
            "https://example.com/a#top    | example.com/a",
            "https://example.com/Path     | example.com/Path",
            "https://example.com/%EA%B0%80| example.com/%EA%B0%80",
            "https://example.com/a%2Fb    | example.com/a%2Fb",
            "https://example.com/s?q=a%26b| example.com/s?q=a%26b",
            "https://example.com/가나      | example.com/가나",
            "`  example.com  `            | example.com",
    })
    void normalizesToDisplayForm(String raw, String expected) {
        assertThat(ExcludedUrlParser.parse(raw).url()).isEqualTo(expected);
    }

    @Test
    void domainIsHostWithoutWww() {
        assertThat(ExcludedUrlParser.parse("https://www.Tistory.com/feed").domain()).isEqualTo("tistory.com");
        assertThat(ExcludedUrlParser.parse("https://mail.google.com").domain()).isEqualTo("mail.google.com");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "ftp://example.com/file", "https://example .com", "https://localhost/path",
            "http://192.168.0.1", "https://user@example.com", "example.c", "https://-abc.com", "https://ex_ample.com",
            "https://예시.com", "https://example.com/a<b", "https://example.com/%zz", "not a url", "not-a-url",
            "javascript:alert(1)", "mailto:a@example.com"})
    void rejectsInvalidUrl(String raw) {
        assertThatThrownBy(() -> ExcludedUrlParser.parse(raw)).isInstanceOf(CustomException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ExcludedUrlParser.parse(null)).isInstanceOf(CustomException.class);
    }

    @Test
    void limitsLengthInBytes() {
        String domain = "example.com/";
        assertThat(ExcludedUrlParser.parse(domain + "a".repeat(2048 - domain.length())).url()).hasSize(2048);
        assertThatThrownBy(() -> ExcludedUrlParser.parse(domain + "a".repeat(2049 - domain.length())))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> ExcludedUrlParser.parse(domain + "가".repeat(700)))
                .isInstanceOf(CustomException.class);
    }
}
