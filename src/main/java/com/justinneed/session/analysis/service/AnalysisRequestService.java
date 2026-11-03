package com.justinneed.session.analysis.service;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.RawLinkVisits;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.CollectionSettings;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Spring → AI 분석 요청.
 *
 * <pre>
 * 수집 완료 (finishCollection)  COLLECTING → READY, 커밋 뒤 비동기로 아래 실행 (원본 저장과 같은 트랜잭션)
 * 재분석 (reanalyze)            FAILED → READY, 만료 · 시도 기록 초기화, 커밋 뒤 비동기로 아래 실행
 * 분석 요청 (requestAnalysis)   READY → ANALYZING 커밋 → POST /analyze { sessionId } (합치기가 끝났다는 알림)
 *                               AI 는 202 로 접수하고 GET /input 으로 원본을 가져온다 (AiInternalController)
 *                               결과 · 실패는 콜백으로 온다 (AiCallbackController)
 * 재전송 (resendIfDue)          GET /input 이 없으면 복구 스케줄러가 호출. /analyze 첫 호출 실패도 이 규칙을 따른다
 *                               응답이 있으면 계속 대기, 재전송 두 번 연달아 응답이 없으면 FAILED
 *                               계속 대기 중이어도 전체 상한(ai-wait-limit)이 지나면 FAILED
 * 콜백 시간 초과                GET /input 뒤 콜백이 없으면 FAILED (failIfCallbackOverdue)
 * </pre>
 */
@Service
public class AnalysisRequestService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestService.class);
    // 재전송에 연달아 응답이 없으면 FAILED 로 보는 횟수 (1분 뒤 재전송, 2분 뒤 재전송)
    private static final int MAX_UNANSWERED = 2;

    private final BrowsingSessionRepository sessionRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final SessionCollectionRepository collectionRepository;
    private final MemberRepository memberRepository;
    private final AiAnalysisClient aiClient;
    private final CommunityCardSync cards;
    private final AnalysisFailures failures;
    private final CollectionSettings settings;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public AnalysisRequestService(BrowsingSessionRepository sessionRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            SessionCollectionRepository collectionRepository,
            MemberRepository memberRepository,
            AiAnalysisClient aiClient,
            CommunityCardSync cards,
            AnalysisFailures failures,
            CollectionSettings settings,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.collectionRepository = collectionRepository;
        this.memberRepository = memberRepository;
        this.aiClient = aiClient;
        this.cards = cards;
        this.failures = failures;
        this.settings = settings;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // 합치기가 끝났을 때 호출 (ChunkSessionService). 원본은 같은 트랜잭션에서 session_raw_payloads 에 저장한다
    @Transactional
    public void finishCollection(Long sessionId) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() != SessionStatus.COLLECTING) {
            throw new CustomException(ErrorCode.SESSION_NOT_COLLECTING);
        }
        session.markReady();
        eventPublisher.publishEvent(new SessionReadyEvent(sessionId));
    }

    // 실패한 세션 다시 분석 (S-06). 두 번 눌러도 둘째 요청은 잠금 뒤 READY 를 보고 409 라 AI 요청은 한 번만 나간다
    @Transactional
    public SessionDetailResponse reanalyze(Long userId, Long sessionId) {
        BrowsingSession session = sessionRepository.lockOwned(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() != SessionStatus.FAILED) {
            throw new CustomException(ErrorCode.SESSION_NOT_FAILED);
        }
        SessionRawPayload rawPayload = rawPayloadRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RAW_PAYLOAD_NOT_FOUND));
        rawPayload.clearExpiry();
        collectionRepository.getOrCreate(sessionId).resetForReanalysis();
        session.markReady();
        cards.sync(session);
        eventPublisher.publishEvent(new SessionReadyEvent(sessionId));
        return SessionDetailResponse.from(session);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionReady(SessionReadyEvent event) {
        requestAnalysis(event.sessionId());
    }

    // ANALYZING 을 먼저 커밋한 뒤 호출한다 (AI 콜백이 먼저 와도 상태가 맞도록). 실패해도 재전송 규칙을 따르므로 FAILED 로 두지 않는다
    public void requestAnalysis(Long sessionId) {
        AiAnalyzeRequest request = transactionTemplate.execute(status -> startAnalysis(sessionId));
        if (request != null) {
            send(request);
        }
    }

    // 재전송할 때가 됐으면 보낸다. 네트워크 호출 동안 세션 행을 잡지 않도록 준비 · 전송 · 결과 기록을 나눈다
    public void resendIfDue(Long sessionId, LocalDateTime now) {
        boolean due = Boolean.TRUE.equals(transactionTemplate.execute(status -> prepareResend(sessionId, now)));
        if (!due) {
            return;
        }
        boolean answered = send(new AiAnalyzeRequest(sessionId));
        transactionTemplate.executeWithoutResult(status -> recordResend(sessionId, answered));
    }

    // GET /input 뒤 콜백을 기다리는 시간이 지났으면 FAILED
    public void failIfCallbackOverdue(Long sessionId, LocalDateTime now) {
        transactionTemplate.executeWithoutResult(status -> {
            BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
            if (session == null || session.isDeleted() || session.getStatus() != SessionStatus.ANALYZING) {
                return;
            }
            LocalDateTime fetchedAt = collectionRepository.getOrCreate(sessionId).getInputFetchedAt();
            if (fetchedAt != null && !fetchedAt.plus(settings.callbackTimeout()).isAfter(now)) {
                log.warn("[ai analysis] session {} 콜백이 오지 않아 FAILED", sessionId);
                failures.fail(session);
            }
        });
    }

    private boolean send(AiAnalyzeRequest request) {
        try {
            aiClient.requestAnalysis(request);
            return true;
        } catch (RestClientException e) {
            log.warn("[ai analysis] session {} 분석 요청 실패: {}", request.sessionId(), e.getMessage());
            return false;
        }
    }

    private boolean prepareResend(Long sessionId, LocalDateTime now) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (!isAwaitingInput(session)) {
            return false;
        }
        SessionCollection collection = collectionRepository.getOrCreate(sessionId);
        if (!collection.getAiRequestedAt().plus(settings.aiWaitLimit()).isAfter(now)) {
            log.warn("[ai analysis] session {} AI 대기 상한이 지나 FAILED", sessionId);
            failures.fail(session);
            return false;
        }
        if (!collection.isResendDue(settings.resendFirstDelay(), settings.resendSecondDelay(), now)) {
            return false;
        }
        collection.markResent(now);
        return true;
    }

    // 보내는 사이 GET /input 이나 콜백이 왔으면 결과를 무시한다
    private void recordResend(Long sessionId, boolean answered) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (!isAwaitingInput(session)) {
            return;
        }
        SessionCollection collection = collectionRepository.getOrCreate(sessionId);
        if (answered) {
            collection.resendAnswered();
        } else if (collection.resendUnanswered() >= MAX_UNANSWERED) {
            log.warn("[ai analysis] session {} 재전송에 응답이 없어 FAILED", sessionId);
            failures.fail(session);
        }
    }

    private boolean isAwaitingInput(BrowsingSession session) {
        if (session == null || session.isDeleted() || session.getStatus() != SessionStatus.ANALYZING) {
            return false;
        }
        SessionCollection collection = collectionRepository.getOrCreate(session.getId());
        return collection.getAiRequestedAt() != null && collection.getInputFetchedAt() == null;
    }

    // 지웠거나 READY 가 아니면(이미 요청됨 등) 건너뛴다. 원본이 없거나 탈퇴한 회원의 세션은 AI 로 보내지 않고 FAILED
    private AiAnalyzeRequest startAnalysis(Long sessionId) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (session == null || session.isDeleted() || session.getStatus() != SessionStatus.READY) {
            log.info("[ai analysis] session {} 는 분석 대기 중이 아니라 분석 요청을 건너뜀", sessionId);
            return null;
        }
        Long userId = session.getUserId();
        if (memberRepository.findById(userId).map(Member::isWithdrawn).orElse(false)) {
            log.info("[ai analysis] session {} 는 탈퇴한 회원의 세션이라 FAILED", sessionId);
            failures.fail(session);
            return null;
        }
        SessionRawPayload rawPayload = rawPayloadRepository.findById(sessionId).orElse(null);
        if (rawPayload == null) {
            log.warn("[ai analysis] session {} 원본이 없어 FAILED", sessionId);
            failures.fail(session);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        session.startAnalysis(RawLinkVisits.sessionEndedAt(rawPayload.getPayload()).orElse(now));
        collectionRepository.getOrCreate(sessionId).startAiRequest(now);
        return new AiAnalyzeRequest(sessionId);
    }
}
