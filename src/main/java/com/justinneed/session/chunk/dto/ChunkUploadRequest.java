package com.justinneed.session.chunk.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

// 청크 형식(payload 안에 무엇이 들어가는지)은 확장 담당과 맞춘 뒤 확정. 지금은 JSON 객체를 그대로 저장한다
public record ChunkUploadRequest(
        @NotBlank(message = "세션 id 를 입력해 주세요.")
        @Size(max = 64, message = "세션 id 는 64자 이하여야 합니다.")
        String sessionId,

        @NotBlank(message = "구간 id(segId)를 입력해 주세요.")
        @Size(max = 64, message = "구간 id(segId)는 64자 이하여야 합니다.")
        String segId,

        @NotNull(message = "청크 번호(n)를 입력해 주세요.")
        @Min(value = 1, message = "청크 번호(n)는 1 이상이어야 합니다.")
        Integer n,

        // 없으면 마지막 청크가 아님
        @JsonProperty("final")
        Boolean isFinal,

        @Min(value = 1, message = "총 청크 수(maxN)는 1 이상이어야 합니다.")
        Integer maxN,

        @Size(max = 50, message = "종료 사유(stopReason)는 50자 이하여야 합니다.")
        String stopReason,

        @NotNull(message = "청크 내용(payload)을 입력해 주세요.")
        Map<String, Object> payload
) {

    @JsonIgnore
    public boolean lastChunk() {
        return Boolean.TRUE.equals(isFinal);
    }

    @JsonIgnore
    @AssertTrue(message = "마지막 청크에는 총 청크 수(maxN)가 필요합니다.")
    public boolean isMaxNGivenForFinal() {
        return !lastChunk() || maxN != null;
    }

    @JsonIgnore
    @AssertTrue(message = "청크 번호(n)는 총 청크 수(maxN)보다 클 수 없습니다.")
    public boolean isNWithinMaxN() {
        return n == null || maxN == null || n <= maxN;
    }
}
