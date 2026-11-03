package com.justinneed.session.chunk.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 합치기 정답 비교. src/test/resources/chunk-assembly/ 의 정답은 확장 레포의 진짜 assemble() 이 만든 것:
 *   node tools/chunks-to-bundle.js <이름>.chunks.json -o <이름>.bundle.json   (확장 레포 루트에서)
 *   real-*  = 실제 기록 청크 (로컬 시험 DB). 확장 [내보내기] 파일과도 같음을 확인한 것. 큰 것은 .gz (gzip -9)
 *             real-ace10e1d = 탭 6개 · 글 7개를 오가고 · 링크 이동 · 탭 닫기 · 다른 앱 갔다 옴 (11-02 서현 기록)
 *   tricky  = 여러 글 · 탭 · 같은 조각 두 번 · 빈 번호 · 글 정보 없는 조각 · 틱 없는 글 · 유닛 없는 글 · 유닛 교체 ·
 *             정지 뒤 조각 · 번호 없는 조각 · tabId 없는 조각 · 소수 시각 · meta null 을 일부러 넣은 가짜 기록
 */
@DisplayName("청크 합치기 = 확장 assemble() 결과와 JSON 으로 같음")
class ChunkAssemblerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // <이름>.json 이 없으면 <이름>.json.gz
    private static JsonNode read(String path) throws Exception {
        InputStream raw = ChunkAssemblerTest.class.getResourceAsStream("/chunk-assembly/" + path);
        if (raw == null) {
            InputStream gz = ChunkAssemblerTest.class.getResourceAsStream("/chunk-assembly/" + path + ".gz");
            assertThat(gz).as(path + "(.gz)").isNotNull();
            raw = new GZIPInputStream(gz);
        }
        try (InputStream in = raw) {
            return JSON.readTree(in);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"real-74a8e268", "real-ace10e1d", "tricky"})
    void sameAsExtension(String name) throws Exception {
        List<Map<String, Object>> chunks = JSON.convertValue(read(name + ".chunks.json"), new TypeReference<>() { });
        var log = new ArrayList<Map<String, Object>>();
        chunks.stream()
                .sorted(Comparator.comparingInt(c -> ((Number) c.get("n")).intValue()))
                .forEach(c -> {
                    @SuppressWarnings("unchecked")
                    var payload = (Map<String, Object>) c.get("payload");
                    @SuppressWarnings("unchecked")
                    var records = (List<Map<String, Object>>) payload.get("records");
                    log.addAll(records);
                });

        // 자바 Map 을 JSON 글자로 썼다가 다시 읽어 정답과 같은 길로 비교한다 (숫자 타입 차이를 없앤다)
        JsonNode actual = JSON.readTree(JSON.writeValueAsString(ChunkAssembler.assemble(log)));
        JsonNode expected = read(name + ".bundle.json");
        assertThat(actual).isEqualTo(expected);
    }
}
