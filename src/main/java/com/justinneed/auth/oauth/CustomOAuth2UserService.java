package com.justinneed.auth.oauth;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 소셜 프로필 조회 후 회원을 조회/생성한다. (방법 B: 첫 로그인 시 닉네임 null로 즉시 생성)
 * 탈퇴한 소셜 계정으로 다시 로그인하면 새 회원으로 가입시킨다. (닉네임 null → 닉네임 입력부터)
 * 반환되는 principal 의 attributes 에 memberId / needsNickname 을 실어 SuccessHandler 로 전달한다.
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    public static final String ATTR_MEMBER_ID = "memberId";
    public static final String ATTR_NEEDS_NICKNAME = "needsNickname";
    public static final String ATTR_PROVIDER_ID = "providerId";

    private final MemberRepository memberRepository;

    public CustomOAuth2UserService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        return resolve(registrationId, oAuth2User.getAttributes());
    }

    /**
     * 소셜 프로필 attributes 로 회원을 조회/생성하고 principal 을 만든다.
     * 프로바이더 호출(super.loadUser)과 분리해 두어 테스트에서 프로필 JSON 만으로 부를 수 있다.
     */
    public OAuth2User resolve(String registrationId, Map<String, Object> profileAttributes) {
        SocialProvider provider = SocialProvider.valueOf(registrationId.toUpperCase());
        OAuth2UserInfo info = OAuth2UserInfo.of(provider, profileAttributes);

        Member member = findOrCreate(provider, info);

        Map<String, Object> attributes = new HashMap<>(profileAttributes);
        attributes.put(ATTR_MEMBER_ID, member.getId());
        attributes.put(ATTR_NEEDS_NICKNAME, member.getNickname() == null);
        attributes.put(ATTR_PROVIDER_ID, info.getProviderId());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                ATTR_PROVIDER_ID);
    }

    private Member findOrCreate(SocialProvider provider, OAuth2UserInfo info) {
        Optional<Member> found = memberRepository.findBySocialProviderAndProviderId(provider, info.getProviderId());
        if (found.isPresent() && !found.get().isWithdrawn()) {
            return found.get();
        }
        // 익명화 도입 전에 탈퇴한 행은 아직 소셜 식별자를 쥐고 있다 → 먼저 비우고(유니크 제약) 새 회원을 만든다
        found.ifPresent(this::anonymizeWithdrawn);
        return memberRepository.save(new Member(provider, info.getProviderId(), null, info.getEmail()));
    }

    private void anonymizeWithdrawn(Member withdrawn) {
        withdrawn.anonymize();
        memberRepository.saveAndFlush(withdrawn);
    }
}
