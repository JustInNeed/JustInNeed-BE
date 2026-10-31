package com.justinneed.home.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.analytics.ReadingDistribution;
import com.justinneed.home.HomeGraphBuilder;
import com.justinneed.home.dto.HomeResponse;
import com.justinneed.home.dto.HomeResponse.DayActivity;
import com.justinneed.home.dto.HomeResponse.Graph;
import com.justinneed.home.dto.HomeResponse.Stats;
import com.justinneed.home.dto.HomeResponse.TagGroup;
import com.justinneed.home.dto.HomeResponse.TimelineSlot;
import com.justinneed.home.dto.HomeResponse.TopTag;
import com.justinneed.home.dto.HomeResponse.Visit;
import com.justinneed.home.dto.HomeResponse.WeekActivity;
import com.justinneed.home.dto.HomeResponse.WeekSession;
import com.justinneed.home.repository.HomeSessionRepository;
import com.justinneed.home.repository.HomeSessionRow;
import com.justinneed.session.analysis.category.SiteCategory;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈 대시보드 집계. 대상은 내 완료(COMPLETED) · 미삭제 세션이고, 날짜 기준은 세션 시작 시각(KST)이다.
 *
 * <pre>
 * 이번 주 = 오늘 포함 최근 7일, 지난주 = 그 앞 7일
 * 세션 길이 = 종료 시각 - 시작 시각. 종료 시각이 없으면 세션 수에만 들어간다
 * Deep Reading 비율 = Deep Reading 유닛 수 ÷ 전체 유닛 수 (Idle 포함)
 * </pre>
 *
 * 세션 통계는 세션 행만으로 계산하므로 분석 결과가 없거나 AI JSON 이 어긋난 세션도 통계에는 들어간다.
 */
@Service
@Transactional(readOnly = true)
public class HomeService {

    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final int WEEK_DAYS = 7;
    private static final int MAX_TOP_TAGS = 10;
    private static final int MAX_FILTER_TAGS = 20;
    private static final int MAX_TODAY_SITES = 3;
    private static final int MAX_SESSIONS = 300;
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final List<String> WEEKDAYS = List.of("월", "화", "수", "목", "금", "토", "일");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M.d");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    // 쿼리에는 URI 가 거부하는 문자(| { } 등)가 그대로 남을 수 있어 host 판단에 필요한 앞부분만 쓴다
    private static final Pattern WEB_ORIGIN = Pattern.compile("^https?://[^/?#]*", Pattern.CASE_INSENSITIVE);

    private final HomeSessionRepository homeSessionRepository;
    private final TagGroupRepository tagGroupRepository;
    private final Clock clock;

    public HomeService(HomeSessionRepository homeSessionRepository, TagGroupRepository tagGroupRepository,
            Clock clock) {
        this.homeSessionRepository = homeSessionRepository;
        this.tagGroupRepository = tagGroupRepository;
        this.clock = clock;
    }

    public HomeResponse getHome(Long userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        LocalDate weekStart = today.minusDays(WEEK_DAYS - 1);

        List<HomeSessionRow> sessions = homeSessionRepository.findCompletedByUserId(userId);
        List<HomeSessionRow> thisWeek = oldestFirstBetween(sessions, weekStart, today);
        List<HomeSessionRow> lastWeek = oldestFirstBetween(sessions, weekStart.minusDays(WEEK_DAYS),
                weekStart.minusDays(1));

        HomeGraphBuilder.Result graph = HomeGraphBuilder.build(sessions, today);
        List<TagTally> tags = rankTags(sessions);

        return new HomeResponse(
                new Graph(graph.nodes(), graph.edges(), graph.nodeMeta(), tagGroups(userId), filterTags(tags)),
                stats(graph, tags.size(), thisWeek, lastWeek),
                weekActivity(thisWeek, weekStart, today),
                topTags(tags),
                todayTimeline(thisWeek, today),
                weekSessions(thisWeek, today),
                recentSessions(sessions)
        );
    }

    /** 홈 stats.totalNodes 와 같은 값. */
    public int totalNodes(Long userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        return HomeGraphBuilder.build(homeSessionRepository.findCompletedByUserId(userId), today).totalNodes();
    }

    private static List<HomeSessionRow> oldestFirstBetween(List<HomeSessionRow> newestFirst, LocalDate from,
            LocalDate to) {
        List<HomeSessionRow> sessions = newestFirst.stream()
                .filter(session -> !session.startDate().isBefore(from) && !session.startDate().isAfter(to))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(sessions);
        return sessions;
    }

    private static Stats stats(HomeGraphBuilder.Result graph, int hashtagCount, List<HomeSessionRow> thisWeek,
            List<HomeSessionRow> lastWeek) {
        double averageSeconds = thisWeek.stream()
                .filter(session -> session.endedAt() != null)
                .mapToLong(HomeService::lengthSeconds)
                .average()
                .orElse(0);
        ReadingDistribution thisWeekUnits = readingUnits(thisWeek);
        ReadingDistribution lastWeekUnits = readingUnits(lastWeek);
        int deepReadingDelta = thisWeekUnits.hasData() && lastWeekUnits.hasData()
                ? thisWeekUnits.deepReadingPct() - lastWeekUnits.deepReadingPct()
                : 0;

        return new Stats(
                graph.totalNodes(),
                graph.nodeDelta(),
                hashtagCount,
                thisWeek.size(),
                thisWeek.size() - lastWeek.size(),
                (int) Math.round(averageSeconds / SECONDS_PER_MINUTE),
                thisWeekUnits.deepReadingPct(),
                deepReadingDelta
        );
    }

    // 세션은 시작한 날에 넣는다. 자정을 넘긴 세션도 시작일 한 칸에 모두 들어간다
    private static WeekActivity weekActivity(List<HomeSessionRow> thisWeek, LocalDate weekStart, LocalDate today) {
        Map<LocalDate, Long> secondsByDay = thisWeek.stream()
                .collect(Collectors.groupingBy(HomeSessionRow::startDate,
                        Collectors.summingLong(HomeService::lengthSeconds)));

        List<DayActivity> days = new ArrayList<>();
        for (LocalDate day = weekStart; !day.isAfter(today); day = day.plusDays(1)) {
            long seconds = secondsByDay.getOrDefault(day, 0L);
            String weekday = WEEKDAYS.get(day.getDayOfWeek().getValue() - 1);
            days.add(new DayActivity(weekday, (int) Math.round(seconds / SECONDS_PER_MINUTE)));
        }
        return new WeekActivity(weekStart.format(DATE_FORMAT) + " — " + today.format(DATE_FORMAT), days);
    }

    // 종료 시각이 없으면 0. 종료가 시작보다 앞서는 잘못된 값도 0 으로 본다
    private static long lengthSeconds(HomeSessionRow session) {
        if (session.endedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(session.startedAt(), session.endedAt()).toSeconds());
    }

    private static List<TimelineSlot> todayTimeline(List<HomeSessionRow> thisWeek, LocalDate today) {
        return thisWeek.stream()
                .filter(session -> session.startDate().equals(today))
                .map(session -> new TimelineSlot(
                        time(session.startedAt()),
                        session.endedAt() == null ? null : time(session.endedAt()),
                        session.tags(),
                        topSites(session)))
                .toList();
    }

    private static String time(LocalDateTime dateTime) {
        return dateTime.format(TIME_FORMAT);
    }

    // 도메인별 체류시간 합이 긴 순. 같으면 도메인 이름순
    private static List<String> topSites(HomeSessionRow session) {
        Map<String, Long> dwellByDomain = new LinkedHashMap<>();
        for (PageVisit visit : pageVisits(session)) {
            dwellByDomain.merge(visit.domain(), visit.dwellMs(), Long::sum);
        }
        return dwellByDomain.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_TODAY_SITES)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<WeekSession> weekSessions(List<HomeSessionRow> thisWeek, LocalDate today) {
        return thisWeek.stream()
                .map(session -> weekSession(session, today))
                .toList();
    }

    private static WeekSession weekSession(HomeSessionRow session, LocalDate today) {
        List<Visit> visits = pageVisits(session).stream()
                .map(visit -> new Visit(visit.domain(), Math.round(visit.dwellMs() / MILLIS_PER_SECOND)))
                .toList();
        int dayOffset = (int) ChronoUnit.DAYS.between(today, session.startDate());
        return new WeekSession(dayOffset, time(session.startedAt()), session.tags(), visits);
    }

    // session_results.pages 의 방문 글을 도메인 · 체류시간으로. 주소를 읽을 수 없는 항목은 뺀다
    private static List<PageVisit> pageVisits(HomeSessionRow session) {
        if (session.pages() == null) {
            return List.of();
        }
        List<PageVisit> visits = new ArrayList<>();
        for (Object item : session.pages()) {
            if (item instanceof Map<?, ?> page) {
                domainOf(page.get("url"))
                        .ifPresent(domain -> visits.add(new PageVisit(domain, nonNegative(page.get("dwellMs")))));
            }
        }
        return visits;
    }

    private static Optional<String> domainOf(Object url) {
        if (!(url instanceof String text)) {
            return Optional.empty();
        }
        Matcher origin = WEB_ORIGIN.matcher(text.strip());
        if (!origin.find()) {
            return Optional.empty();
        }
        return SiteCategory.extractHost(origin.group());
    }

    private static ReadingDistribution readingUnits(List<HomeSessionRow> sessions) {
        return ReadingDistribution.ofSummaries(sessions.stream().map(HomeSessionRow::analysisSummary).toList());
    }

    // AI JSON 의 체류시간. Integer · Long · Double 어느 쪽으로든 올 수 있고, 숫자가 아니거나 음수면 0
    private static long nonNegative(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    // 세션 수가 많은 순 → 더 최근 세션에 나온 순 → 이름순. 표기는 가장 최근 세션의 첫 표기
    private static List<TagTally> rankTags(List<HomeSessionRow> sessions) {
        Map<String, TagTally> tallyByKey = new LinkedHashMap<>();
        for (int order = 0; order < sessions.size(); order++) {
            Set<String> countedInSession = new HashSet<>();
            for (String tag : sessions.get(order).tags()) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                String key = tag.toLowerCase(Locale.ROOT);
                if (!countedInSession.add(key)) {
                    continue;
                }
                int latestOrder = order;
                tallyByKey.computeIfAbsent(key, newKey -> new TagTally(tag, latestOrder)).count++;
            }
        }
        return tallyByKey.values().stream()
                .sorted(Comparator.comparingInt(TagTally::count).reversed()
                        .thenComparingInt(TagTally::latestOrder)
                        .thenComparing(TagTally::tag))
                .toList();
    }

    private static List<TopTag> topTags(List<TagTally> tags) {
        return tags.stream()
                .limit(MAX_TOP_TAGS)
                .map(tally -> new TopTag(tally.tag(), tally.count()))
                .toList();
    }

    private static List<String> filterTags(List<TagTally> tags) {
        return tags.stream()
                .limit(MAX_FILTER_TAGS)
                .map(TagTally::tag)
                .toList();
    }

    private List<TagGroup> tagGroups(Long userId) {
        return tagGroupRepository.findByUserIdOrderByPositionAscIdAsc(userId).stream()
                .map(group -> new TagGroup(group.getId(), group.getName(), group.getHashtags()))
                .toList();
    }

    private static List<SessionListResponse> recentSessions(List<HomeSessionRow> sessions) {
        return sessions.stream()
                .limit(MAX_SESSIONS)
                .map(session -> new SessionListResponse(
                        session.id(),
                        session.title(),
                        session.startedAt(),
                        session.endedAt(),
                        session.pageCount(),
                        SessionStatus.COMPLETED,
                        session.favorite(),
                        session.publicSession(),
                        session.tags()))
                .toList();
    }

    private record PageVisit(String domain, long dwellMs) {
    }

    private static final class TagTally {

        private final String tag;
        private final int latestOrder;
        private int count;

        private TagTally(String tag, int latestOrder) {
            this.tag = tag;
            this.latestOrder = latestOrder;
        }

        private String tag() {
            return tag;
        }

        private int latestOrder() {
            return latestOrder;
        }

        private int count() {
            return count;
        }
    }
}
