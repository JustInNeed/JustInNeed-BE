package com.justinneed.community.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 공개 카드 찾기. 탈퇴한 작성자의 카드는 나오지 않는다.
 * 해시태그는 json 컬럼이라 글자로 바꿔 포함 여부만 본다. 태그 경계 · 작성자의 비공개 해시태그는 SQL 에서 알 수 없어
 * 여기서는 후보만 좁히고, 정확한 판단은 부르는 쪽이 CommunitySharing.visibleTags 로 한다.
 */
@Repository
public class PublicCardSearch {
    private static final String TAG_TEXT_HAS = "locate(:%s, lower(cast(c.hashtags as string))) > 0";
    private static final String NICKNAME_HAS = "locate(:q, lower(m.nickname)) > 0";
    private static final String TITLE_HAS = "(p.visibility = com.justinneed.community.domain.NodeVisibility.CONTENT "
            + "and locate(:q, lower(c.title)) > 0)";
    private static final String FROM = " from CommunityCardRow c join Member m on m.id = c.ownerId ";
    private static final String MINDMAP_HIT = "select new " + MindmapHit.class.getName()
            + "(c.sessionId, c.ownerId, c.hashtags, case when " + NICKNAME_HAS + " or " + TITLE_HAS
            + " then true else false end)" + FROM + "left join CommunityProfile p on p.userId = c.ownerId"
            + " where m.withdrawnAt is null and (" + TAG_TEXT_HAS.formatted("q") + " or " + NICKNAME_HAS + " or " + TITLE_HAS + ")"
            + " order by c.lastActivityAt desc, c.sessionId desc";
    private static final String OWNER_HIT = "select distinct c.ownerId" + FROM
            + "where m.withdrawnAt is null and c.ownerId > :after and (" + TAG_TEXT_HAS.formatted("q") + " or " + NICKNAME_HAS + ")"
            + " order by c.ownerId";

    @PersistenceContext
    private EntityManager entityManager;

    /** q 가 태그 글자 · 닉네임 · 내용까지 공개한 카드의 제목에 든 카드. 최근 활동순 → id 큰 순. */
    public List<MindmapHit> mindmaps(String q, int batch, int batchSize) {
        return entityManager.createQuery(MINDMAP_HIT, MindmapHit.class).setParameter("q", q)
                .setFirstResult(batch * batchSize).setMaxResults(batchSize).getResultList();
    }

    /** q 가 태그 글자 · 닉네임에 든 작성자 id. id 순으로 after 다음부터. */
    public List<Long> owners(String q, Long after, int limit) {
        return entityManager.createQuery(OWNER_HIT, Long.class).setParameter("q", q).setParameter("after", after)
                .setMaxResults(limit).getResultList();
    }

    /** keys(소문자) 중 하나라도 태그 글자에 든 카드, id 순. exceptOwner 의 카드는 뺀다 (null 이면 빼지 않음). */
    public List<TaggedCard> tagged(List<String> keys, Long exceptOwner) {
        List<String> anyKey = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            anyKey.add(TAG_TEXT_HAS.formatted("k" + i));
        }
        TypedQuery<TaggedCard> query = entityManager.createQuery("select new " + TaggedCard.class.getName()
                + "(c.sessionId, c.ownerId, c.hashtags, c.lastActivityAt)" + FROM + "where m.withdrawnAt is null"
                + (exceptOwner == null ? "" : " and c.ownerId <> :exceptOwner")
                + " and (" + String.join(" or ", anyKey) + ") order by c.sessionId", TaggedCard.class);
        for (int i = 0; i < keys.size(); i++) {
            query.setParameter("k" + i, keys.get(i));
        }
        if (exceptOwner != null) {
            query.setParameter("exceptOwner", exceptOwner);
        }
        return query.getResultList();
    }

    /** matchedText: 닉네임이나 공개된 제목에 걸렸다. false 면 태그로만 걸린 후보다. */
    public record MindmapHit(Long sessionId, Long ownerId, List<String> hashtags, Boolean matchedText) {
    }

    public record TaggedCard(Long sessionId, Long ownerId, List<String> hashtags, LocalDateTime lastActivityAt) {
    }
}
