package com.justinneed.session.chunk.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 합친 bundle 에서 수집 제외 URL 의 페이지를 뺀다. 확장이 거르지 못하고 보낸 페이지를 서버에서 한 번 더 거르는 것.
 *
 * <pre>
 * 일치 규칙 (확장 쪽 규칙과 같아야 한다)
 *   호스트[:포트] 는 정확히 같아야 한다. 서브도메인은 다른 사이트로 본다   naver.com ⊅ mail.naver.com
 *   맨 앞 www. 는 떼고 비교한다 (ExcludedUrlParser 와 같다)              naver.com ⊃ www.naver.com
 *   경로는 / 단위 앞부분 일치                                         naver.com/4 ⊃ naver.com/4 · naver.com/4/x, ⊅ naver.com/40
 *   쿼리 · # 이하는 보지 않는다 (제외 URL 에 쿼리가 있어도 경로까지만 비교)
 *   경로는 % 인코딩을 풀어 비교한다 (주소창 복사본 · 직접 입력한 한글이 같게)
 *   http · https 가 아닌 페이지(chrome:// 등)는 걸리지 않는다
 *
 * 빼는 곳
 *   pages            meta.url 이나 segments[].url 중 하나라도 걸리면 그 페이지. session.focusMs 에서 그 페이지 시간을 뺀다
 *   droppedPages     segments[].url 중 하나라도 걸리면
 *   visits · flushReport  위에서 뺀 pageId 의 항목
 * </pre>
 */
final class ExcludedPageFilter {

    private static final String SCHEME_SEPARATOR = "://";
    private static final String WWW_PREFIX = "www.";

    private ExcludedPageFilter() {
    }

    record Target(String hostPort, String path) {
    }

    // 걸러낸 페이지 수 (pages + droppedPages)
    @SuppressWarnings("unchecked")
    static int apply(Map<String, Object> bundle, List<String> excludedUrls) {
        List<Target> targets = new ArrayList<>();
        for (String url : excludedUrls) {
            Target t = split(url);
            if (t != null) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        Set<Object> removedPageIds = new HashSet<>();
        long removedFocusMs = 0;

        if (bundle.get("pages") instanceof List<?> pages) {
            var it = ((List<Object>) pages).iterator();
            while (it.hasNext()) {
                if (!(it.next() instanceof Map<?, ?> page) || !(page.get("meta") instanceof Map<?, ?> meta)) {
                    continue;
                }
                if (matchesAny(meta.get("url"), targets) || segmentsMatch(meta.get("segments"), targets)) {
                    removedPageIds.add(key(meta.get("pageId")));
                    if (meta.get("focusMs") instanceof Number ms) {
                        removedFocusMs += ms.longValue();
                    }
                    it.remove();
                }
            }
        }

        if (!(bundle.get("session") instanceof Map<?, ?> rawSession)) {
            return removedPageIds.size();
        }
        var session = (Map<String, Object>) rawSession;
        if (session.get("droppedPages") instanceof List<?> dropped) {
            ((List<Object>) dropped).removeIf(d -> {
                if (d instanceof Map<?, ?> m && segmentsMatch(m.get("segments"), targets)) {
                    removedPageIds.add(key(m.get("pageId")));
                    return true;
                }
                return false;
            });
        }
        if (removedPageIds.isEmpty()) {
            return 0;
        }
        if (removedFocusMs > 0 && session.get("focusMs") instanceof Number total) {
            session.put("focusMs", Math.max(0, total.longValue() - removedFocusMs));
        }
        removeByPageId(session.get("visits"), removedPageIds);
        removeByPageId(session.get("flushReport"), removedPageIds);
        return removedPageIds.size();
    }

    static boolean matches(String pageUrl, String excludedUrl) {
        Target t = split(excludedUrl);
        return t != null && matchesAny(pageUrl, List.of(t));
    }

    private static boolean segmentsMatch(Object segments, List<Target> targets) {
        if (!(segments instanceof List<?> list)) {
            return false;
        }
        for (Object s : list) {
            if (s instanceof Map<?, ?> seg && matchesAny(seg.get("url"), targets)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(Object pageUrl, List<Target> targets) {
        if (!(pageUrl instanceof String s) || !s.contains(SCHEME_SEPARATOR)) {
            return false;   // 페이지 URL 은 확장이 location.href 로 남겨 항상 프로토콜이 있다
        }
        Target page = split(s);
        if (page == null) {
            return false;
        }
        for (Target t : targets) {
            if (page.hostPort().equals(t.hostPort())
                    && (t.path().isEmpty() || page.path().equals(t.path()) || page.path().startsWith(t.path() + "/"))) {
                return true;
            }
        }
        return false;
    }

    // URL → (호스트[:포트], 경로). 프로토콜이 없으면 제외 URL 표시형으로 본다. http · https 가 아니면 null
    static Target split(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String rest = url.trim();
        int sep = rest.indexOf(SCHEME_SEPARATOR);
        if (sep >= 0) {
            String scheme = rest.substring(0, sep).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            rest = rest.substring(sep + SCHEME_SEPARATOR.length());
        }
        int authorityEnd = indexOfAny(rest, "/?#");
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String afterAuthority = authorityEnd < 0 ? "" : rest.substring(authorityEnd);
        authority = authority.substring(authority.lastIndexOf('@') + 1);   // user:pass@ 제거

        String host = authority;
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon > authority.lastIndexOf(']')) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        host = withoutWww(host.toLowerCase(Locale.ROOT));
        if (host.isEmpty()) {
            return null;
        }
        String hostPort = port.isEmpty() || port.equals("80") || port.equals("443") ? host : host + ":" + port;

        int pathEnd = indexOfAny(afterAuthority, "?#");
        String path = pathEnd < 0 ? afterAuthority : afterAuthority.substring(0, pathEnd);
        path = decode(path).replaceAll("/+$", "");
        return new Target(hostPort, path);
    }

    // ExcludedUrlParser.withoutWww 와 같다 (www.com 처럼 떼면 도메인이 안 되는 경우는 그대로)
    private static String withoutWww(String host) {
        boolean removable = host.startsWith(WWW_PREFIX) && host.indexOf('.', WWW_PREFIX.length()) > 0;
        return removable ? host.substring(WWW_PREFIX.length()) : host;
    }

    // 경로의 % 인코딩만 푼다 (+ 는 경로에서 글자 그대로). 잘못된 인코딩이면 그대로 둔다
    private static String decode(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        try {
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static void removeByPageId(Object list, Set<Object> pageIds) {
        if (list instanceof List<?> l) {
            l.removeIf(e -> e instanceof Map<?, ?> m && m.containsKey("pageId") && pageIds.contains(key(m.get("pageId"))));
        }
    }

    // ChunkAssembler.key 와 같다 — 숫자 pageId 는 값으로 (1 과 1L 이 같게)
    private static Object key(Object v) {
        return v instanceof Number n ? (Object) n.doubleValue() : Objects.requireNonNullElse(v, "");
    }
}
