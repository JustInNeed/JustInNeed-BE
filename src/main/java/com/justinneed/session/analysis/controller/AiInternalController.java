package com.justinneed.session.analysis.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.dto.AiAnalysisInput;
import com.justinneed.session.analysis.dto.AiSessionHistory;
import com.justinneed.session.analysis.service.AnalysisInputService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 컨테이너 → Spring 분석 입력 조회 (내부 전용, X-Internal-Token 필요).
 * POST /analyze 는 알림만 보내고, AI 가 여기서 원본 · 모델 · 이전 세션 유닛 피처를 가져간다.
 */
@RestController
@RequestMapping("/internal/ai/sessions/{sessionId}")
public class AiInternalController {

    private final AiInternalToken internalToken;
    private final AnalysisInputService inputService;

    public AiInternalController(AiInternalToken internalToken, AnalysisInputService inputService) {
        this.internalToken = internalToken;
        this.inputService = inputService;
    }

    // 분석 시작 시 원본 · 사용자 · 이전 세션 수 · 군집 모델
    @GetMapping("/input")
    public ApiResponse<AiAnalysisInput> getInput(
            @RequestHeader(value = AiInternalToken.HEADER, required = false) String token,
            @PathVariable Long sessionId
    ) {
        internalToken.verify(token);
        return ApiResponse.ok(inputService.input(sessionId));
    }

    // 재학습이 필요할 때만 이전 세션 유닛 피처
    @GetMapping("/history")
    public ApiResponse<AiSessionHistory> getHistory(
            @RequestHeader(value = AiInternalToken.HEADER, required = false) String token,
            @PathVariable Long sessionId
    ) {
        internalToken.verify(token);
        return ApiResponse.ok(inputService.history(sessionId));
    }
}
