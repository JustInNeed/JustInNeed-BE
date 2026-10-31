package com.justinneed.settings.collection.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 수집 제외 URL 을 저장 · 중복 판단에 쓰는 표시형으로 바꾼다. FE excludedUrl.ts 와 같은 규칙이다.
 *
 * <pre>
 * 표시형 = 도메인[:포트][경로][?쿼리]
 * 프로토콜은 생략할 수 있고(http · https 만 허용) 저장하지 않는다
 * 도메인은 소문자, 맨 앞 www. 제거. 기본 포트(80 · 443) · 끝 슬래시 · 빈 쿼리 · # 이하 제거
 * 경로 · 쿼리의 대소문자와 % 인코딩은 입력 그대로 둔다 (%26 을 & 로 풀면 뜻이 바뀐다)
 * </pre>
 */
final class ExcludedUrlParser {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final String SCHEME_SEPARATOR = "://";
    private static final String WWW_PREFIX = "www.";
    // url 컬럼 길이이자 (member_id, url) 유니크 인덱스에 들어가는 크기. 한글은 글자당 3바이트라 바이트로 잰다
    private static final int MAX_URL_BYTES = 2048;

    private ExcludedUrlParser() {
    }

    static ParsedUrl parse(String rawUrl) {
        if (rawUrl == null) {
            throw new CustomException(ErrorCode.INVALID_URL);
        }
        String value = rawUrl.trim();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new CustomException(ErrorCode.INVALID_URL);
        }
        try {
            URI uri = new URI(value.contains(SCHEME_SEPARATOR) ? value : "https" + SCHEME_SEPARATOR + value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || !DOMAIN_PATTERN.matcher(host).matches()
                    || uri.getUserInfo() != null) {
                throw new CustomException(ErrorCode.INVALID_URL);
            }
            String domain = withoutWww(host);
            String url = domain + port(uri.getPort()) + path(uri.getRawPath()) + query(uri.getRawQuery());
            if (url.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
                throw new CustomException(ErrorCode.INVALID_URL);
            }
            return new ParsedUrl(url, domain);
        } catch (URISyntaxException exception) {
            throw new CustomException(ErrorCode.INVALID_URL);
        }
    }

    // www.com 처럼 떼면 도메인이 안 되는 경우는 그대로 둔다
    private static String withoutWww(String host) {
        boolean removable = host.startsWith(WWW_PREFIX) && host.indexOf('.', WWW_PREFIX.length()) > 0;
        return removable ? host.substring(WWW_PREFIX.length()) : host;
    }

    private static String port(int port) {
        return port == -1 || port == 80 || port == 443 ? "" : ":" + port;
    }

    private static String path(String rawPath) {
        return rawPath == null ? "" : TRAILING_SLASHES.matcher(rawPath).replaceFirst("");
    }

    private static String query(String rawQuery) {
        return rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery;
    }

    record ParsedUrl(String url, String domain) {
    }
}
