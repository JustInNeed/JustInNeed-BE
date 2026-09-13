package com.justinneed.settings.collection.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

final class ExcludedUrlParser {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");

    private ExcludedUrlParser() {
    }

    static ParsedUrl parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.chars().anyMatch(Character::isWhitespace)) {
            throw new CustomException(ErrorCode.INVALID_URL);
        }

        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String domain = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || !DOMAIN_PATTERN.matcher(domain).matches()
                    || uri.getUserInfo() != null) {
                throw new CustomException(ErrorCode.INVALID_URL);
            }

            URI normalized = new URI(
                    scheme,
                    null,
                    domain,
                    uri.getPort(),
                    normalizePath(uri.getPath()),
                    uri.getQuery(),
                    null
            );
            return new ParsedUrl(normalized.toString(), domain);
        } catch (URISyntaxException exception) {
            throw new CustomException(ErrorCode.INVALID_URL);
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    record ParsedUrl(String url, String domain) {
    }
}
