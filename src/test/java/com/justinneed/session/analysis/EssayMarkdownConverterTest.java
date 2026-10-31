package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justinneed.session.analysis.dto.AiEssay;
import com.justinneed.session.analysis.dto.AiEssaySection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EssayMarkdownConverterTest {

    @Test
    void convertsSectionsToMarkdownWithoutTitle() {
        AiEssay essay = new AiEssay("대제목", List.of(
                new AiEssaySection("소제목1", "본문1", List.of("u1")),
                new AiEssaySection("소제목2", "본문2", List.of("u2"))
        ), "llm");

        assertThat(EssayMarkdownConverter.toMarkdown(essay)).isEqualTo("""
                ## 소제목1

                본문1

                ## 소제목2

                본문2""");
    }

    @Test
    void trimsStringsAndKeepsParagraphBreaksInBody() {
        AiEssay essay = new AiEssay("  대제목  ", List.of(
                new AiEssaySection("\n 소제목\n이어짐 ", "\r\n 첫 문단\r\n\r\n둘째 문단 \n", null)
        ), "llm");

        assertThat(EssayMarkdownConverter.toMarkdown(essay)).isEqualTo("""
                ## 소제목 이어짐

                첫 문단

                둘째 문단""");
    }

    @Test
    void skipsEmptyTitleHeadingAndBody() {
        AiEssay essay = new AiEssay(" ", Arrays.asList(
                new AiEssaySection(null, "소제목 없는 본문", null),
                new AiEssaySection("본문 없는 소제목", "  ", null),
                new AiEssaySection("", "", null),
                null
        ), "fallback");

        assertThat(EssayMarkdownConverter.toMarkdown(essay)).isEqualTo("""
                소제목 없는 본문

                ## 본문 없는 소제목""");
    }

    @Test
    void returnsEmptyStringForNullOrEmptyEssay() {
        assertThat(EssayMarkdownConverter.toMarkdown(null)).isEmpty();
        assertThat(EssayMarkdownConverter.toMarkdown(new AiEssay(null, null, null))).isEmpty();
        assertThat(EssayMarkdownConverter.toMarkdown(new AiEssay("", List.of(), "llm"))).isEmpty();
        // 소제목 · 본문이 없으면 대제목만 있어도 본문은 비어 있다
        assertThat(EssayMarkdownConverter.toMarkdown(new AiEssay("대제목", List.of(), "llm"))).isEmpty();
    }

    @Test
    void returnsTrimmedTitleAsSessionTitle() {
        assertThat(EssayMarkdownConverter.toSessionTitle(new AiEssay("  대제목 ", List.of(), "llm")))
                .isEqualTo("대제목");
        assertThat(EssayMarkdownConverter.toSessionTitle(null)).isEmpty();
        assertThat(EssayMarkdownConverter.toSessionTitle(new AiEssay(null, null, null))).isEmpty();
    }

    @Test
    void truncatesSessionTitleTo100Characters() {
        String exact = "가".repeat(100);
        assertThat(EssayMarkdownConverter.toSessionTitle(new AiEssay(exact, null, null))).isEqualTo(exact);

        String tooLong = "가".repeat(99) + "나다라";
        assertThat(EssayMarkdownConverter.toSessionTitle(new AiEssay(tooLong, null, null)))
                .isEqualTo("가".repeat(99) + "나");
    }

    @Test
    void doesNotSplitSurrogatePairWhenTruncating() {
        // 이모지(서로게이트 쌍)가 100번째 글자에 걸리면 쌍째로 빠지고 반쪽 문자가 남지 않는다.
        String title = "가".repeat(99) + "😀" + "끝";

        String truncated = EssayMarkdownConverter.toSessionTitle(new AiEssay(title, null, null));

        assertThat(truncated).isEqualTo("가".repeat(99));
    }

    @Test
    void truncatesByUtf16LengthLikeTheTitleValidator() {
        // 이모지는 UTF-16 2자라 100자 제한에 50개까지 들어간다.
        String truncated = EssayMarkdownConverter.toSessionTitle(new AiEssay("😀".repeat(60), null, null));

        assertThat(truncated).isEqualTo("😀".repeat(50));
        assertThat(truncated).hasSize(EssayMarkdownConverter.MAX_TITLE_LENGTH);
    }

    @Test
    void deserializesAiEssayJson() throws Exception {
        String json = """
                {"title": "대제목", "sections": [{"heading": "소제목", "body": "본문", "unitKeys": ["u1", "u2"]}],
                 "source": "llm", "extra": "무시"}
                """;

        AiEssay essay = new ObjectMapper().readValue(json, AiEssay.class);

        assertThat(essay.title()).isEqualTo("대제목");
        assertThat(essay.source()).isEqualTo("llm");
        assertThat(essay.sections()).containsExactly(new AiEssaySection("소제목", "본문", List.of("u1", "u2")));
        assertThat(EssayMarkdownConverter.toMarkdown(essay)).isEqualTo("## 소제목\n\n본문");
    }
}
