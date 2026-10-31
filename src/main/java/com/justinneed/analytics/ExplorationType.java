package com.justinneed.analytics;

/** 탐색 성향 (ANA-4). 같은 세션 수면 앞에 있는 성향을 고른다. 문구는 엑셀 그대로이고 집중 탐색 추천 문구는 없다 */
public enum ExplorationType {
    DISTRACTED(
            "여러 페이지를 빠르게 오가며 탐색하는 경향이 있습니다.",
            "지금 가장 중요한 문서 딱 하나만 남기고 나머지 탭은 잠시 닫아두세요. 15분만 타이머를 맞추고 하나의 글에만 온전히 몰입해 보는 걸 추천합니다."),
    FOCUSED("하나의 주제에 대해 깊이 있게 탐색하는 방식입니다.", null),
    BROAD("여러 주제를 살펴보는 탐색 방식입니다.", "다양한 정보를 비교하거나 조사할 때 유용한 패턴입니다."),
    QUICK_CHECK("필요한 정보를 빠르게 확인하는 효율적인 탐색 방식입니다.", "찾은 자료 중 1~2개만 골라 깊이 읽어보기를 추천합니다.");

    private final String analysis;
    private final String recommendation;

    ExplorationType(String analysis, String recommendation) {
        this.analysis = analysis;
        this.recommendation = recommendation;
    }

    public String analysis() {
        return analysis;
    }

    public String recommendation() {
        return recommendation;
    }
}
