package com.justinneed.settings.account.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.account.dto.request.AccountWithdrawRequest;
import com.justinneed.settings.account.dto.response.AccountWithdrawResponse;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountSettingsService {

    private static final String WITHDRAW_CONFIRM_TEXT = "WITHDRAW";

    private final MemberRepository memberRepository;
    private final AuthTokenService authTokenService;
    private final CommunityCardSync cards;
    private final CommunityProfileRepository communityProfiles;

    public AccountSettingsService(MemberRepository memberRepository, AuthTokenService authTokenService,
            CommunityCardSync cards, CommunityProfileRepository communityProfiles) {
        this.memberRepository = memberRepository;
        this.authTokenService = authTokenService;
        this.cards = cards;
        this.communityProfiles = communityProfiles;
    }

    @Transactional
    public AccountWithdrawResponse withdraw(Long memberId, AccountWithdrawRequest request, String accessToken) {
        if (!WITHDRAW_CONFIRM_TEXT.equals(request.confirmText())) {
            throw new CustomException(ErrorCode.INVALID_WITHDRAW_CONFIRMATION);
        }

        Member member = findActiveMember(memberId);
        LocalDateTime withdrawnAt = LocalDateTime.now();
        member.withdraw(request.reason(), withdrawnAt);
        cards.removeOwner(memberId);
        // 닉네임 · 이메일처럼 소개도 지운다
        communityProfiles.findById(memberId).ifPresent(profile -> profile.changeBio(null));
        authTokenService.revokeAll(memberId, accessToken);

        return new AccountWithdrawResponse(200, memberId, true, false, withdrawnAt);
    }

    private Member findActiveMember(Long memberId) {
        return memberRepository.findByIdAndWithdrawnAtIsNull(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
