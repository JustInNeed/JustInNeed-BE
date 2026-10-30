package com.justinneed.session.analysis.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.dto.AiAnalysisFailure;
import com.justinneed.session.analysis.dto.AiAnalysisResult;
import com.justinneed.session.analysis.service.AnalysisResultService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 컨테이너 → Spring 콜백 (내부 전용, X-Internal-Token 필요).
 * 세션이 ANALYZING 일 때만 받는다. 그 외 상태면 409.
 */
@RestController
@RequestMapping("/internal/ai/sessions/{sessionId}")
public class AiCallbackController {

    private final AiInternalToken internalToken;
    private final AnalysisResultService resultService;

    public AiCallbackController(AiInternalToken internalToken, AnalysisResultService resultService) {
        this.internalToken = internalToken;
        this.resultService = resultService;
    }

    // 분석 결과 (AI 응답 전체 6개 항목). LLM 실패면 FAILED 로 두고 422
    @PostMapping("/result")
    public ApiResponse<Void> receiveResult(
            @RequestHeader(value = AiInternalToken.HEADER, required = false) String token,
            @PathVariable Long sessionId,
            @RequestBody AiAnalysisResult result
    ) {
        internalToken.verify(token);
        resultService.save(sessionId, result);
        return ApiResponse.ok();
    }

    // 분석 중 오류
    @PostMapping("/failure")
    public ApiResponse<Void> receiveFailure(
            @RequestHeader(value = AiInternalToken.HEADER, required = false) String token,
            @PathVariable Long sessionId,
            @RequestBody AiAnalysisFailure failure
    ) {
        internalToken.verify(token);
        resultService.fail(sessionId, failure.reason());
        return ApiResponse.ok();
    }
}
