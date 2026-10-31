package com.justinneed.community.search;

import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.search.CommunitySearchService.TagCount;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 태그별 카드 수. 대소문자만 다른 태그는 하나로 묶어 처음 나온 표기를 쓴다. 많은 순 → 이름 순. */
final class TagTally {
    private static final Comparator<TagCount> MOST_FIRST =
            Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::tag);

    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();

    /** 카드 한 장의 태그. 같은 태그가 겹쳐 있어도 한 번만 센다. */
    void add(List<String> tags) {
        Set<String> seen = new HashSet<>();
        for (String tag : tags) {
            String key = CommunityTags.key(tag);
            if (seen.add(key)) {
                names.putIfAbsent(key, tag);
                counts.merge(key, 1L, Long::sum);
            }
        }
    }

    List<TagCount> top(int limit) {
        return counts.entrySet().stream().map(entry -> new TagCount(names.get(entry.getKey()), entry.getValue()))
                .sorted(MOST_FIRST).limit(limit).toList();
    }
}
