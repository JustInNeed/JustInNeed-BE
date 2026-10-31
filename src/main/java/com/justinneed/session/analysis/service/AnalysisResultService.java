package com.justinneed.session.analysis.service;

import tools.jackson.databind.ObjectMapper;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.EssayMarkdownConverter;
import com.justinneed.session.analysis.RawLinkVisits;
import com.justinneed.session.analysis.RawLinkVisits.LinkVisit;
import com.justinneed.session.analysis.SessionHashtagSelector;
import com.justinneed.session.analysis.category.SiteCategory;
import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.dto.AiAnalysisResult;
import com.justinneed.session.analysis.dto.AiEssay;
import com.justinneed.session.analysis.dto.AiMindmap;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 분석 결과(/analyze 응답)를 저장한다.
 *
 * <pre>
 * state.unitFeatures · mindmap · selected · analysis · pages · pageVisits → session_results
 * state.userModel (재학습했을 때만)                         → ai_cluster_models (USER)
 * essay    → summaries.essay · summaries.markdown, sessions.title
 * mindmap  → sessions.tags (최대 5개)
 * selected → sessions.sources (링크당 1개, 방문 시각은 원본 기준, 병합된 유닛의 링크 포함)
 * 원본     → sessions.page_count (방문 링크 수), markAnalyzed (21일 뒤 삭제)
 * </pre>
 *
 * AI 콜백(/internal/ai/sessions/{id}/result)으로 받으며, 세션이 ANALYZING 일 때만 저장한다. 지운 세션은 저장하지 않는다.
 * LLM 이 실패해 마인드맵 · 줄글이 없거나 대체 구조면 아무것도 저장하지 않고 세션을 FAILED 로 바꾼 뒤 예외를 던진다.
 * (실패 상태를 남겨야 하므로 CustomException 은 롤백하지 않는다. 검사는 모두 저장 전에 한다.)
 */
@Service
public class AnalysisResultService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultService.class);
    private static final String SOURCE_LLM = "llm";

    private final BrowsingSessionRepository sessionRepository;
    private final SessionResultRepository resultRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final AiClusterModelRepository clusterModelRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public AnalysisResultService(BrowsingSessionRepository sessionRepository,
            SessionResultRepository resultRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            AiClusterModelRepository clusterModelRepository,
            MemberRepository memberRepository,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.resultRepository = resultRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.clusterModelRepository = clusterModelRepository;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = CustomException.class)
    public void save(Long sessionId, AiAnalysisResult result) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        requireInProgress(session);
        // 분석 도중 탈퇴했으면 결과를 저장하지 않는다. 4xx 라 AI 도 재시도하지 않는다
        if (memberRepository.findById(session.getUserId()).map(Member::isWithdrawn).orElse(false)) {
            session.failAnalysis();
            throw new CustomException(ErrorCode.ANALYSIS_MEMBER_WITHDRAWN);
        }

        if (isLlmFailed(result)) {
            session.failAnalysis();
            throw new CustomException(ErrorCode.ANALYSIS_LLM_FAILED);
        }
        SessionRawPayload rawPayload = rawPayloadRepository.findById(sessionId).orElse(null);
        if (rawPayload == null) {
            session.failAnalysis();
            throw new CustomException(ErrorCode.RAW_PAYLOAD_NOT_FOUND);
        }

        RawLinkVisits linkVisits = RawLinkVisits.from(rawPayload.getPayload());
        AiEssay essay = objectMapper.convertValue(result.essay(), AiEssay.class);
        AiMindmap mindmap = objectMapper.convertValue(result.mindmap(), AiMindmap.class);

        saveResult(session, result, linkVisits);
        saveUserModel(session.getUserId(), result.state());
        saveEssay(session, result.essay(), EssayMarkdownConverter.toMarkdown(essay));
        session.completeAnalysis(
                EssayMarkdownConverter.toSessionTitle(essay),
                SessionHashtagSelector.select(mindmap),
                toSources(listOrEmpty(result.selected()), linkVisits),
                linkVisits.linkCount());
        rawPayload.markAnalyzed(LocalDateTime.now());
    }

    // AI 가 분석 중 오류를 알려 오면 FAILED 로 둔다 (원본은 남아 있어 다시 요청할 수 있다)
    @Transactional
    public void fail(Long sessionId, String reason) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        requireInProgress(session);
        session.failAnalysis();
        log.warn("[ai analysis] session {} 분석 실패: {}", sessionId, reason);
    }

    private static void requireInProgress(BrowsingSession session) {
        if (session.isDeleted() || session.getStatus() != SessionStatus.ANALYZING) {
            throw new CustomException(ErrorCode.ANALYSIS_NOT_IN_PROGRESS);
        }
    }

    // 마인드맵 · 줄글이 없거나(LLM 미호출) 대체 구조(LLM 호출 실패)면 실패로 본다
    private static boolean isLlmFailed(AiAnalysisResult result) {
        return result.mindmap() == null
                || result.essay() == null
                || !SOURCE_LLM.equals(result.mindmap().get("structureSource"))
                || !SOURCE_LLM.equals(result.essay().get("source"));
    }

    private void saveResult(BrowsingSession session, AiAnalysisResult result, RawLinkVisits linkVisits) {
        SessionResult sessionResult = resultRepository.findById(session.getId())
                .orElseGet(() -> new SessionResult(session.getId(), session.getUserId()));
        Map<String, Object> analysis = mapOrEmpty(result.analysis());
        sessionResult.replace(
                result.mindmap(),
                listOrEmpty(result.selected()),
                listOf(analysis.get("units")),
                mapOf(analysis.get("summary")),
                withCategory(listOrEmpty(result.pages()), linkVisits),
                listOf(mapOrEmpty(result.state()).get("unitFeatures")),
                result.modelSource(),
                result.scoreMethod());
        sessionResult.replacePageVisits(result.pageVisits());
        resultRepository.save(sessionResult);
    }

    // AI 는 재학습했을 때만 userModel 을 준다. 사용자당 1행이라 있으면 교체한다
    private void saveUserModel(Long userId, Map<String, Object> state) {
        Map<String, Object> userModel = mapOf(mapOrEmpty(state).get("userModel"));
        if (userModel.isEmpty()) {
            return;
        }
        int sessionCount = (int) resultRepository.countByUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        clusterModelRepository.findByScopeAndUserId(ClusterModelScope.USER, userId)
                .ifPresentOrElse(
                        model -> model.replace(userModel, sessionCount, now),
                        () -> clusterModelRepository.save(
                                new AiClusterModel(ClusterModelScope.USER, userId, userModel, sessionCount, now)));
    }

    // 요약 행이 없으면 만들고, 줄글 원본(essay)과 변환한 마크다운을 함께 넣는다
    private static void saveEssay(BrowsingSession session, Map<String, Object> essay, String markdown) {
        Summary summary = session.getSummary();
        if (summary == null) {
            summary = new Summary(session, "");
        }
        summary.replaceEssay(essay, markdown);
    }

    // AI pages 에 링크별 사이트 카테고리를 붙인다 (홈 · 분석 화면 집계용)
    private static List<Map<String, Object>> withCategory(List<Map<String, Object>> pages, RawLinkVisits linkVisits) {
        List<Map<String, Object>> categorized = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            String url = page.get("url") instanceof String text ? text : null;
            SiteCategory category = linkVisits.find(url)
                    .map(LinkVisit::category)
                    .orElseGet(() -> SiteCategory.classify(url));
            Map<String, Object> copy = new LinkedHashMap<>(page);
            copy.put("category", category.name());
            categorized.add(copy);
        }
        return categorized;
    }

    // 컷오프 유닛을 링크별로 묶어 출처 1개로. 발췌는 점수가 가장 높은 유닛, 방문 시각은 원본의 링크 방문 시각
    private static List<Source> toSources(List<Map<String, Object>> selected, RawLinkVisits linkVisits) {
        Map<String, List<Map<String, Object>>> unitsByLink = new LinkedHashMap<>();
        for (Map<String, Object> unit : selected) {
            if (unit.get("pageUrl") instanceof String url && !url.isBlank()) {
                unitsByLink.computeIfAbsent(url, key -> new ArrayList<>()).add(unit);
            }
        }

        List<Source> sources = new ArrayList<>();
        unitsByLink.forEach((url, units) -> {
            Map<String, Object> best = units.stream()
                    .max(Comparator.comparingDouble(AnalysisResultService::score))
                    .orElseThrow();
            LocalDateTime visitedAt = linkVisits.find(url)
                    .map(LinkVisit::firstVisitedAt)
                    .orElseGet(() -> earliestSeenAt(units));
            sources.add(new Source(text(units.get(0).get("pageTitle")), url, text(best.get("text")), visitedAt));
        });
        addMergedLinks(selected, linkVisits, sources);
        sources.sort(Comparator.comparing(Source::visitedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return sources;
    }

    // 내용이 같아 대표 유닛에 합쳐진 유닛은 selected 에 없고 대표의 mergedFrom("pageId#pid")에만 남는다.
    // 그 링크가 아직 출처에 없으면 출처로 더한다. 글이 같은 내용이므로 발췌는 점수가 높은 대표의 문단을 쓴다
    private static void addMergedLinks(List<Map<String, Object>> selected, RawLinkVisits linkVisits,
            List<Source> sources) {
        Set<String> links = new HashSet<>(sources.stream().map(Source::url).toList());
        selected.stream()
                .sorted(Comparator.comparingDouble(AnalysisResultService::score).reversed())
                .forEach(unit -> {
                    for (Object merged : listOf(unit.get("mergedFrom"))) {
                        if (merged instanceof String unitKey) {
                            String pageId = pageIdOf(unitKey);
                            Optional<LinkVisit> visit = linkVisits.findByPageId(pageId);
                            String url = visit.map(LinkVisit::url).orElse(pageId);
                            if (!url.isBlank() && links.add(url)) {
                                sources.add(new Source(visit.map(LinkVisit::title).orElse(""), url,
                                        text(unit.get("text")),
                                        visit.map(LinkVisit::firstVisitedAt)
                                                .orElseGet(() -> earliestSeenAt(List.of(unit)))));
                            }
                        }
                    }
                });
    }

    private static String pageIdOf(String unitKey) {
        int separator = unitKey.lastIndexOf('#');
        return separator < 0 ? unitKey : unitKey.substring(0, separator);
    }

    // 원본에 없는 링크면 유닛을 처음 본 시각 중 가장 이른 값으로 대신한다
    private static LocalDateTime earliestSeenAt(List<Map<String, Object>> units) {
        return units.stream()
                .map(unit -> RawLinkVisits.toKst(unit.get("firstSeenAt")))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static double score(Map<String, Object> unit) {
        return unit.get("score") instanceof Number number ? number.doubleValue() : Double.NEGATIVE_INFINITY;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, Object> mapOrEmpty(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
