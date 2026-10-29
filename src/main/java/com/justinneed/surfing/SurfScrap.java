package com.justinneed.surfing;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
@Entity @Getter @Table(uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","clientId"}))
public class SurfScrap {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long sessionId; private String clientId;
    @Column(length=2048) private String url;
    @Column(length=300) private String title;
    @Column(columnDefinition="text") private String text;
    private Instant savedAt;
    protected SurfScrap() {}
    public SurfScrap(Long id, SurfRequests.Scrap r, Instant now) {
        sessionId=id; clientId=r.clientId(); url=r.url(); title=r.title(); text=r.text(); savedAt=now;
    }
}
