package com.justinneed.settings.account.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.settings.account.dto.request.AccountWithdrawRequest;
import com.justinneed.settings.account.dto.request.NicknameUpdateRequest;
import com.justinneed.settings.account.dto.response.AccountWithdrawResponse;
import com.justinneed.settings.account.dto.response.NicknameUpdateResponse;
import com.justinneed.settings.account.service.AccountSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings/account")
public class AccountSettingsController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccountSettingsService accountSettingsService;

    public AccountSettingsController(AccountSettingsService accountSettingsService) {
        this.accountSettingsService = accountSettingsService;
    }

    @PatchMapping("/nickname")
    public ApiResponse<NicknameUpdateResponse> updateNickname(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody NicknameUpdateRequest request
    ) {
        NicknameUpdateResponse response = accountSettingsService.updateNickname(memberId, request.nickname());
        return new ApiResponse<>(true, response, "닉네임이 변경되었습니다.");
    }

    @DeleteMapping
    public ApiResponse<AccountWithdrawResponse> withdraw(
            @AuthenticationPrincipal Long memberId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody AccountWithdrawRequest request
    ) {
        AccountWithdrawResponse response = accountSettingsService.withdraw(
                memberId, request, resolveBearer(authorization));
        return new ApiResponse<>(true, response, "계정 탈퇴가 완료되었습니다.");
    }

    private String resolveBearer(String authorization) {
        return authorization.startsWith(BEARER_PREFIX)
                ? authorization.substring(BEARER_PREFIX.length())
                : null;
    }
}
