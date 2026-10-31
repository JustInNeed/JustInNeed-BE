package com.justinneed.community.card;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 요약 마크다운 → 카드 미리보기: 제목 · 코드 블록 · 구분선을 건너뛴 첫 문단을 일반 텍스트로 바꿔 120자로 자른다.
 * 본문이 없으면 null.
 */
public final class CardSnippet {

    static final int MAX_LENGTH = 120;

    private static final Pattern HEADING = Pattern.compile("^#{1,6}(\\s.*)?$");
    private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}$");
    private static final Pattern LINE_PREFIX = Pattern.compile("^(>\\s*)+|^([-*+]|\\d+[.)])\\s+");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern STAR_EMPHASIS = Pattern.compile("(?<![\\w*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern MARKS = Pattern.compile("\\*\\*|__|~~|`");

    private CardSnippet() {
    }

    public static String of(String markdown) {
        if (markdown == null) {
            return null;
        }
        List<String> paragraph = new ArrayList<>();
        boolean inCode = false;
        for (String raw : markdown.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (!paragraph.isEmpty()) {
                    break;
                }
                inCode = !inCode;
            } else if (!inCode) {
                if (line.isEmpty() || HEADING.matcher(line).matches() || RULE.matcher(line).matches()) {
                    if (!paragraph.isEmpty()) {
                        break;
                    }
                } else {
                    String text = plain(line);
                    if (!text.isEmpty()) {
                        paragraph.add(text);
                    }
                }
            }
        }
        return paragraph.isEmpty() ? null : cut(String.join(" ", paragraph));
    }

    private static String plain(String line) {
        String text = LINE_PREFIX.matcher(line).replaceFirst("");
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = MARKS.matcher(text).replaceAll("");
        text = STAR_EMPHASIS.matcher(text).replaceAll("$1");
        return text.trim();
    }

    // 서로게이트 쌍(이모지 등)이 끝에 걸리면 쌍째로 뺀다
    private static String cut(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return text.substring(0, end).trim();
    }
}
