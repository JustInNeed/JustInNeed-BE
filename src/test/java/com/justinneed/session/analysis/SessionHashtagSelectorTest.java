package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justinneed.session.analysis.dto.AiMindmap;
import com.justinneed.session.analysis.dto.AiMindmapNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionHashtagSelectorTest {

    @Test
    void selectsTopicsFirstThenUnitsUpToFive() {
        AiMindmap mindmap = new AiMindmap(List.of(
                root("재일교포 문학"),
                topic("정권 비판", 2.39, 2),
                topic("재일교포", 4.0, 3),
                topic("장애 극복", 3.0, 2),
                unit("원폭 후유증", 3.68),
                unit("배타성", 4.0),
                unit("민족 정체성", 3.1)
        ));

        assertThat(SessionHashtagSelector.select(mindmap))
                .containsExactly("재일교포", "장애극복", "정권비판", "배타성", "원폭후유증");
    }

    @Test
    void unitWithHigherScoreStillComesAfterTopics() {
        AiMindmap mindmap = new AiMindmap(List.of(
                unit("유닛", 9.9),
                topic("주제", 0.1, 0)
        ));

        assertThat(SessionHashtagSelector.select(mindmap)).containsExactly("주제", "유닛");
    }

    @Test
    void breaksScoreTiesByChildCountThenOriginalOrder() {
        AiMindmap mindmap = new AiMindmap(List.of(
                topic("자식적음", 3.0, 1),
                topic("먼저나옴", 3.0, 2),
                topic("자식많음", 3.0, 5),
                topic("나중나옴", 3.0, 2)
        ));

        assertThat(SessionHashtagSelector.select(mindmap))
                .containsExactly("자식많음", "먼저나옴", "나중나옴", "자식적음");
    }

    @Test
    void sortsNullScoreAndNullChildCountLast() {
        AiMindmap mindmap = new AiMindmap(List.of(
                new AiMindmapNode("topic:0", "topic", "점수없음", null, 9),
                new AiMindmapNode("topic:1", "topic", "점수있음", 0.5, null),
                new AiMindmapNode("topic:2", "topic", "자식있음", 0.5, 1)
        ));

        assertThat(SessionHashtagSelector.select(mindmap))
                .containsExactly("자식있음", "점수있음", "점수없음");
    }

    @Test
    void removesAllWhitespaceFromKeyword() {
        AiMindmap mindmap = new AiMindmap(List.of(
                topic(" 장애 극복 ", 3.0, 0),
                unit("원폭\t후유\n증", 2.0),
                unit("민족 정체성", 1.0)
        ));

        assertThat(SessionHashtagSelector.select(mindmap))
                .containsExactly("장애극복", "원폭후유증", "민족정체성");
    }

    @Test
    void skipsKeywordsViolatingHashtagRule() {
        AiMindmap mindmap = new AiMindmap(Arrays.asList(
                topic("열한글자가넘는아주긴키워드", 9.0, 0),
                topic("남북·통일", 8.0, 0),
                topic("C++", 7.0, 0),
                topic("   ", 6.0, 0),
                topic(null, 5.0, 0),
                null,
                topic("열글자까지는허용된다", 4.0, 0),
                unit("Java17", 3.0)
        ));

        assertThat(SessionHashtagSelector.select(mindmap))
                .containsExactly("열글자까지는허용된다", "Java17");
    }

    @Test
    void skipsDuplicatesIgnoringCaseAndSpaces() {
        AiMindmap mindmap = new AiMindmap(List.of(
                topic("Spring Boot", 3.0, 0),
                topic("springboot", 2.0, 0),
                unit("장애 극복", 2.0),
                unit("장애극복", 1.0),
                unit("SPRINGBOOT", 0.5)
        ));

        assertThat(SessionHashtagSelector.select(mindmap)).containsExactly("SpringBoot", "장애극복");
    }

    @Test
    void returnsAllWhenFewerThanFiveCandidates() {
        AiMindmap mindmap = new AiMindmap(List.of(
                root("루트"),
                topic("주제", 1.0, 1),
                unit("유닛", 1.0)
        ));

        assertThat(SessionHashtagSelector.select(mindmap)).containsExactly("주제", "유닛");
    }

    @Test
    void excludesRootNodes() {
        AiMindmap mindmap = new AiMindmap(List.of(
                new AiMindmapNode("root:1", "root", "루트키워드", 10.0, 3),
                new AiMindmapNode("root:2", "root", "두번째루트", 10.0, 3)
        ));

        assertThat(SessionHashtagSelector.select(mindmap)).isEmpty();
    }

    @Test
    void returnsEmptyListForNullOrEmptyMindmap() {
        assertThat(SessionHashtagSelector.select(null)).isEmpty();
        assertThat(SessionHashtagSelector.select(new AiMindmap(null))).isEmpty();
        assertThat(SessionHashtagSelector.select(new AiMindmap(List.of()))).isEmpty();
    }

    @Test
    void deserializesAiMindmapJson() throws Exception {
        String json = """
                {"nodes": [
                  {"id": "root:1", "type": "root", "keyword": "루트", "keywordCandidates": [], "score": null,
                   "childCount": 2, "unitRef": null, "mergedFrom": []},
                  {"id": "topic:0", "type": "topic", "keyword": "장애 극복", "keywordCandidates": ["장애"],
                   "score": 3.56, "childCount": 2, "unitRef": null, "mergedFrom": []},
                  {"id": "u-1", "type": "unit", "keyword": "배타성", "score": 4, "childCount": 0,
                   "unitRef": {"pageId": 1}}
                 ],
                 "edges": [{"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true, "order": 0}],
                 "rootSource": "llm", "structureSource": "llm", "repairs": []}
                """;

        AiMindmap mindmap = new ObjectMapper().readValue(json, AiMindmap.class);

        assertThat(mindmap.nodes()).hasSize(3);
        assertThat(mindmap.nodes().get(0).score()).isNull();
        assertThat(mindmap.nodes().get(1)).isEqualTo(new AiMindmapNode("topic:0", "topic", "장애 극복", 3.56, 2));
        assertThat(SessionHashtagSelector.select(mindmap)).containsExactly("장애극복", "배타성");
    }

    private static AiMindmapNode root(String keyword) {
        return new AiMindmapNode("root:1", "root", keyword, null, 0);
    }

    private static AiMindmapNode topic(String keyword, Double score, Integer childCount) {
        return new AiMindmapNode("topic:" + keyword, "topic", keyword, score, childCount);
    }

    private static AiMindmapNode unit(String keyword, Double score) {
        return new AiMindmapNode("unit:" + keyword, "unit", keyword, score, 0);
    }
}
