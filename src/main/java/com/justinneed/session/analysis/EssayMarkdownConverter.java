package com.justinneed.session.analysis;

import com.justinneed.session.analysis.dto.AiEssay;
import com.justinneed.session.analysis.dto.AiEssaySection;
import java.util.ArrayList;
import java.util.List;

/**
 * AI essay → 요약 마크다운(summary.markdown) / 세션 제목 변환.
 *
 * <pre>
 * # 대제목
 *
 * ## 소제목
 *
 * 본문
 * </pre>
 *
 * 세션 제목은 essay 대제목을 그대로 쓰고, sessions.title 컬럼 길이(100자)를 넘으면 자른다.
 */
public final class EssayMarkdownConverter {

    public static final int MAX_TITLE_LENGTH = 100;

    private static final String BLOCK_SEPARATOR = "\n\n";

    private EssayMarkdownConverter() {
    }

    // 비어 있는 제목/소제목/본문은 건너뛴다. essay 가 null 이거나 내용이 없으면 빈 문자열.
    public static String toMarkdown(AiEssay essay) {
        if (essay == null) {
            return "";
        }

        List<String> blocks = new ArrayList<>();
        String title = toHeadingText(essay.title());
        if (!title.isEmpty()) {
            blocks.add("# " + title);
        }

        if (essay.sections() != null) {
            for (AiEssaySection section : essay.sections()) {
                if (section == null) {
                    continue;
                }
                String heading = toHeadingText(section.heading());
                if (!heading.isEmpty()) {
                    blocks.add("## " + heading);
                }
                String body = toBodyText(section.body());
                if (!body.isEmpty()) {
                    blocks.add(body);
                }
            }
        }
        return String.join(BLOCK_SEPARATOR, blocks);
    }

    // essay 대제목을 세션 제목으로. 없으면 빈 문자열 (기본 제목은 호출하는 쪽에서 정한다).
    public static String toSessionTitle(AiEssay essay) {
        if (essay == null) {
            return "";
        }
        return truncate(toHeadingText(essay.title()), MAX_TITLE_LENGTH);
    }

    // 제목 줄은 한 줄이어야 하므로 줄바꿈 포함 연속 공백을 공백 하나로 합친다.
    private static String toHeadingText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private static String toBodyText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").trim();
    }

    // 서로게이트 쌍(이모지 등)이 반으로 잘리지 않도록 코드포인트 기준으로 자른다.
    private static String truncate(String text, int maxLength) {
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxLength);
        return text.substring(0, end).trim();
    }
}
