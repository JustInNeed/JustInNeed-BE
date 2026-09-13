package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justinneed.global.exception.CustomException;
import org.junit.jupiter.api.Test;

class ExcludedUrlParserTest {

    @Test
    void normalizesValidHttpUrl() {
        var parsed = ExcludedUrlParser.parse("HTTPS://Example.COM/private/");

        assertThat(parsed.url()).isEqualTo("https://example.com/private");
        assertThat(parsed.domain()).isEqualTo("example.com");
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> ExcludedUrlParser.parse("ftp://example.com/file"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void rejectsWhitespaceAndInvalidDomain() {
        assertThatThrownBy(() -> ExcludedUrlParser.parse("https://example .com"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> ExcludedUrlParser.parse("https://localhost/path"))
                .isInstanceOf(CustomException.class);
    }
}
