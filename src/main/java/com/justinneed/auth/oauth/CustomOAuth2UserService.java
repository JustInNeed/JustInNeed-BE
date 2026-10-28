package com.justinneed.auth.oauth;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 소셜 프로필 조회 후 회원을 조회/생성한다. (방법 B: 첫 로그인 시 닉네임 null로 즉시 생성)
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
        SocialProvider provider = SocialProvider.valueOf(registrationId.toUpperCase());
        OAuth2UserInfo info = OAuth2UserInfo.of(provider, oAuth2User.getAttributes());

        Member member = memberRepository.findBySocialProviderAndProviderId(provider, info.getProviderId())
                .orElseGet(() -> memberRepository.save(
                        new Member(provider, info.getProviderId(), null, info.getEmail())));
        if (member.isWithdrawn()) {
            throw new OAuth2AuthenticationException("withdrawn_account");
        }

        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        attributes.put(ATTR_MEMBER_ID, member.getId());
        attributes.put(ATTR_NEEDS_NICKNAME, member.getNickname() == null);
        attributes.put(ATTR_PROVIDER_ID, info.getProviderId());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                ATTR_PROVIDER_ID);
    }
}
