package com.justinneed.community;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
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
    protected Long ownerId;
    protected Long viewerId;

    @BeforeEach
    void createMembers() {
        ownerId = members.saveAndFlush(new Member(SocialProvider.KAKAO, "community-owner", "Owner", null)).getId();
        viewerId = members.saveAndFlush(new Member(SocialProvider.NAVER, "community-viewer", "Viewer", null)).getId();
    }

    protected String auth(Long id) {
        return "Bearer " + tokens.createAccessToken(id);
    }

    protected BrowsingSession session(Long owner, boolean isPublic, String... tags) {
        BrowsingSession session = new BrowsingSession(owner, "private title", LocalDateTime.now());
        session.update(null, null, isPublic, false, List.of(tags), null);
        session.replaceSources(List.of(new Source("private source", "https://example.com", "private excerpt")));
        new Summary(session, "private heading", "private markdown");
        return sessions.saveAndFlush(session);
    }
}
