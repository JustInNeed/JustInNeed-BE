package com.justinneed.community.card;

import java.util.Collection;
import java.util.Map;

/** 카드의 좋아요 · 북마크 · 나도 탐색중 숫자와 보는 사람의 상태. 탈퇴 회원의 행동은 빼고 내 행동은 포함한다. */
public interface CardCounts {

    /** 세션 id → 숫자를 한 번에 돌려준다. 행동이 없는 세션은 빠져도 된다 (Count.ZERO 로 본다). */
    Map<Long, Count> counts(Collection<Long> sessionIds, Long viewerId);

    /** 세션별 행 수 쿼리의 결과. */
    interface Total {
        Long getSessionId();

        long getTotal();
    }

    record Count(long likeCount, boolean likedByMe, long bookmarkCount, Long bookmarkId,
            long exploringCount, boolean exploringByMe) {
        public static final Count ZERO = new Count(0, false, 0, null, 0, false);
    }
}
