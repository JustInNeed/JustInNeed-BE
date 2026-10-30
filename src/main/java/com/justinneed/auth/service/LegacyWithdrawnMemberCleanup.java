package com.justinneed.auth.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 시 익명화(소셜 식별자 · 닉네임 · 이메일 비우기)를 도입하기 전에 탈퇴한 회원 행을 앱 시작 때 익명화한다.
 * 그대로 두면 그 닉네임(유니크)을 아무도 쓸 수 없고, 같은 소셜 계정의 재가입도 막힌다.
 * 이미 익명화된 행은 건드리지 않으므로 매번 시작할 때 실행해도 안전하다.
 */
@Component
public class LegacyWithdrawnMemberCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyWithdrawnMemberCleanup.class);

    private final MemberRepository memberRepository;

    public LegacyWithdrawnMemberCleanup(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Member> legacy = memberRepository.findByWithdrawnAtIsNotNullAndProviderIdNotLike(
                Member.WITHDRAWN_PROVIDER_ID_PREFIX + "%");
        legacy.forEach(Member::anonymize);
        if (!legacy.isEmpty()) {
            log.info("[withdrawn member cleanup] 익명화 전에 탈퇴한 회원 {}건 익명화", legacy.size());
        }
    }
}
