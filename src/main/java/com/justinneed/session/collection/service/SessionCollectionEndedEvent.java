package com.justinneed.session.collection.service;

// 종료 요청을 받았고 청크가 총 수만큼 있음 → 커밋 뒤 합치기
public record SessionCollectionEndedEvent(
        Long sessionId
) {
}
