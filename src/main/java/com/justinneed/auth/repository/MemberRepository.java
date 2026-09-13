package com.justinneed.auth.repository;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 재로그인 시 (프로바이더 + 프로바이더 발급 id)로 기존 회원 조회
    Optional<Member> findBySocialProviderAndProviderId(SocialProvider socialProvider, String providerId);

    Optional<Member> findByIdAndWithdrawnAtIsNull(Long id);

    boolean existsByNicknameIgnoreCaseAndIdNot(String nickname, Long id);
}
