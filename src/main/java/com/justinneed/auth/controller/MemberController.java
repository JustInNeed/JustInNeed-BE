package com.justinneed.auth.controller;

import com.justinneed.auth.dto.MemberResponse;
import com.justinneed.auth.dto.NicknameAvailabilityResponse;
import com.justinneed.auth.dto.NicknameUpdateRequest;
import com.justinneed.auth.service.MemberService;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/me")
    public ApiResponse<MemberResponse> getMe(@AuthenticationPrincipal Long memberId) {
        return ApiResponse.ok(memberService.getMe(memberId));
    }

    @PatchMapping("/me/nickname")
    public ApiResponse<MemberResponse> updateNickname(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody NicknameUpdateRequest request
    ) {
        return ApiResponse.ok(memberService.updateNickname(memberId, request.nickname()));
    }

    // 쿼리 파라미터를 PATCH 와 같은 DTO 로 받아 형식 규칙(누락·공백 포함)을 그대로 적용한다
    @GetMapping("/nickname/available")
    public ApiResponse<NicknameAvailabilityResponse> checkNicknameAvailability(
            @AuthenticationPrincipal Long memberId,
            @Valid @ModelAttribute NicknameUpdateRequest request
    ) {
        return ApiResponse.ok(memberService.checkNicknameAvailability(memberId, request.nickname()));
    }
}
