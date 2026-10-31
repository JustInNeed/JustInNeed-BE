package com.justinneed.session.analysis;

import com.justinneed.global.common.HashtagValidator;
import com.justinneed.session.analysis.dto.AiMindmap;
import com.justinneed.session.analysis.dto.AiMindmapNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 마인드맵 → 세션 해시태그 선정.
 *
 * <pre>
 * 후보 = 주제(topic) + 유닛(unit) 노드의 keyword (루트 제외)
 * 정렬 = 주제 노드 먼저, 그다음 유닛 노드
 *        각각 안에서: score 높은 순(null 은 맨 뒤) → childCount 많은 순 → 원문 순서(노드 리스트 순서)
 * 위에서부터: 모든 공백 제거 → 해시태그 규칙 위반이면 건너뜀 → 이미 고른 것과 같으면(대소문자 무시) 건너뜀
 * MAX_HASHTAG_COUNT 개 차면 끝
 * </pre>
 */
public final class SessionHashtagSelector {

    public static final int MAX_HASHTAG_COUNT = 5;

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt(Candidate::typeRank)
            .thenComparing(Candidate::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::childCount, Comparator.reverseOrder())
            .thenComparingInt(Candidate::index);

    private SessionHashtagSelector() {
    }

    public static List<String> select(AiMindmap mindmap) {
        if (mindmap == null || mindmap.nodes() == null) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        List<AiMindmapNode> nodes = mindmap.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            AiMindmapNode node = nodes.get(i);
            int typeRank = node == null ? -1 : typeRank(node.type());
            if (typeRank < 0 || node.keyword() == null) {
                continue;
            }
            int childCount = node.childCount() == null ? 0 : node.childCount();
            candidates.add(new Candidate(node.keyword(), typeRank, node.score(), childCount, i));
        }
        candidates.sort(CANDIDATE_ORDER);

        Set<String> seen = new HashSet<>();
        List<String> hashtags = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String tag = removeWhitespace(candidate.keyword());
            if (!HashtagValidator.isValid(tag)) {
                continue;
            }
            if (!seen.add(tag.toLowerCase(Locale.ROOT))) {
                continue;
            }
            hashtags.add(tag);
            if (hashtags.size() == MAX_HASHTAG_COUNT) {
                break;
            }
        }
        return hashtags;
    }

    // 주제 0, 유닛 1, 그 외(루트 등) -1 = 후보 아님
    private static int typeRank(String type) {
        if (AiMindmapNode.TYPE_TOPIC.equals(type)) {
            return 0;
        }
        if (AiMindmapNode.TYPE_UNIT.equals(type)) {
            return 1;
        }
        return -1;
    }

    // 띄어쓰기 붙이기: 일반 공백뿐 아니라 줄바꿈, NBSP 같은 유니코드 공백도 모두 제거
    public static String removeWhitespace(String keyword) {
        StringBuilder builder = new StringBuilder(keyword.length());
        keyword.codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    private record Candidate(String keyword, int typeRank, Double score, int childCount, int index) {
    }
}
