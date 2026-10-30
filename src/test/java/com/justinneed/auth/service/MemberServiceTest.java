package com.justinneed.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("중복 확인을 통과했어도 저장 순간 유니크 제약에 걸리면 DUPLICATE_NICKNAME 으로 바꾼다")
    void uniqueViolationBecomesDuplicateNickname() {
        Member member = new Member(SocialProvider.KAKAO, "provider-id", null, null);
        when(memberRepository.findByIdAndWithdrawnAtIsNull(1L)).thenReturn(Optional.of(member));
        when(memberRepository.existsByNicknameIgnoreCaseAndIdNot("justin", 1L)).thenReturn(false);
        when(memberRepository.saveAndFlush(member)).thenThrow(new DataIntegrityViolationException("uk_nickname"));

        assertThatThrownBy(() -> memberService.updateNickname(1L, "justin"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);
    }
}
