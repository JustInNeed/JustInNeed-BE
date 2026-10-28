package com.justinneed.community.activity;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class CommunityClockConfig {
    @Bean
    public Clock communityClock() { return Clock.systemUTC(); }
}
