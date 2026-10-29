package com.justinneed.surfing;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.api.SelectionKey;
import com.justinneed.settings.collection.service.ExcludedUrlService;
import com.justinneed.session.management.domain.*;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import com.justinneed.global.common.HashtagValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.*;
import java.util.*;
@Service @Transactional
public class SurfService {
    private final SurfSessionRepository sessions;
    private final SurfEventRepository events;
    private final SurfScrapRepository scraps;
    private final ExtensionConnectionRepository connections;
    private final MemberRepository members;
    private final CommunityMemberRepository locks;
    private final ExcludedUrlService excluded;
    private final BrowsingSessionRepository records;
    private final SurfModel model;
    public SurfService(SurfSessionRepository sessions,SurfEventRepository events,SurfScrapRepository scraps,
        ExtensionConnectionRepository connections,MemberRepository members,CommunityMemberRepository locks,
        ExcludedUrlService excluded,BrowsingSessionRepository records,SurfModel model) {
        this.sessions=sessions; this.events=events; this.scraps=scraps; this.connections=connections;
        this.members=members; this.locks=locks; this.excluded=excluded; this.records=records; this.model=model;
    }
    public Connection connect(Long user) {
        memberLock(user);
        String token=UUID.randomUUID().toString()+UUID.randomUUID();
        Instant expiry=Instant.now().plus(Duration.ofDays(30));
        connections.save(new ExtensionConnection(user,SelectionKey.of(token),expiry));
        return new Connection(token,expiry);
    }
    private void memberLock(Long user) {
        var m=locks.lockById(user).orElseThrow(SurfErrors::missing);
        if(m.isWithdrawn()) throw SurfErrors.missing();
    }
    private void connection(Long user,String token) {
        if(token==null || connections.findById(user).filter(c -> c.getExpiresAt().isAfter(Instant.now())
            && c.getTokenHash().equals(SelectionKey.of(token))).isEmpty()) throw SurfErrors.conflict("Link extension first");
    }
    public View start(Long user,String token) {
        memberLock(user); connection(user,token);
        if(sessions.existsByUserIdAndState(user,SurfSession.State.COLLECTING)) throw SurfErrors.conflict("Collection already active");
        return view(sessions.save(new SurfSession(user,Instant.now())));
    }
    public SurfSession owned(Long user,Long id) { return sessions.owned(id,user).orElseThrow(SurfErrors::missing); }
    private void collecting(SurfSession s) {
        if(s.getState()!=SurfSession.State.COLLECTING) throw SurfErrors.conflict("Collection is closed");
        if(!s.getLastActivityAt().plusSeconds(1800).isAfter(Instant.now())) throw SurfErrors.conflict("Collection expired");
    }
    public View end(Long user,Long id,String token,SurfRequests.EndReason reason) {
        connection(user,token); var s=owned(user,id);
        if(s.getState()==SurfSession.State.COLLECTING) s.end(Instant.now(),reason.name());
        return view(s);
    }
    public View status(Long user,Long id) { var s=owned(user,id); expire(s); return view(s); }
    public View retry(Long user,Long id) {
        var s=owned(user,id);
        if(s.getState()!=SurfSession.State.FAILED) throw SurfErrors.conflict("Only failed analysis can be retried");
        if(s.getAttempts()>=5) throw SurfErrors.conflict("Retry limit reached");
        s.retry(); return view(s);
    }
    public Accepted collect(Long user,Long id,String token,SurfRequests.Batch batch) {
        connection(user,token); var s=owned(user,id); collecting(s);
        if(events.countBySessionId(id)+batch.events().size()>5000) throw SurfErrors.conflict("Session event limit reached");
        int accepted=0,ignored=0;
        for(var e:batch.events()) {
            if(e.occurredAt().isBefore(s.getStartedAt()) || e.occurredAt().isAfter(Instant.now().plusSeconds(5)))
                throw SurfErrors.bad("Event timestamp outside session");
            if(blocked(user,e.url()) || events.existsBySessionIdAndClientId(id,e.clientId())) { ignored++; continue; }
            events.save(new SurfEvent(id,user,e)); accepted++;
        }
        if(accepted>0) s.touch(Instant.now());
        return new Accepted(accepted,ignored);
    }
    public SurfScrap scrap(Long user,Long id,String token,SurfRequests.Scrap request) {
        connection(user,token); var s=owned(user,id); collecting(s);
        if(blocked(user,request.url())) throw SurfErrors.bad("URL is excluded");
        var old=scraps.findBySessionIdAndClientId(id,request.clientId());
        if(old.isPresent()) return old.get();
        if(scraps.countBySessionId(id)>=100) throw SurfErrors.conflict("Scrap limit reached");
        s.touch(Instant.now()); return scraps.save(new SurfScrap(id,request,Instant.now()));
    }
    public List<SurfScrap> scraps(Long user,Long id) { owned(user,id); return scraps.findBySessionIdOrderBySavedAtAsc(id); }
    public boolean blocked(Long user,String url) {
        URI target=url(url);
        return excluded.getExcludedUrls(user,false,0,100).excludedUrls().stream().filter(x -> x.isExcluded()).anyMatch(x -> {
            URI rule=url(x.url()); String host=target.getHost().toLowerCase(Locale.ROOT);
            String domain=rule.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
            String path=Optional.ofNullable(rule.getPath()).orElse("");
            String actual=Optional.ofNullable(target.getPath()).orElse("");
            return (host.equals(domain)||host.endsWith("."+domain)) && (path.isEmpty()||path.equals("/")||actual.equals(path)||actual.startsWith(path.endsWith("/")?path:path+"/"));
        });
    }
    private URI url(String value) {
        try { URI u=URI.create(value); if(u.getHost()==null || u.getUserInfo()!=null ||
            !List.of("http","https").contains(u.getScheme().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(); return u;
        } catch(Exception e) { throw SurfErrors.bad("HTTP(S) URL required"); }
    }
    public void expire(Long user,Long id) { expire(owned(user,id)); }
    private void expire(SurfSession s) {
        if(s.getState()==SurfSession.State.COLLECTING && !s.getLastActivityAt().plusSeconds(1805).isAfter(Instant.now()))
            s.end(s.getLastActivityAt().plusSeconds(1805),"INACTIVITY");
    }
    public void process(Long user,Long id) {
        var s=owned(user,id); if(s.getState()!=SurfSession.State.ANALYZING) return;
        s.attempt();
        List<SurfEvent> data;
        SurfModel.Result result;
        List<String> tags;
        try {
            data=events.findBySessionIdOrderByOccurredAtAscIdAsc(id);
            if(data.isEmpty()) throw new IllegalStateException();
            result=model.analyze(data);
            if(result==null || result.title()==null || result.title().isBlank() || result.title().length()>100 ||
                result.markdown()==null || result.markdown().length()>100000 || result.behaviors()==null) throw new IllegalStateException();
            tags=HashtagValidator.normalizeAndValidate(result.tags()==null?List.of():result.tags());
            for(var e:data) if(result.behaviors().get(e.getClientId())==null) throw new IllegalStateException();
        } catch(Exception e) { s.fail("Analysis unavailable or invalid; retry later"); return; }
            var record=new BrowsingSession(user,result.title(),LocalDateTime.ofInstant(s.getStartedAt(),ZoneOffset.UTC));
            record.update(null,null,false,false,tags,null);
            var sources=new LinkedHashMap<String,Source>();
            data.forEach(e -> sources.putIfAbsent(e.getUrl(),new Source(e.getTitle(),e.getUrl(),e.getContent())));
            record.replaceSources(List.copyOf(sources.values()));
            record.complete(LocalDateTime.ofInstant(s.getEndedAt(),ZoneOffset.UTC),sources.size());
            new Summary(record,result.title(),result.markdown());
            records.saveAndFlush(record);
            data.forEach(e -> e.classify(result.behaviors().get(e.getClientId())));
            s.done(record.getId());
    }
    private View view(SurfSession s) {
        Instant now=Instant.now();
        return new View(s.getId(),s.getState(),s.getStartedAt(),s.getEndedAt(),s.getLastActivityAt(),
            Math.max(0,Duration.between(s.getStartedAt(),s.getEndedAt()==null?now:s.getEndedAt()).toSeconds()),
            s.getLastActivityAt().plusSeconds(1800),s.getLastActivityAt().plusSeconds(1805),
            s.getRecordId(),s.getFailure(),s.getAttempts());
    }
    public record Connection(String extensionToken,Instant expiresAt) {}
    public record Accepted(int accepted,int ignored) {}
    public record View(Long sessionId,SurfSession.State status,Instant startedAt,Instant endedAt,Instant lastActivityAt,
        long elapsedSeconds,Instant inactivityNoticeAt,Instant automaticEndAt,Long recordId,String failure,int attempts) {}
}
