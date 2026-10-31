package com.justinneed.community.activity;

import java.util.List;

// 공개 세션의 태그만 읽은 행 (요약 · 출처는 읽지 않는다)
public record TrendingSession(Long id, Long ownerId, List<String> tags) { }
