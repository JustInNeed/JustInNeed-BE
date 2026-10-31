package com.justinneed.community;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public abstract class CommunityTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected MemberRepository members;
    @Autowired protected BrowsingSessionRepository sessions;
    @Autowired protected CommunityProfileRepository profiles;
    @Autowired protected JwtTokenProvider tokens;
    @Autowired protected CommunityCardSync cardSync;
    @Autowired protected CommunityTags communityTags;
    protected Long ownerId;
    protected Long viewerId;

    @BeforeEach
    void createMembers() {
        ownerId = members.saveAndFlush(new Member(SocialProvider.KAKAO, "community-owner", "Owner", null)).getId();
        viewerId = members.saveAndFlush(new Member(SocialProvider.NAVER, "community-viewer", "Viewer", null)).getId();
    }

    /** 프로필 행을 테스트에서 바로 저장했을 때의 해시태그 행 (실제로는 관심사 · 비공개 설정을 저장하며 만들어진다). */
    protected void syncProfileTags(CommunityProfile profile) {
        communityTags.ensure(profile.getUserId(), profile.getInterests());
        communityTags.ensure(profile.getUserId(), profile.getHiddenHashtags());
    }

    protected String auth(Long id) {
        return "Bearer " + tokens.createAccessToken(id);
    }

    protected BrowsingSession session(Long owner, boolean isPublic, String... tags) {
        BrowsingSession session = new BrowsingSession(owner, "private title", LocalDateTime.now());
        session.complete(LocalDateTime.now(), 1);
        session.update(null, null, isPublic, false, List.of(tags));
        session.replaceSources(List.of(new Source("private source", "https://example.com", "private excerpt")));
        new Summary(session, "private markdown");
        BrowsingSession saved = sessions.saveAndFlush(session);
        cardSync.sync(saved);
        return saved;
    }
}
