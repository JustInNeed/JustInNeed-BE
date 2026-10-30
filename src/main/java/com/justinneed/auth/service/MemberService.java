package com.justinneed.auth.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.dto.MemberResponse;
import com.justinneed.auth.dto.NicknameAvailabilityResponse;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public MemberResponse getMe(Long memberId) {
        return MemberResponse.from(findMember(memberId));
    }

    @Transactional
    public MemberResponse updateNickname(Long memberId, String nickname) {
        Member member = findMember(memberId);
        if (memberRepository.existsByNicknameIgnoreCaseAndIdNot(nickname, memberId)) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }
        member.updateNickname(nickname);
        return MemberResponse.from(member);
    }

    // 저장(updateNickname)과 같은 기준: 대소문자 무시, 본인의 현재 닉네임은 사용 가능
    public NicknameAvailabilityResponse checkNicknameAvailability(Long memberId, String nickname) {
        boolean taken = memberRepository.existsByNicknameIgnoreCaseAndIdNot(nickname, memberId);
        return new NicknameAvailabilityResponse(nickname, !taken);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
