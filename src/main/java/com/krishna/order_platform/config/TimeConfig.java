package com.krishna.order_platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    // Injectable clock so tests can fix "now" and assert exact timestamps.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}