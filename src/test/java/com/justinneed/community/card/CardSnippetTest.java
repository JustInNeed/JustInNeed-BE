package com.justinneed.community.card;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardSnippetTest {

    @Test
    void takesFirstBodyParagraphAsPlainText() {
        assertThat(CardSnippet.of("""
                # 대제목

                ## 소제목
                **콜로세움**은 [공식 사이트](https://x.example.com)에서
                `예약`한다.

                둘째 문단
                """)).isEqualTo("콜로세움은 공식 사이트에서 예약한다.");
    }

    @Test
    void skipsCodeBlocksRulesAndImagesAndStripsQuotesAndLists() {
        assertThat(CardSnippet.of("```\n코드\n\n코드\n```\n\n---\n\n![그림](a.png)\n\n> 인용 *강조*")).isEqualTo("인용 강조");
        assertThat(CardSnippet.of("- 항목 snake_case 3*4")).isEqualTo("항목 snake_case 3*4");
        assertThat(CardSnippet.of("1. 첫째\n2. 둘째")).isEqualTo("첫째 둘째");
    }

    @Test
    void noBodyIsNull() {
        assertThat(CardSnippet.of(null)).isNull();
        assertThat(CardSnippet.of("")).isNull();
        assertThat(CardSnippet.of("## 제목만\n\n# 또 제목")).isNull();
    }

    @Test
    void cutsAt120WithoutSplittingSurrogatePair() {
        assertThat(CardSnippet.of("가".repeat(200))).hasSize(120);
        assertThat(CardSnippet.of("가".repeat(119) + "😀끝")).isEqualTo("가".repeat(119));
    }
}
