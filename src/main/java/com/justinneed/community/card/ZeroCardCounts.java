package com.justinneed.community.card;

import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

// 카드 단위 좋아요 · 북마크 · 나도 탐색중을 만들기 전까지의 기본 구현. 그 구현이 이 빈을 대체한다
@Component
class ZeroCardCounts implements CardCounts {
    @Override
    public Map<Long, Count> counts(Collection<Long> sessionIds, Long viewerId) {
        return Map.of();
    }
}
