package com.justinneed;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class JustInNeedApplication {

    // 서버 시간대는 KST 로 통일한다. LocalDateTime.now() · DB 시각 · 스케줄러 cron 이 모두 이 기준
    public static final String TIME_ZONE = "Asia/Seoul";

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
        SpringApplication.run(JustInNeedApplication.class, args);
    }
}
