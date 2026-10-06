package com.justinneed.settings.account.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.account.dto.request.AccountWithdrawRequest;
import com.justinneed.settings.account.dto.response.AccountWithdrawResponse;
import com.justinneed.settings.account.dto.response.NicknameUpdateResponse;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountSettingsService {

    private static final String WITHDRAW_CONFIRM_TEXT = "WITHDRAW";

    private final MemberRepository memberRepository;
    private final AuthTokenService authTokenService;

    public AccountSettingsService(MemberRepository memberRepository, AuthTokenService authTokenService) {
        this.memberRepository = memberRepository;
        this.authTokenService = authTokenService;
    }

    @Transactional
    public NicknameUpdateResponse updateNickname(Long memberId, String nickname) {
        Member member = findActiveMember(memberId);
        if (memberRepository.existsByNicknameIgnoreCaseAndIdNot(nickname, memberId)) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        member.updateNickname(nickname);
        return new NicknameUpdateResponse(200, memberId, nickname, LocalDateTime.now());
    }

    @Transactional
    public AccountWithdrawResponse withdraw(Long memberId, AccountWithdrawRequest request, String accessToken) {
        if (!WITHDRAW_CONFIRM_TEXT.equals(request.confirmText())) {
            throw new CustomException(ErrorCode.INVALID_WITHDRAW_CONFIRMATION);
        }

        Member member = findActiveMember(memberId);
        LocalDateTime withdrawnAt = LocalDateTime.now();
        member.withdraw(request.reason(), withdrawnAt);
        authTokenService.revokeAll(memberId, accessToken);

        return new AccountWithdrawResponse(200, memberId, true, false, withdrawnAt);
    }

    private Member findActiveMember(Long memberId) {
        return memberRepository.findByIdAndWithdrawnAtIsNull(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
