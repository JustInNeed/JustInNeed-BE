package com.justinneed.community.activity;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommunityClockConfig {
    @Bean
    public Clock communityClock() {
        return Clock.systemUTC();
    }
}
