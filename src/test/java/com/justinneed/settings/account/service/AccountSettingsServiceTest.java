package com.justinneed.settings.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.service.AuthTokenService;
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
        service = new AccountSettingsService(memberRepository, authTokenService);
        member = new Member(SocialProvider.KAKAO, "provider-id", "before", "user@example.com");
    }

    @Test
    void updateNicknameChangesAvailableNickname() {
        when(memberRepository.findByIdAndWithdrawnAtIsNull(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNicknameIgnoreCaseAndIdNot("justin", 1L)).thenReturn(false);

        var response = service.updateNickname(1L, "justin");

        assertThat(member.getNickname()).isEqualTo("justin");
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.nickname()).isEqualTo("justin");
    }

    @Test
    void updateNicknameRejectsDuplicateNickname() {
        when(memberRepository.findByIdAndWithdrawnAtIsNull(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNicknameIgnoreCaseAndIdNot("justin", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateNickname(1L, "justin"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);
    }

    @Test
    void withdrawRevokesTokensAndMarksMemberWithdrawn() {
        when(memberRepository.findByIdAndWithdrawnAtIsNull(1L)).thenReturn(Optional.of(member));
        var response = service.withdraw(1L, new AccountWithdrawRequest("WITHDRAW", "unused"), "access-token");

        assertThat(member.isWithdrawn()).isTrue();
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
