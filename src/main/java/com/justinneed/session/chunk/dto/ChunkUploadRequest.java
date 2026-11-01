package com.justinneed.session.chunk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

// 청크 형식(payload 안에 무엇이 들어가는지)은 확장 담당과 맞춘 뒤 확정. 지금은 기존 세션 JSON 을 쪼갠 형태로 보고 합친다
public record ChunkUploadRequest(
        // 수집기 세션 id
        @NotBlank(message = "세션 id 를 입력해 주세요.")
        @Size(max = 64, message = "세션 id 는 64자 이하여야 합니다.")
        String sessionId,

        @NotBlank(message = "청크 id 를 입력해 주세요.")
        @Size(max = 128, message = "청크 id 는 128자 이하여야 합니다.")
        String chunkId,

        @NotNull(message = "청크 내용(payload)을 입력해 주세요.")
        Map<String, Object> payload
) {
}
