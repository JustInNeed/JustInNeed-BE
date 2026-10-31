package com.justinneed.settings.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.account.dto.request.AccountWithdrawRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountSettingsServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AuthTokenService authTokenService;

    private AccountSettingsService service;
    private Member member;

    @BeforeEach
    void setUp() {
        service = new AccountSettingsService(memberRepository, authTokenService, mock(CommunityCardSync.class),
                mock(CommunityProfileRepository.class));
        member = new Member(SocialProvider.KAKAO, "provider-id", "before", "user@example.com");
    }

    @Test
    void withdrawRevokesTokensAndMarksMemberWithdrawn() {
        when(memberRepository.findByIdAndWithdrawnAtIsNull(1L)).thenReturn(Optional.of(member));
        var response = service.withdraw(1L, new AccountWithdrawRequest("WITHDRAW", "unused"), "access-token");

        assertThat(member.isWithdrawn()).isTrue();
        // 같은 소셜 계정으로 다시 가입할 수 있게 소셜 식별자 · 닉네임 · 이메일을 비운다
        assertThat(member.getProviderId()).startsWith(Member.WITHDRAWN_PROVIDER_ID_PREFIX);
        assertThat(member.getNickname()).isNull();
        assertThat(member.getEmail()).isNull();
        assertThat(response.withdrawn()).isTrue();
        assertThat(response.extensionLogin()).isFalse();
        verify(authTokenService).revokeAll(1L, "access-token");
    }

    @Test
    void withdrawRejectsInvalidConfirmation() {
        assertThatThrownBy(() -> service.withdraw(
                1L, new AccountWithdrawRequest("DELETE", null), "access-token"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_WITHDRAW_CONFIRMATION);
    }
}
