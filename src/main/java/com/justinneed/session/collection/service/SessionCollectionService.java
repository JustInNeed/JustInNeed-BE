package com.justinneed.session.collection.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 확장 청크가 가리키는 세션 행 찾기 · 만들기. 확장은 시작 · 종료 요청을 보내지 않으므로 첫 청크를 받을 때 만든다.
 *
 * <pre>
 * 행이 있으면  그대로 쓴다 (지운 세션도 찾으므로 삭제된 세션을 다시 만들지 않는다)
 * 행이 없으면  COLLECTING, 임시 제목으로 만든다
 * </pre>
 *
 * 재전송해도 안전하다. 동시에 만들면 유니크 제약에 걸린 쪽이 먼저 만든 행을 쓴다 — 그래서 바깥에 트랜잭션을 두지 않는다.
 */
@Service
public class SessionCollectionService {

    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final TransactionTemplate transactionTemplate;

    public SessionCollectionService(BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Long findOrCreate(Long userId, String collectorSessionId, LocalDateTime startedAt) {
        Long existing = findId(userId, collectorSessionId);
        if (existing != null) {
            return existing;
        }
        try {
            return transactionTemplate.execute(status -> insert(userId, collectorSessionId, startedAt));
        } catch (DataIntegrityViolationException race) {
            Long winner = findId(userId, collectorSessionId);
            if (winner == null) {
                throw race;
            }
            return winner;
        }
    }

    private Long insert(Long userId, String collectorSessionId, LocalDateTime startedAt) {
        BrowsingSession session = sessionRepository.saveAndFlush(
                new BrowsingSession(userId, temporaryTitle(userId), startedAt, collectorSessionId));
        collectionRepository.save(new SessionCollection(session.getId()));
        return session.getId();
    }

    // 2026.11.02의 1번째 세션. 분석이 성공하면 AI 결과 제목으로 바뀐다. 번호는 표시용이라 동시 요청으로 겹칠 수 있다
    private String temporaryTitle(Long userId) {
        LocalDate today = LocalDate.now(KST);
        long order = sessionRepository.countCreatedBetween(userId,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay()) + 1;
        return today.format(TITLE_DATE) + "의 " + order + "번째 세션";
    }

    // 잠그기 전에는 id 만 읽는다 (BrowsingSessionRepository.findIdByCollector)
    private Long findId(Long userId, String collectorSessionId) {
        return sessionRepository.findIdByCollector(userId, collectorSessionId).orElse(null);
    }
}
