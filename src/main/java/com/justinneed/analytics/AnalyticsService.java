package com.justinneed.analytics;
import com.justinneed.surfing.*;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.auth.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import static com.justinneed.surfing.SurfEvent.Behavior.*;
@Service @Transactional(readOnly=true)
public class AnalyticsService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    private final SurfSessionRepository sessions;
    private final SurfEventRepository events;
    private final BrowsingSessionRepository records;
    private final MemberRepository members;
    public AnalyticsService(SurfSessionRepository sessions,SurfEventRepository events,BrowsingSessionRepository records,MemberRepository members) {
        this.sessions=sessions; this.events=events; this.records=records; this.members=members;
    }
    private Window window(Long user,LocalDate from,LocalDate to,boolean timeline) {
        if(members.findByIdAndWithdrawnAtIsNull(user).isEmpty()) throw SurfErrors.missing();
        LocalDate today=LocalDate.now(ZONE);
        if(to==null) to=today;
        if(from==null) from=to.minusDays(6);
        if(to.isAfter(today)||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>89
            || timeline && (from.isBefore(today.minusDays(6))||to.isAfter(today))) throw SurfErrors.bad("Invalid date range; timeline supports today and previous six days");
        Instant start=from.atStartOfDay(ZONE).toInstant(), end=to.plusDays(1).atStartOfDay(ZONE).toInstant();
        var ownedRecords=records.findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(user);
        var recordIds=new HashSet<Long>(); ownedRecords.forEach(r->recordIds.add(r.getId()));
        var selected=sessions.findByUserIdAndStartedAtLessThanAndEndedAtGreaterThanEqualOrderByStartedAtDesc(user,end,start).stream()
            .filter(s->s.getRecordId()==null||recordIds.contains(s.getRecordId())).toList();
        var data=new ArrayList<SurfEvent>();
        selected.forEach(s->data.addAll(events.findBySessionIdOrderByOccurredAtAscIdAsc(s.getId()).stream()
            .filter(e->!e.getOccurredAt().isBefore(start)&&e.getOccurredAt().isBefore(end)).toList()));
        data.sort(Comparator.comparing(SurfEvent::getOccurredAt).thenComparing(SurfEvent::getId));
        return new Window(from,to,selected,List.copyOf(data));
    }
    public Sources sources(Long user,LocalDate from,LocalDate to) {
        var w=window(user,from,to,false);
        var visits=w.events.stream().filter(e->e.getType()==SurfEvent.Type.PAGE_VIEW).toList();
        long deep=w.events.stream().filter(e->e.getBehavior()==DEEP_READING).mapToLong(SurfEvent::getActiveMillis).sum();
        var rows=new ArrayList<Category>();
        for(var c:DomainCategory.values()) {
            long count=visits.stream().filter(e->DomainCategory.classify(e.getUrl())==c).count();
            long millis=w.events.stream().filter(e->e.getBehavior()==DEEP_READING&&DomainCategory.classify(e.getUrl())==c).mapToLong(SurfEvent::getActiveMillis).sum();
            rows.add(new Category(c,count,percent(count,visits.size()),millis,percent(millis,deep)));
        }
        var domains=group(w.events.stream().filter(e->e.getBehavior()==DEEP_READING&&e.getActiveMillis()>0).toList(),e->DomainCategory.host(e.getUrl()));
        String top=domains.entrySet().stream().max(Comparator.<Map.Entry<String,List<SurfEvent>>>comparingLong(e->e.getValue().stream().mapToLong(SurfEvent::getActiveMillis).sum()).thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElse(null);
        var ranked=rows.stream().sorted(Comparator.comparingLong(Category::visits).reversed().thenComparing(c->c.category().name())).toList();
        String frequency=visits.isEmpty()?"NO_DATA":ranked.get(0).visitPercent()>=60?"CONCENTRATED":"BALANCED";
        var suggestions=new ArrayList<String>();
        double primary=rows.stream().filter(c->c.category()==DomainCategory.BLOG||c.category()==DomainCategory.COMMUNITY).mapToDouble(Category::visitPercent).sum();
        if(primary>=60) suggestions.add("Explore official documentation or original sources.");
        rows.stream().filter(c->c.category()==DomainCategory.COMMUNITY&&c.visitPercent()>=50).findFirst()
            .ifPresent(c->suggestions.add("Community sources dominate; compare them with original documentation."));
        var visitDomains=group(visits,e->DomainCategory.host(e.getUrl()));
        visitDomains.entrySet().stream().filter(e->percent(e.getValue().size(),visits.size())>=60)
            .sorted(Map.Entry.comparingByKey()).findFirst().ifPresent(e->suggestions.add("Compare sources beyond "+e.getKey()+"."));
        return new Sources(w.from,w.to,rows,frequency,deep,top,suggestions);
    }
    public Timeline timeline(Long user,LocalDate from,LocalDate to) {
        var w=window(user,from,to,true);
        var tags=new HashMap<Long,List<String>>();
        for(var s:w.sessions) if(s.getRecordId()!=null) tags.put(s.getId(),records.findById(s.getRecordId()).orElseThrow().getTags());
        var visits=w.events.stream().filter(e->e.getType()==SurfEvent.Type.PAGE_VIEW)
            .map(e->new Visit(e.getOccurredAt(),e.getSessionId(),e.getUrl(),e.getTitle(),tags.getOrDefault(e.getSessionId(),List.of()))).toList();
        return new Timeline(w.from,w.to,ZONE.getId(),visits);
    }
    public TimePatterns time(Long user,LocalDate from,LocalDate to) {
        var w=window(user,from,to,false);
        var hours=new ArrayList<Hour>();
        for(int hour=0;hour<24;hour++) {
            int h=hour;
            var data=w.events.stream().filter(e->e.getOccurredAt().atZone(ZONE).getHour()==h).toList();
            long active=data.stream().mapToLong(SurfEvent::getActiveMillis).sum();
            long focused=data.stream().filter(e->e.getBehavior()==DEEP_READING||e.getBehavior()==READING).mapToLong(SurfEvent::getActiveMillis).sum();
            hours.add(new Hour(h,data.stream().filter(e->e.getType()==SurfEvent.Type.PAGE_VIEW).count(),active,percent(focused,active)));
        }
        Integer busiest=hours.stream().filter(h->h.activeMillis()>0).max(Comparator.comparingLong(Hour::activeMillis).thenComparing(h->-h.hour())).map(Hour::hour).orElse(null);
        Integer focus=hours.stream().filter(h->h.activeMillis()>=60000).max(Comparator.comparingDouble(Hour::focusedPercent).thenComparingLong(Hour::activeMillis).thenComparing(h->-h.hour())).filter(h->h.focusedPercent()>0).map(Hour::hour).orElse(null);
        double average=w.sessions.stream().mapToLong(s->Math.max(0,Duration.between(s.getStartedAt(),s.getEndedAt()).toSeconds())).average().orElse(0);
        long total=hours.stream().mapToLong(Hour::activeMillis).sum();
        String pattern=busiest==null?"NO_DATA":percent(hours.get(busiest).activeMillis(),total)>=30?"CONCENTRATED":"DISTRIBUTED";
        return new TimePatterns(w.from,w.to,average,hours,busiest,focus,pattern);
    }
    public Reading reading(Long user,LocalDate from,LocalDate to) {
        var w=window(user,from,to,false);
        long total=w.events.stream().mapToLong(SurfEvent::getActiveMillis).sum();
        var rows=new ArrayList<BehaviorCount>();
        for(var b:SurfEvent.Behavior.values()) {
            var matches=w.events.stream().filter(e->e.getBehavior()==b).toList();
            long active=matches.stream().mapToLong(SurfEvent::getActiveMillis).sum();
            rows.add(new BehaviorCount(b,matches.size(),active,percent(active,total)));
        }
        var dominant=rows.stream().filter(r->r.behavior()!=UNKNOWN&&r.activeMillis()>0).max(Comparator.comparingLong(BehaviorCount::activeMillis).thenComparing(r->r.behavior().name())).map(BehaviorCount::behavior).orElse(UNKNOWN);
        String description=switch(dominant) {
            case DEEP_READING -> "You tend to read deeply.";
            case READING -> "You spend time reading sources.";
            case SCANNING -> "You scan pages to find key information.";
            case NAVIGATION -> "You navigate between pages to find information.";
            default -> "Not enough classified reading data.";
        };
        return new Reading(w.from,w.to,rows,dominant,description);
    }
    public Exploration exploration(Long user,LocalDate from,LocalDate to) {
        var w=window(user,from,to,false);
        var perSession=new ArrayList<SessionPattern>();
        for(var s:w.sessions) {
            var data=w.events.stream().filter(e->e.getSessionId().equals(s.getId())).toList();
            long urls=data.stream().map(SurfEvent::getUrl).distinct().count();
            long active=data.stream().mapToLong(SurfEvent::getActiveMillis).sum();
            long focused=data.stream().filter(e->e.getBehavior()==DEEP_READING||e.getBehavior()==READING).mapToLong(SurfEvent::getActiveMillis).sum();
            double dwell=urls==0?0:active/1000.0/urls;
            String type=urls==0?"NO_DATA":urls>=10&&dwell<30?"DISTRACTED":percent(focused,active)>=60?"FOCUSED":urls>=10?"BROAD":"QUICK_CHECK";
            String tip=switch(type) {
                case "DISTRACTED" -> "Keep one important document open and focus for 15 minutes.";
                case "BROAD" -> "Compare sources when researching different topics.";
                case "QUICK_CHECK" -> "Choose one or two sources to read deeply.";
                case "FOCUSED" -> "Continue reading original sources in depth.";
                default -> "Collect more activity before interpreting this session.";
            };
            perSession.add(new SessionPattern(s.getId(),s.getStartedAt().atZone(ZONE).getDayOfWeek(),type,urls,dwell,tip));
        }
        var weekdays=new ArrayList<Weekday>();
        for(var day:DayOfWeek.values()) {
            var data=perSession.stream().filter(s->s.weekday()==day).toList();
            var counts=new TreeMap<String,Long>(); data.forEach(s->counts.merge(s.pattern(),1L,Long::sum));
            String dominant=counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("NO_DATA");
            weekdays.add(new Weekday(day,data.size(),dominant,data.stream().mapToLong(SessionPattern::uniqueUrls).average().orElse(0),data.stream().mapToDouble(SessionPattern::averageActiveSecondsPerUrl).average().orElse(0)));
        }
        return new Exploration("RULE_V1",perSession,weekdays);
    }
    public Tip tip() {
        var tips=List.of(new Tip("EXACT","Search with quoted keywords for exact matches."),
            new Tip("EXCLUDE","Use keyword -unwanted to exclude irrelevant results."),
            new Tip("SOURCE","Use site:github.com keyword to restrict sources."));
        return tips.get(ThreadLocalRandom.current().nextInt(tips.size()));
    }
    private static double percent(long part,long total) { return total==0?0:Math.round(part*10000.0/total)/100.0; }
    private static <T> Map<String,List<T>> group(List<T> list,Function<T,String> key) {
        var map=new TreeMap<String,List<T>>(); list.forEach(e->map.computeIfAbsent(key.apply(e),k->new ArrayList<>()).add(e)); return map;
    }
    private record Window(LocalDate from,LocalDate to,List<SurfSession> sessions,List<SurfEvent> events) {}
    public record Category(DomainCategory category,long visits,double visitPercent,long deepReadingMillis,double deepReadingPercent) {}
    public record Sources(LocalDate from,LocalDate to,List<Category> categories,String frequencyPattern,long totalDeepReadingMillis,String topDeepReadingDomain,List<String> suggestions) {}
    public record Visit(Instant occurredAt,Long sessionId,String url,String title,List<String> hashtags) {}
    public record Timeline(LocalDate from,LocalDate to,String timezone,List<Visit> visits) {}
    public record Hour(int hour,long visits,long activeMillis,double focusedPercent) {}
    public record TimePatterns(LocalDate from,LocalDate to,double averageSessionSeconds,List<Hour> hours,Integer busiestHour,Integer recommendedReadingHour,String pattern) {}
    public record BehaviorCount(SurfEvent.Behavior behavior,long events,long activeMillis,double percent) {}
    public record Reading(LocalDate from,LocalDate to,List<BehaviorCount> distribution,SurfEvent.Behavior dominant,String description) {}
    public record SessionPattern(Long sessionId,DayOfWeek weekday,String pattern,long uniqueUrls,double averageActiveSecondsPerUrl,String suggestion) {}
    public record Weekday(DayOfWeek weekday,long sessions,String dominant,double averageUniqueUrls,double averageActiveSecondsPerUrl) {}
    public record Exploration(String policyVersion,List<SessionPattern> sessions,List<Weekday> weekdays) {}
    public record Tip(String id,String text) {}
}
