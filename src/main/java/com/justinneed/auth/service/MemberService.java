package com.justinneed.auth.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.dto.MemberResponse;
import com.justinneed.auth.dto.NicknameAvailabilityResponse;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            // 커밋 때 터지면 잡을 곳이 없으므로 여기서 플러시한다 (위 확인을 동시에 통과한 요청은 유니크 제약에 걸린다)
            memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }
        return MemberResponse.from(member);
    }

    // 저장(updateNickname)과 같은 기준: 대소문자 무시, 본인의 현재 닉네임은 사용 가능
    public NicknameAvailabilityResponse checkNicknameAvailability(Long memberId, String nickname) {
        boolean taken = memberRepository.existsByNicknameIgnoreCaseAndIdNot(nickname, memberId);
        return new NicknameAvailabilityResponse(nickname, !taken);
    }

    // 탈퇴한 회원은 토큰이 남아 있어도 없는 회원으로 본다
    private Member findMember(Long memberId) {
        return memberRepository.findByIdAndWithdrawnAtIsNull(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
