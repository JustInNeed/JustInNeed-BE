package com.justinneed.session.collection;

// 청크 원본 형식이 깨져 합칠 수 없음. 합치기를 롤백하고 정해진 횟수까지 다시 시도한다 (SessionMergeService)
public class ChunkMergeException extends RuntimeException {

    public ChunkMergeException(String message) {
        super(message);
    }
}
