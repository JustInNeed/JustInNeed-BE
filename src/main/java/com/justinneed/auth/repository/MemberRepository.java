package com.justinneed.auth.repository;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 재로그인 시 (프로바이더 + 프로바이더 발급 id)로 기존 회원 조회
    Optional<Member> findBySocialProviderAndProviderId(SocialProvider socialProvider, String providerId);

    Optional<Member> findByIdAndWithdrawnAtIsNull(Long id);

    boolean existsByIdAndWithdrawnAtIsNull(Long id);

    // 인증 필터용: 행이 있고 탈퇴했을 때만 true (회원 행이 없는 id 는 false)
    boolean existsByIdAndWithdrawnAtIsNotNull(Long id);

    // 익명화 도입 전에 탈퇴해 소셜 식별자 · 닉네임을 아직 쥐고 있는 회원 (LegacyWithdrawnMemberCleanup)
    List<Member> findByWithdrawnAtIsNotNullAndProviderIdNotLike(String providerIdPattern);

    // 닉네임 중복 검사 (대소문자 무시, 본인 제외)
    boolean existsByNicknameIgnoreCaseAndIdNot(String nickname, Long id);
}
