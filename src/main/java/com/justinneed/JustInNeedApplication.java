package com.justinneed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JustInNeedApplication {

    public static void main(String[] args) {
        SpringApplication.run(JustInNeedApplication.class, args);
    }
}
