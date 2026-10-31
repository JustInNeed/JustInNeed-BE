package com.justinneed.community.follow;

import static com.justinneed.community.repository.CommunitySessionRepository.PUBLIC_SESSION;

import com.justinneed.session.management.domain.BrowsingSession;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 태그 비교에 필요한 컬럼만 읽는다 (세션의 출처 · 요약을 읽지 않는다). 탈퇴 회원은 여기서 빠진다
public interface SimilarPeopleRepository extends Repository<BrowsingSession, Long> {
    @Query("""
            select new com.justinneed.community.follow.SimilarPeopleRepository$ProfileRow(
                p.userId, m.nickname, p.interests, p.hiddenHashtags)
            from CommunityProfile p, Member m
            where m.id = p.userId and m.withdrawnAt is null
            """)
    List<ProfileRow> findProfileRows();

    @Query("""
            select new com.justinneed.community.follow.SimilarPeopleRepository$SessionRow(
                s.userId, m.nickname, s.tags, coalesce(s.endedAt, s.startedAt))
            from BrowsingSession s, Member m
            where m.id = s.userId and m.withdrawnAt is null and
            """ + PUBLIC_SESSION + """
            order by s.id desc
            """)
    List<SessionRow> findPublicSessionRows();

    record ProfileRow(Long userId, String nickname, List<String> interests, List<String> hiddenHashtags) { }
    record SessionRow(Long userId, String nickname, List<String> tags, LocalDateTime activityAt) { }
}
